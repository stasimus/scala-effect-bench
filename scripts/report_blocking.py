#!/usr/bin/env python3
"""Report complete blocking TCP runs without changing the published baseline."""
import argparse
import hashlib
import itertools
import json
import math
import pathlib

RUNTIMES = {"ce": "CE default", "ceVirtual": "CE virtual calls", "kyo": "Kyo default"}
EXPECTED = set(itertools.product(("thrpt", "sample"), ("0", "1000"), ("8", "64"), RUNTIMES))


def measurements(rows):
    indexed = {}
    configurations = set()
    for row in rows:
        p = row["params"]
        key = (row["mode"], p["delayMicros"], p["parallelism"], p["runtime"])
        if row["benchmark"] != "bench.blocking.BlockingBench.requests" or key not in EXPECTED:
            raise ValueError("Unknown benchmark or configuration")
        if p["size"] != "256" or set(p) != {"delayMicros", "parallelism", "runtime", "size"}:
            raise ValueError("Unexpected workload")
        if key in indexed:
            raise ValueError("Duplicate measurement")
        if (row["forks"], row["warmupIterations"], row["measurementIterations"], row["threads"]) != (3, 5, 5, 1):
            raise ValueError("Incomplete run settings")
        if row["warmupTime"] != "1 s" or row["measurementTime"] != "1 s":
            raise ValueError("Unexpected iteration duration")
        metric = row["primaryMetric"]
        unit = "ops/s" if row["mode"] == "thrpt" else "s/op"
        if metric["scoreUnit"] != unit or not math.isfinite(metric["score"]) or metric["score"] <= 0:
            raise ValueError("Invalid metric")
        if not math.isfinite(metric["scoreError"]) or metric["scoreError"] < 0:
            raise ValueError("Missing confidence interval")
        raw = metric.get("rawData", metric.get("rawDataHistogram", []))
        if len(raw) != 3 or any(len(fork) != 5 for fork in raw):
            raise ValueError("Incomplete fork measurements")
        allocation = row["secondaryMetrics"]["gc.alloc.rate.norm"]
        if allocation["scoreUnit"] != "B/op" or not math.isfinite(allocation["score"]) or allocation["score"] <= 0:
            raise ValueError("Invalid allocation profile")
        if row["mode"] == "sample":
            percentiles = metric["scorePercentiles"]
            if not 0 < percentiles["50.0"] <= percentiles["99.0"] < math.inf:
                raise ValueError("Invalid latency percentiles")
        if not {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC"}.issubset(row["jvmArgs"]):
            raise ValueError("Unexpected heap or collector")
        configurations.add((row["jvm"], row["jdkVersion"], row["jmhVersion"], tuple(row["jvmArgs"])))
        indexed[key] = row
    if set(indexed) != EXPECTED:
        raise ValueError(f"Expected 24 configurations, got {len(indexed)}")
    if len(configurations) != 1:
        raise ValueError("Mixed JVM configurations")
    return indexed


def validate_provenance(meta, data):
    if meta.get("source_changed_during_run", True) or meta.get("exit_code") != 0:
        raise ValueError("Unverified run provenance")
    if meta.get("validation") != {"checks": 102, "passed": True}:
        raise ValueError("Correctness validation missing")
    if meta.get("result_sha256") != hashlib.sha256(data).hexdigest():
        raise ValueError("Result hash mismatch")


def render(rows, meta):
    indexed = measurements(rows)
    machine = meta.get("machine", {})
    lines = [
        "# Blocking TCP results", "",
        "These default-runtime results omit Kyo's documented blocking hint. See the [blocking-hint control](../blocking-hint/report.md)",
        "and [investigation](../blocking-investigation/report.md) before interpreting the high-concurrency gap.", "",
        f"Recorded: {meta.get('started_at', 'see metadata')}. JDK {rows[0]['jdkVersion']}; JMH {rows[0]['jmhVersion']}.",
        f"Machine: {machine.get('platform', 'see metadata')}; {machine.get('hw.ncpu', 'see metadata')} CPU cores.", "",
        "Three forks; five 1-second warmup and five 1-second measurement iterations; one JMH caller; fixed 2 GiB G1 client heap.",
        "Each operation completes 256 requests. Throughput and latency use separate runs.",
        "Throughput includes JMH 99.9% confidence-interval half-widths. Ratios compare means against CE default; overlapping intervals are flagged.",
        "Latency percentiles describe complete batches in a closed-loop workload, including the runner. They are not individual request latency or an open-loop service SLA.",
        "B/batch is client JVM allocation from the throughput run, including runtime activity. It is not retained heap, native stack memory, or peak process memory.", "",
        "The local TCP server runs in a separate JVM with identical settings for every case; its allocation is excluded, but its CPU shares this machine.",
        "Persistent connections, one outstanding request per connection, identical static worker lanes. Setup and connection establishment are excluded.",
        "The server sleeps for the configured delay before replying; actual delay includes OS timer and scheduling overhead.", "",
        "[Method and reproduction](../../docs/blocking-io.md). [Raw JSON](current.json), [run log](run.log), [provenance](metadata.json).", "",
    ]
    for delay, limit in itertools.product(("0", "1000"), ("8", "64")):
        lines += [f"## Server delay {delay} us, concurrency {limit}", "",
                  "| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | Client B/batch |",
                  "| --- | ---: | --- | ---: | ---: | ---: |"]
        base = indexed[("thrpt", delay, limit, "ce")]["primaryMetric"]
        for runtime, label in RUNTIMES.items():
            throughput = indexed[("thrpt", delay, limit, runtime)]
            metric = throughput["primaryMetric"]
            percentiles = indexed[("sample", delay, limit, runtime)]["primaryMetric"]["scorePercentiles"]
            ratio = "baseline" if runtime == "ce" else f"{metric['score'] / base['score']:.2f}x"
            if runtime != "ce" and abs(metric["score"] - base["score"]) <= metric["scoreError"] + base["scoreError"]:
                ratio += " (CIs overlap)"
            allocation = throughput["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]
            lines.append(f"| {label} | {metric['score']:,.2f} ± {metric['scoreError']:,.2f} | {ratio} | "
                         f"{percentiles['50.0'] * 1000:.3f} | {percentiles['99.0'] * 1000:.3f} | {allocation:,.0f} |")
        lines.append("")
    lines += ["CE virtual calls uses IO.blocking(...).evalOn a virtual-thread executor for each request, retaining CE's default compute runtime.",
              "Kyo uses Sync.defer on its default scheduler. This comparison does not replace CE's compute pool or enable Kyo's optional virtual workers.",
              "These measurements cover successful blocking TCP exchanges on one machine. They do not establish a general library ranking or test cancellation/resource safety.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=pathlib.Path)
    args = parser.parse_args()
    data = args.input.read_bytes()
    meta = json.loads((args.input.parent / "metadata.json").read_text())
    validate_provenance(meta, data)
    output = args.input.parent / "report.md"
    output.write_text(render(json.loads(data), meta))
    print(f"Wrote {output}: 24 measurements")


if __name__ == "__main__":
    main()
