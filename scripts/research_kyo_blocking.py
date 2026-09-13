#!/usr/bin/env python3
"""Run separate-JVM diagnostic controls; these are not JMH scores."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = ["--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xms2g", "-Xmx2g", "-XX:+UseG1GC"]


def case(label, runtime="kyo", props=None, size=256, delay=1000, warmup=6, samples=10):
    return dict(label=label, runtime=runtime, props=props or {}, size=size, parallelism=64,
                delay=delay, warmup=warmup, samples=samples)


def fixed(n):
    return {f"kyo.scheduler.{key}": n for key in ("coreWorkers", "minWorkers", "maxWorkers")}


CASES = [
    case("ce", "ce"), case("plain"), case("flush", "kyoFlush"),
    case("flush16", "kyoFlush", fixed(16)), case("flush64", "kyoFlush", fixed(64)),
    case("plain64", props=fixed(64)),
    case("admission1", props={"kyo.scheduler.regulator.admissionCollectIntervalMs": 1}),
    case("admission1000", props={"kyo.scheduler.regulator.admissionCollectIntervalMs": 1000}),
    case("slice1", props={"kyo.scheduler.timeSliceMs": 1}),
    case("flushWarm30", "kyoFlush", warmup=30),
    case("flushVirtual", "kyoFlush", {"kyo.scheduler.virtualizeWorkers": "true"}),
    case("plainVirtual", props={"kyo.scheduler.virtualizeWorkers": "true"}),
    case("longLane", size=4096, samples=3), case("longWait", delay=50000, samples=3),
    case("ceZero", "ce", delay=0), case("flush64Zero", "kyoFlush", fixed(64), delay=0),
    case("flush64Stride64", "kyoFlush", fixed(64) | {"kyo.scheduler.scheduleStride": 64}),
    case("flush128", "kyoFlush", fixed(128)),
]


def hashes():
    files = [ROOT / "build.sbt", ROOT / "bench/src/main/scala/bench/BaseBench.scala",
             ROOT / "bench/src/main/scala/bench/matched/MatchedBase.scala", Path(__file__)]
    files += sorted((ROOT / "bench/src/main/scala/bench/blocking").glob("*.scala"))
    files += [ROOT / "bench/src/test/scala/bench/blocking/SchedulerResearchProbe.scala"]
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "results/kyo-blocking-research/controls")
    parser.add_argument("--cases", nargs="+", choices=[c["label"] for c in CASES])
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()
    if args.repeat < 1:
        parser.error("Repeat must be positive")
    output = args.output.resolve()
    if output.exists():
        parser.error("Choose a fresh output directory")
    output.mkdir(parents=True)
    cases = [c for c in CASES if not args.cases or c["label"] in args.cases]
    if args.repeat > 1:
        cases = [dict(c, label=f"{c['label']}Run{i + 1}") for i in range(args.repeat) for c in cases]
    command = ["sbt", "set bench / Test / fork := true"]
    for c in cases:
        flags = BASE + [f"-D{k}={v}" for k, v in c["props"].items()]
        command += ["set bench / Test / javaOptions := Seq(" + ",".join(map(json.dumps, flags)) + ")",
                    "bench/Test/runMain bench.blocking.SchedulerResearchProbe " + " ".join(str(c[k]) for k in
                     ("runtime", "size", "parallelism", "delay", "warmup", "samples", "label"))]
    meta = {"started_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "command": command, "cases": cases, "source_sha256": hashes(), "kind": "instrumented diagnostic, not JMH"}
    (output / "metadata.json").write_text(json.dumps(meta, indent=2) + "\n")
    print(f"Diagnostics: {output / 'run.log'}", flush=True)
    with (output / "run.log").open("w") as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    meta["exit_code"] = result.returncode
    meta["source_changed_during_run"] = meta["source_sha256"] != hashes()
    meta["completed_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    meta["run_log_sha256"] = hashlib.sha256((output / "run.log").read_bytes()).hexdigest()
    (output / "metadata.json").write_text(json.dumps(meta, indent=2) + "\n")
    if result.returncode or meta["source_changed_during_run"]:
        return result.returncode or 1
    records = []
    for line in (output / "run.log").read_text().splitlines():
        match = re.search(r"(SAMPLE|STATE|MONITOR|RSS_AFTER_WARMUP_KIB|LANE_STARTS_NS) (.*)", line)
        if match:
            row = dict(token.split("=", 1) for token in match[2].split())
            row["kind"] = match[1]
            records.append(row)
    for c in cases:
        samples = [r for r in records if r["kind"] == "SAMPLE" and r["case"] == c["label"]]
        assert len(samples) == c["samples"], c["label"]
        assert all(0 < int(r["peak"]) <= c["parallelism"] for r in samples)
        if c["props"].get("kyo.scheduler.virtualizeWorkers") == "true":
            assert all(int(r["virtualCalls"]) == c["size"] for r in samples), "Virtual worker fallback"
    (output / "observations.json").write_text(json.dumps(records, indent=2) + "\n")
    print(f"Recorded {len(records)} diagnostic observations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
