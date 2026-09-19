#!/usr/bin/env python3
"""Verify and summarize CE/Loom memory occupancy, without performance rankings."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

from run_memory import JVM_ARGS, MARKER, cases


def verify(rows, profile):
    forks = 3 if profile == "measured" else 1
    expected = {(runtime, mode, count, setting, fork) for mode, count, setting in cases(profile)
                for runtime in ("ce", "loom") for fork in range(forks)}
    found = set()
    for row in rows:
        key = tuple(row[k] for k in ("runtime", "mode", "count", "setting", "fork"))
        if key not in expected or key in found or row["exit_code"] != 0 or row["jvm_args"] != JVM_ARGS:
            raise ValueError("Unexpected, repeated, failed or differently configured memory probe")
        found.add(key)
        parked = row["mode"] == "parked"
        stages = ["hello", "baseline", "held", "live", "released", "complete"] if parked else [
            "hello", "baseline", "active", "idle", "complete"]
        if [e["stage"] for e in row["events"]] != stages:
            raise ValueError("Incomplete memory lifecycle")
        events = {e["stage"]: e for e in row["events"]}
        hello = events["hello"]
        if any(hello[k] != row[k] for k in ("runtime", "mode", "count", "setting")):
            raise ValueError("Probe parameters differ from requested parameters")
        inputs = hello["input_args"]
        if hello["jdk"] != "25.0.3" or inputs[:-1] != JVM_ARGS or not inputs[-1].startswith("-Xlog:gc=info:file="):
            raise ValueError("Different actual JVM flags or JDK")
        for stage in stages[1:]:
            e = events[stage]
            if not 0 < e["heap_used"] <= e["heap_committed"] or not 0 < e["nonheap_used"] <= e["nonheap_committed"]:
                raise ValueError("Invalid occupied/committed heap")
        if parked:
            for stage in ("held", "live"):
                if events[stage]["waiting"] != row["count"] or events[stage]["completed"] != 0:
                    raise ValueError("Not all tasks were held at measurement")
            if events["released"]["completed"] != row["count"]:
                raise ValueError("Not all tasks completed")
            gc_stages = ("baseline", "live", "released")
            counts = dict(baseline=5, held=10, live=5, released=5)
        else:
            if events["idle"]["checked_batches"] <= 0:
                raise ValueError("No TCP work completed")
            gc_stages = ("baseline", "idle")
            counts = dict(baseline=5, active=50 if profile == "measured" else 10, idle=5)
        for before, after in zip(gc_stages, gc_stages[1:]):
            if events[after]["gc_count"] < events[before]["gc_count"] + 3:
                raise ValueError("Missing explicit collections")
        actual_counts = {stage: sum(s["stage"] == stage for s in row["samples"]) for stage in counts}
        if actual_counts != counts or len(row["samples"]) != sum(counts.values()):
            raise ValueError("Incomplete process memory samples")
        for sample in row["samples"]:
            if min(sample[k] for k in ("rss", "footprint", "peak_footprint")) <= 0:
                raise ValueError("Invalid process memory sample")
            if max(sample["footprint"], sample["peak_footprint"]) > row["peak_footprint_before_exit"]:
                raise ValueError("Invalid kernel peak footprint")
    if found != expected:
        raise ValueError(f"Expected {len(expected)} fresh JVMs; got {len(found)}")


def load_verified(directory):
    meta = json.loads((directory / "metadata.json").read_text())
    if not meta.get("complete") or meta.get("source_changed_during_run", True):
        raise ValueError("Incomplete run or changed sources")
    if meta.get("validation") != dict(marker=MARKER, passed=True):
        raise ValueError("Workload validation missing")
    log = (directory / "validation.log").read_bytes()
    if hashlib.sha256(log).hexdigest() != meta["validation_log_sha256"] or MARKER.encode() not in log:
        raise ValueError("Validation log mismatch")
    raw = (directory / "memory.json").read_bytes()
    if hashlib.sha256(raw).hexdigest() != meta["result_sha256"]:
        raise ValueError("Memory results changed")
    rows = json.loads(raw)
    for row in rows:
        for suffix, field in (("gc.log", "gc_log_sha256"), ("stderr.log", "stderr_sha256")):
            data = (directory / f"{row['id']}.{suffix}").read_bytes()
            if hashlib.sha256(data).hexdigest() != row[field]:
                raise ValueError("Probe log hash mismatch")
            if suffix == "gc.log":
                minimum = 9 if row["mode"] == "parked" else 6
                if data.count(b"Pause Full (System.gc())") < minimum:
                    raise ValueError("Full GC confirmation missing")
    verify(rows, meta["profile"])
    return rows, meta


def metrics(row):
    e = {event["stage"]: event for event in row["events"]}
    parked = row["mode"] == "parked"
    stage = "live" if parked else "active"
    measured = [s for s in row["samples"] if s["stage"] == stage]
    result = dict(footprint=statistics.median(s["footprint"] for s in measured),
                  rss=statistics.median(s["rss"] for s in measured),
                  peak_footprint=row["peak_footprint_before_exit"],
                  baseline_heap=e["baseline"]["heap_used"],
                  post_heap=e["released" if parked else "idle"]["heap_used"])
    if parked:
        result.update(live_heap=e["live"]["heap_used"],
                      heap_per_task=(e["live"]["heap_used"] - e["baseline"]["heap_used"]) / row["count"],
                      pre_gc_footprint=statistics.median(s["footprint"] for s in row["samples"] if s["stage"] == "held"))
    return result


def render(rows, meta):
    verify(rows, meta["profile"])
    def cell(selected, metric, scale=1024**2):
        values = [metrics(row)[metric] / scale for row in selected]
        return f"{statistics.median(values):.2f} [{min(values):.2f}, {max(values):.2f}]"
    lines = ["# Cats Effect versus Loom: memory consumption", "",
             "CE 3.7.1; JDK 25.0.3; Scala 3.8.4; macOS. G1, initial heap 64 MiB, maximum heap 2 GiB, no pre-touch or NMT.",
             "Three fresh JVMs per case. Cells are the median [minimum, maximum] across JVMs. Memory is MiB unless stated otherwise.",
             "Physical footprint and RSS come from macOS proc_pid_rusage for the client PID. They are different OS accounting metrics and are not added together.",
             "Peak is the maximum of the kernel's reported lifetime peak and observed footprints through the final snapshot. It includes startup, warmup, work and explicit GC. The separate TCP server is excluded.",
             "Occupied heap is sampled after three confirmed full collections; it approximates live heap and is not an allocation-rate metric.", "",
             "[Method and reproduction](../../docs/memory.md). [Raw measurements](memory.json). [Provenance](metadata.json).", ""]
    if meta["profile"] != "measured":
        lines[2:2] = ["SMOKE CHECK ONLY: one JVM per case and shorter TCP sampling; do not use for memory comparisons.", ""]
    for payload in ("0", "1024"):
        lines += [f"## Waiting tasks: {payload} payload bytes per task", "",
                  "Each child retains its own byte array across the wait and one join handle remains reachable per child. CE waits on Deferred; Loom uses CountDownLatch and a virtual-thread executor.",
                  "Post-GC process memory is reported while every task remains held. Incremental heap/task subtracts the warmed baseline; it includes wait objects, task handles and payload array headers.", "",
                  "| Tasks | Runtime | Live heap | Incremental heap B/task | Physical footprint | RSS | Peak footprint | Heap after release |",
                  "| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
        for mode, count, setting in cases(meta["profile"]):
            if mode != "parked" or setting != payload:
                continue
            for runtime in ("ce", "loom"):
                selected = [r for r in rows if (r["mode"], r["count"], r["setting"], r["runtime"]) == (mode, count, setting, runtime)]
                lines.append(f"| {count:,} | {runtime} | {cell(selected,'live_heap')} | {cell(selected,'heap_per_task',1)} | {cell(selected,'footprint')} | {cell(selected,'rss')} | {cell(selected,'peak_footprint')} | {cell(selected,'post_heap')} |")
        lines.append("")
    lines += ["## TCP client memory", "",
              "256 exchanges per batch, 8 or 64 persistent connections, requested 1 ms server delay. After 32 warmup batches, memory is sampled during five seconds of continuous batches. Each batch's ordered output is checked.",
              "Active footprint/RSS are medians of 50 samples per JVM. Post-work heap is measured after the driver has joined and full GC has completed, with persistent sockets still open. It is not the heap of a suspended in-flight batch.", "",
              "| Connections | Transport | Runtime | Active footprint | Active RSS | Peak footprint | Post-work heap |",
              "| ---: | --- | --- | ---: | ---: | ---: | ---: |"]
    for mode, count, setting in cases(meta["profile"]):
        if mode != "tcp":
            continue
        for runtime in ("ce", "loom"):
            selected = [r for r in rows if (r["mode"],r["count"],r["setting"],r["runtime"]) == (mode,count,setting,runtime)]
            lines.append(f"| {count} | {setting} | {runtime} | {cell(selected,'footprint')} | {cell(selected,'rss')} | {cell(selected,'peak_footprint')} | {cell(selected,'post_heap')} |")
    lines += ["", "These measurements cover the listed wait primitives, task handles, payload and TCP construction. They do not establish a universal number of bytes per fiber or virtual thread. Stack depth, application state, heap policy and workload duration can change the result.", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    rows, meta = load_verified(args.directory)
    (args.directory / "report.md").write_text(render(rows, meta))
