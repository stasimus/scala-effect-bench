#!/usr/bin/env python3
"""Report verified four-library blocking and nonblocking TCP measurements."""
import argparse
import hashlib
import itertools
import json
import math
from pathlib import Path

DEFAULTS = {"ce": "CE", "kyo": "Kyo", "gears": "Gears", "ox": "Ox"}
TUNED = {"ceVirtual": "CE virtual lanes", "kyoFlush": "Kyo queue flush", "kyoTuned": "Kyo flush + fixed64 + scan64"}
EXPECTED = set(itertools.product(("thrpt", "sample"), ("blocking", "nonblocking"), ("8", "64"), DEFAULTS))
EXPECTED |= {(mode, "blocking", "64", runtime) for mode in ("thrpt", "sample") for runtime in TUNED}
TUNING = {f"-Dkyo.scheduler.{key}=64" for key in ("coreWorkers", "minWorkers", "maxWorkers", "scheduleStride")}
FILES = ("default.json", "controls.json", "kyo-tuned.json")


def measurements(rows):
    indexed = {}
    jvms = set()
    for row in rows:
        p = row["params"]
        key = (row["mode"], p["transport"], p["parallelism"], p["runtime"])
        if key not in EXPECTED or key in indexed or row["benchmark"] != "bench.io.IoBench.requests":
            raise ValueError("Unknown or duplicate configuration")
        if set(p) != {"runtime", "transport", "parallelism", "delayMicros", "size"} or p["size"] != "256" or p["delayMicros"] != "1000":
            raise ValueError("Unmatched workload")
        if (row["forks"], row["warmupIterations"], row["measurementIterations"], row["threads"]) != (3, 10, 5, 1):
            raise ValueError("Incomplete run settings")
        if row["warmupTime"] != "1 s" or row["measurementTime"] != "1 s":
            raise ValueError("Wrong iteration duration")
        args = row["jvmArgs"]
        if not {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC"}.issubset(args):
            raise ValueError("Unmatched heap or collector")
        custom = {arg for arg in args if arg.startswith("-Dkyo.")}
        if custom != (TUNING if p["runtime"] == "kyoTuned" else set()):
            raise ValueError("Undeclared scheduler tuning")
        jvms.add((row["jvm"], row["jdkVersion"], row["jmhVersion"], tuple(a for a in args if a not in TUNING)))
        metric = row["primaryMetric"]
        unit = "ops/s" if row["mode"] == "thrpt" else "s/op"
        if metric["scoreUnit"] != unit or not math.isfinite(metric["score"]) or metric["score"] <= 0:
            raise ValueError("Invalid primary metric")
        if not math.isfinite(metric["scoreError"]) or metric["scoreError"] < 0:
            raise ValueError("Missing confidence interval")
        raw = metric.get("rawData", metric.get("rawDataHistogram", []))
        if len(raw) != 3 or any(len(fork) != 5 for fork in raw):
            raise ValueError("Incomplete fork data")
        for name, unit in (("gc.alloc.rate.norm", "B/op"), ("client.cpu", "ms/batch")):
            value = row["secondaryMetrics"][name]
            if value["scoreUnit"] != unit or not math.isfinite(value["score"]) or value["score"] <= 0:
                raise ValueError("Invalid resource metric")
        if row["mode"] == "sample":
            percentiles = metric["scorePercentiles"]
            if not 0 < percentiles["50.0"] <= percentiles["99.0"] < math.inf:
                raise ValueError("Invalid latency percentiles")
        indexed[key] = row
    if set(indexed) != EXPECTED or len(jvms) != 1:
        raise ValueError(f"Expected 38 matched configurations, got {len(indexed)}")
    return indexed


def load_verified(directory):
    meta = json.loads((directory / "metadata.json").read_text())
    if meta.get("exit_code") != 0 or meta.get("source_changed_during_run", True):
        raise ValueError("Incomplete or changed run")
    if meta.get("validation") != {"default_checks": 367, "tuned_checks": 38, "passed": True}:
        raise ValueError("Correctness validation missing")
    rows = []
    for name in FILES:
        data = (directory / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != meta["result_sha256"].get(name):
            raise ValueError("Result hash mismatch")
        rows.extend(json.loads(data))
    measurements(rows)
    return rows, meta


def render(rows, meta):
    indexed = measurements(rows)
    lines = ["# CE, Kyo, Gears and Ox: TCP I/O", "",
        "CE 3.7.1, Kyo 1.0.0-RC6, Gears 0.3.1, Ox 1.0.6; Scala 3.8.4.",
        f"JDK {rows[0]['jdkVersion']}, JMH {rows[0]['jmhVersion']}. Recorded {meta.get('started_at', 'see metadata')}.",
        "Three forks, ten 1-second warmup and five 1-second measurement iterations; one JMH caller; 2 GiB G1 client heap.",
        "Each batch completes 256 requests across 8 or 64 persistent connections, with one outstanding request per connection.",
        "The identical separate server JVM requests a 1 ms sleep before each response; actual time includes OS scheduling.", "",
        "Blocking uses java.net.Socket. Nonblocking uses shared AsynchronousSocketChannel code with two platform completion threads.",
        "Gears and Ox execute their direct-style waits on virtual threads in both modes. The mode names identify socket APIs, not carrier blocking.",
        "Root/worker creation, runtime entry, scoped lifetime management, callbacks and ordered result construction are measured.",
        "Every worker outcome is collected, all workers are joined, then the first lane-ordered failure is propagated.", "",
        "Batches/s includes JMH 99.9% confidence-interval half-widths. Ratios compare mean throughput against CE in the same group.",
        "p50/p99 are closed-loop batch latency from separate sample-time runs, not per-request latency or an external arrival-rate SLA.",
        "Allocation and CPU come from throughput runs. Client CPU includes runtime, GC and completion threads, excluding the separate server.",
        "Allocation is not retained heap, native stacks or peak RSS. The server still shares this host's CPU.", "",
        "[Methods and sources](../../docs/four-library-io.md). [Provenance](metadata.json), [run log](run.log).", ""]

    def table(transport, parallelism, runtimes):
        lines.extend(["| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | B/batch | Client CPU ms/batch |",
                      "| --- | ---: | --- | ---: | ---: | ---: | ---: |"])
        baseline = indexed[("thrpt", transport, parallelism, "ce")]["primaryMetric"]
        for runtime, label in runtimes.items():
            row = indexed[("thrpt", transport, parallelism, runtime)]
            value = row["primaryMetric"]
            latency = indexed[("sample", transport, parallelism, runtime)]["primaryMetric"]["scorePercentiles"]
            ratio = "baseline" if runtime == "ce" else f"{value['score']/baseline['score']:.2f}x"
            if runtime != "ce" and abs(value["score"]-baseline["score"]) <= value["scoreError"]+baseline["scoreError"]:
                ratio += " (CIs overlap)"
            resources = row["secondaryMetrics"]
            lines.append(f"| {label} | {value['score']:,.2f} ± {value['scoreError']:,.2f} | {ratio} | "
                         f"{latency['50.0']*1000:.3f} | {latency['99.0']*1000:.3f} | "
                         f"{resources['gc.alloc.rate.norm']['score']:,.0f} | {resources['client.cpu']['score']:.3f} |")
        lines.append("")

    for transport, parallelism in itertools.product(("blocking", "nonblocking"), ("8", "64")):
        lines.extend([f"## {transport.capitalize()}, concurrency {parallelism}: defaults", ""])
        table(transport, parallelism, DEFAULTS)
    lines.extend(["## Blocking, concurrency 64: explicit alternatives", ""])
    table("blocking", "64", {"ce": "CE default", **TUNED})
    lines.extend([
        "CE virtual lanes shifts each complete worker loop once to a virtual-thread executor, retaining the default compute runtime.",
        "Kyo queue flush calls Scheduler.get.flush() before every exchange. Fixed64 additionally sets coreWorkers, minWorkers, maxWorkers and scheduleStride to 64.",
        "These are disclosed alternatives, not an exhaustive search for each library's best tuning. Default-runtime rows remain separate.", "",
        "This suite measures successful TCP batches on one host. It does not establish general library speed or equivalent cancellation guarantees.",
        "Checks cover outputs, exactly-once execution, limits, worker overlap, thread placement, join-all failure handling, connection reuse, and callback-transport cancellation.",
        "Cross-library parent cancellation and resource-safety equivalence need separate tests. Socket providers, callback adapters and scoped task bookkeeping contribute to the results.", "",
        "Raw JMH files: [defaults](default.json), [alternatives](controls.json), [fixed Kyo](kyo-tuned.json).", ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    rows, meta = load_verified(args.directory)
    path = args.directory / "report.md"
    path.write_text(render(rows, meta))
    print(f"Wrote {path}: {len(rows)} configurations")


if __name__ == "__main__":
    main()
