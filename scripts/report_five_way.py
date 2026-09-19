#!/usr/bin/env python3
"""Verify and report the five-runtime workload comparison."""
import argparse
import hashlib
import itertools
import json
import math
from pathlib import Path

RUNTIMES = {"ce": "CE", "kyo": "Kyo", "loom": "Loom / JDK", "ox": "Ox", "gears": "Gears"}
PROFILES = {
    "measured": dict(forks=3, warmupIterations=3, measurementIterations=3, time="1 s", cli_time="1s"),
    "smoke": dict(forks=1, warmupIterations=1, measurementIterations=1, time="100 ms", cli_time="100ms"),
}
FILES = ("synthetic.json", "tcp.json")
BASE_ARGS = {"--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xms2g", "-Xmx2g", "-XX:+UseG1GC"}
VALIDATION_MARKERS = [
    "PASS 984 checks",
    "PASS 1863 five-way synthetic checks",
    "PASS 1116 five-way fairness checks",
    "PASS 366 I/O checks (ce,kyo,loom,ox,gears)",
    "PASS 30 measured-concurrency checks (ce,kyo,loom,ox,gears)",
]


def configurations():
    result = {}
    def add(benchmark, label, params, modes=("thrpt",)):
        for values in itertools.product(*params.values()):
            p = dict(zip(params, values))
            for mode in modes:
                result[(benchmark, mode, tuple(sorted(p.items())))] = label
    runtime = dict(runtime=tuple(RUNTIMES))
    for method, label in (("workers", "Bounded workers"), ("collectSuccesses", "Collect successes")):
        add(f"bench.direct.ParallelBench.{method}", label,
            dict(**runtime, size=("4096",), parallelism=("8",), work=("0", "64")))
    for method, label in (("ref", "CAS reference updates"), ("promise", "Complete then read promise"),
                          ("queue", "Single-producer / consumer queue"), ("semaphore", "Uncontended non-reentrant permit"),
                          ("spawnJoin", "Sequential child spawn / join")):
        add(f"bench.direct.PrimitivesBench.{method}", label, dict(**runtime, ops=("1000",), capacity=("64",)))
    for method, label in (("sequentialChunks", "Sequential chunk transformation"),
                          ("parallelChunks", "Parallel chunk transformation"), ("queueChunks", "Queue-backed chunk pipeline")):
        add(f"bench.direct.PipelineBench.{method}", label,
            dict(**runtime, size=("10000",), chunkSize=("64",), parallelism=("4",), capacity=("64",), work=("0", "64")))
    add("bench.direct.RunnerBench.entry", "Runtime entry", runtime)
    add("bench.direct.SequentialBaseline.increment", "Sequential arithmetic: separate programming-style baseline",
        dict(**runtime, depth=("1000", "10000")))
    add("bench.io.IoBench.requests", "TCP request batches",
        dict(**runtime, transport=("blocking", "nonblocking"), size=("256",), parallelism=("8", "64"), delayMicros=("1000",)),
        modes=("thrpt", "sample"))
    return result


EXPECTED = configurations()


def key(row):
    return row["benchmark"], row["mode"], tuple(sorted(row["params"].items()))


def verify(rows, profile):
    settings = PROFILES[profile]
    found = {}
    jvms = set()
    for row in rows:
        k = key(row)
        if k not in EXPECTED or k in found:
            raise ValueError("Unexpected or duplicate workload")
        if any(row[name] != settings[name] for name in ("forks", "warmupIterations", "measurementIterations")):
            raise ValueError("Incomplete measurement settings")
        if row["threads"] != 1 or row["warmupTime"] != settings["time"] or row["measurementTime"] != settings["time"]:
            raise ValueError("Unmatched threads or iteration lengths")
        args = row["jvmArgs"]
        # sbt's inherited Test JVM options and @Fork can supply identical flags twice.
        # Only exact duplicates are harmless; different heap values/tuning remain rejected.
        if set(args) != BASE_ARGS:
            raise ValueError("Unmatched JVM arguments or hidden runtime tuning")
        jvms.add((row["jvm"], row["jdkVersion"], row["jmhVersion"]))
        metric = row["primaryMetric"]
        unit = "ops/s" if row["mode"] == "thrpt" else "s/op"
        if metric["scoreUnit"] != unit or not math.isfinite(metric["score"]) or metric["score"] <= 0:
            raise ValueError("Invalid primary metric")
        if profile == "measured" and (not math.isfinite(metric["scoreError"]) or metric["scoreError"] < 0):
            raise ValueError("Missing confidence interval")
        raw = metric.get("rawData", metric.get("rawDataHistogram", []))
        if len(raw) != settings["forks"] or any(len(fork) != settings["measurementIterations"] for fork in raw):
            raise ValueError("Incomplete fork samples")
        for name, resource_unit in (("gc.alloc.rate.norm", "B/op"), ("client.cpu", "ms/batch")):
            value = row["secondaryMetrics"][name]
            if value["scoreUnit"] != resource_unit or not math.isfinite(value["score"]) or value["score"] < 0:
                raise ValueError("Invalid resource metric")
        if row["mode"] == "sample":
            p = metric["scorePercentiles"]
            if not 0 < p["50.0"] <= p["99.0"] < math.inf:
                raise ValueError("Invalid latency percentiles")
        found[k] = row
    if set(found) != set(EXPECTED) or len(jvms) != 1:
        raise ValueError(f"Expected {len(EXPECTED)} configurations on one JVM version; got {len(found)}")
    return found


def load_verified(directory):
    meta = json.loads((directory / "metadata.json").read_text())
    if meta.get("exit_code") != 0 or meta.get("source_changed_during_run", True):
        raise ValueError("Incomplete or changed run")
    if meta.get("validation") != dict(markers=VALIDATION_MARKERS, passed=True):
        raise ValueError("Correctness validation missing")
    log = (directory / "run.log").read_bytes()
    if hashlib.sha256(log).hexdigest() != meta.get("run_log_sha256"):
        raise ValueError("Run log hash mismatch")
    for marker in VALIDATION_MARKERS:
        if marker.encode() not in log:
            raise ValueError("Validation marker missing from log")
    rows = []
    for name in FILES:
        data = (directory / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != meta["result_sha256"].get(name):
            raise ValueError("Result hash mismatch")
        rows.extend(json.loads(data))
    verify(rows, meta["profile"])
    return rows, meta


def render(rows, meta):
    indexed = verify(rows, meta["profile"])
    settings = PROFILES[meta["profile"]]
    lines = ["# CE, Kyo, Loom, Ox and Gears", ""]
    if meta["profile"] == "smoke":
        lines += ["SMOKE TEST ONLY. These short runs verify execution; they do not support performance conclusions.", ""]
    lines += [
        "CE 3.7.1, Kyo 1.0.0-RC6, Ox 1.0.6, Gears 0.3.1; Scala 3.8.4.",
        f"JDK {rows[0]['jdkVersion']}, JMH {rows[0]['jmhVersion']}. Started {meta['started_at']}.",
        f"{settings['forks']} JVM forks, {settings['warmupIterations']} warmup and {settings['measurementIterations']} measurement iterations of {settings['time']} per case; one JMH caller; 2 GiB G1 heap.",
        "Each operation includes runtime entry, task creation, work and result collection. Entry overhead is reported separately and never subtracted.",
        "Allocation is bytes allocated per complete operation; CPU is client process CPU milliseconds per operation. Neither measures retained memory or native stacks.",
        "Throughput errors are JMH's 99.9% confidence-interval half-widths. Ratios are ratios of means, not speedup confidence intervals; overlapping throughput intervals do not establish a winner.",
        "One host, short iterations and a fixed case order limit generalization. These results describe the listed constructions, including their adapters and bookkeeping.", "",
        "[Methods and API mapping](../../docs/five-way.md). [Metadata](metadata.json), [run log](run.log), [synthetic JSON](synthetic.json), [TCP JSON](tcp.json).", "",
        "Loom uses JDK virtual threads, ArrayBlockingQueue, CompletableFuture, Semaphore and AtomicReference. Ox uses forks, channels and Flow, with JDK promises, permits and atomics. Gears uses Futures, channels and semaphores, with JDK atomics.",
        "Pipeline rows compare fs2, Kyo Stream, Ox Flow and explicit Loom/Gears loops with the same chunk barriers. They compare workloads across different APIs.", "",
    ]
    groups = {}
    for k, label in EXPECTED.items():
        benchmark, mode, params = k
        if mode != "thrpt":
            continue
        group = (benchmark, tuple((name, value) for name, value in params if name != "runtime"))
        groups.setdefault(group, label)
    for (benchmark, params), label in groups.items():
        lines += [f"## {label}", "", ", ".join(f"{name}={value}" for name, value in params) or "No workload parameters.", ""]
        sequential = "SequentialBaseline" in benchmark
        tcp = benchmark == "bench.io.IoBench.requests"
        if sequential:
            lines += ["CE/Kyo execute the suspended deep-bind calculation. Loom/Ox/Gears execute a direct while loop, which the JIT may simplify. This is not an equivalent bind-chain implementation; no relative speedup is reported.", ""]
        if tcp:
            lines += ["256 exchanges through persistent loopback connections. The separate server requests 1 ms per response and shares the host CPU. p50/p99 are closed-loop batch latencies from separate sample-time trials.", ""]
        suffix = " | Batch p50 ms | Batch p99 ms" if tcp else ""
        lines += ["| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op" + suffix + " |",
                  "| --- | ---: | --- | ---: | ---:" + (" | ---: | ---:" if tcp else "") + " |"]
        baseline = indexed[(benchmark, "thrpt", tuple(sorted((*params, ("runtime", "ce")))))]["primaryMetric"]
        for runtime, display in RUNTIMES.items():
            p = tuple(sorted((*params, ("runtime", runtime))))
            row = indexed[(benchmark, "thrpt", p)]
            metric = row["primaryMetric"]
            error = metric["scoreError"]
            error_text = f"{error:,.2f}" if isinstance(error, (int, float)) and math.isfinite(error) else "n/a"
            ratio = "n/a" if sequential or meta["profile"] == "smoke" else "baseline" if runtime == "ce" else f"{metric['score']/baseline['score']:.2f}x"
            if ratio not in ("n/a", "baseline") and abs(metric["score"] - baseline["score"]) <= error + baseline["scoreError"]:
                ratio += " (CIs overlap)"
            resources = row["secondaryMetrics"]
            line = f"| {display} | {metric['score']:,.2f} ± {error_text} | {ratio} | {resources['gc.alloc.rate.norm']['score']:,.0f} | {resources['client.cpu']['score']:.4f}"
            if tcp:
                pctl = indexed[(benchmark, "sample", p)]["primaryMetric"]["scorePercentiles"]
                line += f" | {pctl['50.0']*1000:.3f} | {pctl['99.0']*1000:.3f}"
            lines.append(line + " |")
        lines.append("")
    lines += ["Successful-work performance does not establish equivalent cancellation, timeout, race or resource-cleanup guarantees. The TCP checks cover join-all failure handling; separate cancellation comparisons remain out of scope.", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    rows, meta = load_verified(args.directory)
    (args.directory / "report.md").write_text(render(rows, meta))
