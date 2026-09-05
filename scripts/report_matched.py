#!/usr/bin/env python3
"""Build the current report from complete matched JMH results, preserving provenance."""
import argparse
import json
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
PAIRS = [
    ("RunnerBench", "Runner overhead", "ceRunner", "kyoRunner"),
    ("CoreBench", "Deep suspended bind", "ceDeepBind", "kyoDeepBind"),
    ("CoreBench", "Left-associated suspended bind", "ceLeftBind", "kyoLeftBind"),
    ("CoreBench", "Map chain from suspended zero", "ceMapChain", "kyoMapChain"),
    ("ParallelBench", "Bounded workers", "ceWorkers", "kyoWorkers"),
    ("ParallelBench", "Parallel attempt + collect successes", "ceCollectSuccesses", "kyoCollectSuccesses"),
    ("PrimitivesBench", "CAS reference updates", "ceRef", "kyoRef"),
    ("PrimitivesBench", "Complete then read promise", "ceDeferred", "kyoPromise"),
    ("PrimitivesBench", "Queue: one producer / consumer", "ceQueue", "kyoQueue"),
    ("PrimitivesBench", "Uncontended non-reentrant permit", "ceSemaphore", "kyoSemaphore"),
    ("PrimitivesBench", "Sequential child spawn / join", "ceSpawnJoin", "kyoSpawnJoin"),
    ("StreamBench", "Sequential chunk transformation", "ceEvalChunks", "kyoEvalChunks"),
    ("StreamBench", "Parallel chunk transformation", "ceParallelChunks", "kyoParallelChunks"),
    ("StreamBench", "Queue-backed chunk stream", "ceQueueChunks", "kyoQueueChunks"),
]
PARAMS = {
    "RunnerBench": [{}],
    "CoreBench": [{"depth": str(n)} for n in (1000, 10000)],
    "ParallelBench": [{"size": "4096", "parallelism": "8", "work": str(w)} for w in (0, 64)],
    "PrimitivesBench": [{"ops": "1000", "capacity": "64"}],
    "StreamBench": [{"size": "10000", "chunkSize": "64", "parallelism": "4", "capacity": "64", "work": str(w)}
                    for w in (0, 64)],
}


def key(row):
    return tuple(sorted(row.get("params", {}).items()))


def pairs(rows):
    indexed = {}
    for row in rows:
        name = row["benchmark"]
        if not name.startswith("bench.matched."):
            raise ValueError(f"Unexpected historical/unmatched benchmark: {name}")
        cls = name.split(".")[-2]
        if cls not in PARAMS or row.get("params", {}) not in PARAMS[cls]:
            raise ValueError(f"Unexpected parameters: {name}")
        identity = (name, key(row))
        if identity in indexed:
            raise ValueError(f"Duplicate benchmark: {identity}")
        metric = row["primaryMetric"]
        if metric["scoreUnit"] != "ops/s" or not math.isfinite(metric["score"]) or metric["score"] <= 0:
            raise ValueError(f"Invalid throughput: {name}")
        raw = metric["rawData"]
        if row["forks"] != 3 or len(raw) != 3 or any(len(fork) != 5 for fork in raw):
            raise ValueError(f"Incomplete measurements: {name}")
        if row["warmupIterations"] != 5 or row["measurementIterations"] != 5 or row["threads"] != 1:
            raise ValueError(f"Unexpected run settings: {name}")
        if row["warmupTime"] != "1 s" or row["measurementTime"] != "1 s":
            raise ValueError(f"Unexpected iteration duration: {name}")
        if not math.isfinite(metric["scoreError"]):
            raise ValueError(f"Missing confidence interval: {name}")
        if "gc.alloc.rate.norm" not in row["secondaryMetrics"]:
            raise ValueError(f"Missing allocation profile: {name}")
        if not {"-Xms2g", "-Xmx2g", "-XX:+UseG1GC"}.issubset(row["jvmArgs"]):
            raise ValueError(f"Unexpected heap or collector: {name}")
        indexed[identity] = row
    configurations = {(r["jvm"], r["jdkVersion"], r["jmhVersion"], tuple(r["jvmArgs"])) for r in rows}
    if len(configurations) != 1:
        raise ValueError("Mixed JVM/run configurations")
    result = []
    consumed = set()
    for cls, label, ce, ky in PAIRS:
        left = f"bench.matched.{cls}.{ce}"
        right = f"bench.matched.{cls}.{ky}"
        params = sorted({params for name, params in indexed if name == left})
        if not params:
            raise ValueError(f"Missing pair: {label}")
        for param in params:
            a, b = indexed[(left, param)], indexed[(right, param)]
            consumed.update(((left, param), (right, param)))
            result.append((cls, label, param, a, b))
    if consumed != set(indexed):
        raise ValueError("Unpaired or unknown benchmark results")
    if len(result) != 22:
        raise ValueError(f"Expected 22 pair/parameter combinations, got {len(result)}")
    return result


def score(row):
    metric = row["primaryMetric"]
    return f"{metric['score']:,.2f} ± {metric['scoreError']:,.2f}"


def render(rows, result_file, meta):
    grouped = pairs(rows)
    first = rows[0]
    relative = result_file.relative_to(ROOT).as_posix()
    machine = meta.get("machine", {})
    lines = [
        "# Scala effect benchmarks", "",
        "JMH benchmarks comparing equivalent cats-effect and Kyo constructions. Only benchmark code is adjusted; libraries remain unchanged.", "",
        "cats-effect **3.7.1**, Kyo **1.0.0-RC6**, fs2 **3.13.0**, cats-core **2.13.0**, Scala **3.8.4**.", "",
        "These pairs use the same application-level construction. The streaming rows compare fs2 on IO",
        "with Kyo Stream. Native API diagnostics and withdrawn cancellation comparisons are excluded.",
        "",
        "## Measurement", "",
        f"Recorded: {meta.get('started_at', 'see metadata')}. Platform: {machine.get('platform', 'see metadata')}.",
        f"Machine: {machine.get('hw.model', 'see metadata')}; {machine.get('hw.ncpu', 'see metadata')} CPU cores; "
        f"{int(machine['hw.memsize']) / 2**30:g} GiB RAM." if 'hw.memsize' in machine else "Machine details: see metadata.",
        f"JDK {first['jdkVersion']}; JMH {first['jmhVersion']}; G1; fixed 2 GB heap; library scheduler/tracing defaults.", "",
        "Three independent JVM forks per case; five 1-second warmup and five 1-second measurement iterations",
        "per fork; one JMH caller thread. Each operation processes the entire configured workload.",
        "Throughput is ops/s (higher is better), with JMH's reported 99.9% confidence-interval half-width.",
        "The ratio is the ratio of means, rounded; it is not a confidence interval for the speedup.",
        "Rows whose throughput confidence intervals overlap are flagged; their mean ratios do not establish a winner.",
        "Short iterations and one machine limit generalization. Runner overhead is included, never subtracted.", "",
        f"[Raw JSON]({relative}), [run log]({pathlib.PurePosixPath(relative).parent}/run.log), "
        f"[machine, command, source and dependency hashes]({pathlib.PurePosixPath(relative).parent}/metadata.json).", "",
        "Validation passed 984 checks before measurement: outputs/order, exact-once execution, worker concurrency,",
        "batch boundaries, empty/all-failed inputs, partial batches, queue backpressure, and returned permits.", "",
    ]
    titles = {"RunnerBench": "Runner baseline", "CoreBench": "Core chains", "ParallelBench": "Parallel constructions",
              "PrimitivesBench": "Primitives", "StreamBench": "Streaming constructions"}
    previous = None
    for index, (cls, label, params, a, b) in enumerate(grouped):
        if cls != previous:
            lines += [f"## {titles[cls]}", ""]
            if cls == "ParallelBench":
                lines += ["`size=4096`, `parallelism=8`. Bounded traversal uses the same benchmark-local worker",
                          "strategy on both sides. Success collection starts one child per element and joins all children;",
                          "four fail, and 4,092 successes are returned in order. This is not `Async.gather`.", ""]
            elif cls == "PrimitivesBench":
                lines += ["`ops=1000`; queue capacity 64 integers. Queue and spawn/join use one producer/child at a time.", ""]
            elif cls == "StreamBench":
                lines += ["`size=10000`, `chunkSize=64`, `parallelism=4`, queue capacity 64 chunks.",
                          "Both parallel streams complete and emit a whole batch before starting the next.",
                          "These are matched constructions, not the native `parEvalMap / mapPar` implementations.", ""]
            ce_label = "fs2" if cls == "StreamBench" else "CE"
            lines += [f"| Construction | Parameters | {ce_label} ops/s | Kyo ops/s | Ratio of means | {ce_label} B/op | Kyo B/op |",
                      "| --- | --- | ---: | ---: | --- | ---: | ---: |"]
            previous = cls
        p = dict(params)
        detail = ", ".join(f"{name}={p[name]}" for name in ("depth", "work") if name in p) or "—"
        av, bv = a["primaryMetric"]["score"], b["primaryMetric"]["score"]
        ce_label = "fs2" if cls == "StreamBench" else "CE"
        ratio = f"{ce_label if av > bv else 'Kyo'} ~{max(av,bv)/min(av,bv):.2f}×"
        if abs(av - bv) <= a["primaryMetric"]["scoreError"] + b["primaryMetric"]["scoreError"]:
            ratio += " (CIs overlap)"
        alloc_a = a["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]
        alloc_b = b["secondaryMetrics"]["gc.alloc.rate.norm"]["score"]
        lines.append(f"| {label} | {detail} | {score(a)} | {score(b)} | {ratio} | {alloc_a:,.0f} | {alloc_b:,.0f} |")
        # Blank lines are emitted only between tables, never inside a table.
        next_index = index + 1
        if next_index == len(grouped) or grouped[next_index][0] != cls:
            lines.append("")
    lines += ["`work=0` is an increment; `work=64` adds 64 rounds of identical integer arithmetic.",
              "B/op means total allocated bytes per complete operation, not retained memory or peak heap.", "",
              "## Scope", "",
              "These successful-work microbenchmarks do not measure cancellation, resource safety, real I/O,",
              "tail latency, ecosystem, or application performance. Library implementation costs remain part",
              "of the measurements. Historical native-API results are preserved in",
              "[the archived report](results/archive/result-before-matched.md), not mixed into this table.", "",
              "Reproduce with `python3 scripts/run_matched.py --output results/matched-new`.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "README.md")
    args = parser.parse_args()
    result_file = args.input.resolve()
    meta = json.loads((result_file.parent / "metadata.json").read_text())
    if meta.get("source_changed_during_run", True):
        raise ValueError("Source provenance was not verified")
    if not meta.get("validation", {}).get("passed") or meta["validation"].get("checks") != 984:
        raise ValueError("Correctness validation was not recorded")
    rows = json.loads(result_file.read_text())
    report = render(rows, result_file, meta)
    args.output.write_text(report)
    print(f"Wrote {args.output}: {len(rows)} measurements, {len(pairs(rows))} matched pairs/configurations")


if __name__ == "__main__":
    main()
