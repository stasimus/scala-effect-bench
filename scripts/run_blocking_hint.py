#!/usr/bin/env python3
"""Measure the 64-connection blocking-hint control in a fresh directory."""
import argparse
import datetime
import hashlib
import json
import math
import pathlib
import subprocess
import sys

from run_blocking import ROOT, snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "results/blocking-hint")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error("Choose a fresh output directory")
    output.mkdir(parents=True)
    data_file = output / "current.json"
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    command = ["sbt", "set bench / Test / fork := true", "bench/Test/runMain bench.blocking.BlockingHintValidation",
               "bench/Jmh/run -i 5 -wi 5 -f 3 -r 1s -w 1s -foe true -prof gc -rf json "
               f"-p parallelism=64 -p delayMicros=1000 -rff {json.dumps(str(data_file))} .*bench.blocking.BlockingHintBench.requests"]

    def provenance():
        meta = snapshot(command, started)
        meta["report_tool_sha256"]["scripts/run_blocking_hint.py"] = hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()
        return meta

    before = provenance()
    (output / "metadata.json").write_text(json.dumps(before, indent=2) + "\n")
    print(f"Validation and measurement: {output / 'run.log'}", flush=True)
    with (output / "run.log").open("w") as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    meta = provenance()
    meta["source_changed_during_run"] = any(before[k] != meta[k] for k in ("source_sha256", "report_tool_sha256"))
    meta["validation"] = {"checks": 48, "passed": "PASS 48 blocking hint checks" in (output / "run.log").read_text()}
    meta["exit_code"] = result.returncode
    meta["result_sha256"] = hashlib.sha256(data_file.read_bytes()).hexdigest() if data_file.exists() else None
    (output / "metadata.json").write_text(json.dumps(meta, indent=2) + "\n")
    if result.returncode or meta["source_changed_during_run"] or not meta["validation"]["passed"]:
        return result.returncode or 1
    rows = json.loads(data_file.read_bytes())
    indexed = {}
    for row in rows:
        p, m = row["params"], row["primaryMetric"]
        identity = (row["mode"], p["runtime"])
        assert row["benchmark"] == "bench.blocking.BlockingHintBench.requests"
        assert p == {"size": "256", "parallelism": "64", "delayMicros": "1000", "runtime": p["runtime"]}
        assert identity not in indexed
        assert (row["forks"], row["warmupIterations"], row["measurementIterations"], row["threads"]) == (3, 5, 5, 1)
        assert row["warmupTime"] == row["measurementTime"] == "1 s"
        raw = m.get("rawData", m.get("rawDataHistogram", []))
        assert len(raw) == 3 and all(len(fork) == 5 for fork in raw)
        assert m["scoreUnit"] == ("ops/s" if row["mode"] == "thrpt" else "s/op")
        assert m["score"] > 0 and math.isfinite(m["scoreError"])
        assert {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC"}.issubset(row["jvmArgs"])
        indexed[identity] = row
    assert set(indexed) == {(mode, runtime) for mode in ("thrpt", "sample") for runtime in ("ce", "kyoFlush")}
    assert len({(r["jvm"], r["jdkVersion"], r["jmhVersion"], tuple(r["jvmArgs"])) for r in rows}) == 1
    lines = ["# Blocking hint control", "",
             f"Recorded {started}. JDK {rows[0]['jdkVersion']}; 64 persistent connections; 256 requests/batch; 1 ms requested server delay.",
             "Three forks, five 1-second warmup and measurement iterations; fixed 2 GiB G1 client heap. 48 correctness checks passed.",
             "Same worker loops and TCP fixture. CE uses IO.blocking; Kyo flushes its local scheduler queue before each blocking call.",
             "Throughput includes JMH 99.9% confidence-interval half-widths. Latency is sampled batch latency from separate runs; short runs limit p99 precision.",
             "Allocation covers the client JVM, including background activity, and excludes server allocation and native memory.", "",
             "| Runtime | Batches/s | Batch p50 ms | Batch p99 ms | Client B/batch |",
             "| --- | ---: | ---: | ---: | ---: |"]
    for runtime, label in (("ce", "CE default"), ("kyoFlush", "Kyo with flush")):
        throughput = indexed[("thrpt", runtime)]
        metric = throughput["primaryMetric"]
        p = indexed[("sample", runtime)]["primaryMetric"]["scorePercentiles"]
        allocation = throughput["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]
        lines.append(f"| {label} | {metric['score']:.2f} ± {metric['scoreError']:.2f} | {p['50.0']*1000:.3f} | {p['99.0']*1000:.3f} | {allocation:,.0f} |")
    lines += ["", "This focused control covers one workload; it does not establish a general ranking.",
              "[Raw data](current.json), [log](run.log), [provenance](metadata.json).", ""]
    (output / "report.md").write_text("\n".join(lines))
    print(f"Wrote {output / 'report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
