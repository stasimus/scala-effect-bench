#!/usr/bin/env python3
"""Validate and measure all applicable CE/Kyo/Loom/Ox/Gears workloads."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys

from report_five_way import BASE_ARGS, FILES, PROFILES, RUNTIMES, VALIDATION_MARKERS, load_verified, render
from run_four_io import snapshot as io_snapshot
from run_matched import ROOT


def snapshot(command, started):
    meta = io_snapshot(command, started)
    for path in (ROOT / "scripts/run_five_way.py", ROOT / "scripts/report_five_way.py", ROOT / "docs/five-way.md"):
        meta["source_sha256"][str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return meta


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "results/five-way")
    parser.add_argument("--profile", choices=PROFILES, default="measured")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error("Choose a fresh output directory")
    output.mkdir(parents=True)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    settings = PROFILES[args.profile]
    runtimes = ",".join(RUNTIMES)
    command = ["sbt", "set bench / Test / fork := true",
               "set bench / Test / javaOptions := Seq(" + ",".join(map(json.dumps, sorted(BASE_ARGS))) + ")",
               "bench/Test/runMain bench.matched.Validation",
               "set ioBench / Test / fork := true",
               "set ioBench / Test / javaOptions := Seq(" + ",".join(map(json.dumps, sorted(BASE_ARGS))) + ")",
               "ioBench/Test/runMain bench.direct.Validation",
               "ioBench/Test/runMain bench.direct.FairnessValidation",
               "ioBench/Test/runMain bench.io.Validation " + " ".join(RUNTIMES)]
    common = (f"ioBench/Jmh/run -f {settings['forks']} -wi {settings['warmupIterations']} -i {settings['measurementIterations']} "
              f"-w {settings['cli_time']} -r {settings['cli_time']} -t 1 -foe true -prof gc "
              f"-prof bench.io.ClientCpuProfiler -rf json -p runtime={runtimes} ")
    command.append(common + f"-bm thrpt -rff {json.dumps(str(output / FILES[0]))} "
                   "bench.direct.(ParallelBench|PrimitivesBench|PipelineBench|RunnerBench|SequentialBaseline).*")
    command.append(common + f"-rff {json.dumps(str(output / FILES[1]))} bench.io.IoBench.requests")
    before = snapshot(command, started)
    before["profile"] = args.profile
    (output / "metadata.json").write_text(json.dumps(before, indent=2) + "\n")
    print(f"Validation and measurement log: {output / 'run.log'}", flush=True)
    with (output / "run.log").open("w") as log:
        completed = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    after = snapshot(command, started)
    log_bytes = (output / "run.log").read_bytes()
    log_text = log_bytes.decode(errors="replace")
    after.update(profile=args.profile, exit_code=completed.returncode,
        source_changed_during_run=any(before[k] != after[k] for k in ("source_sha256", "report_tool_sha256")),
        validation=dict(markers=VALIDATION_MARKERS, passed=all(marker in log_text for marker in VALIDATION_MARKERS)),
        result_sha256={name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in FILES if (output / name).exists()},
        run_log_sha256=hashlib.sha256(log_bytes).hexdigest())
    (output / "metadata.json").write_text(json.dumps(after, indent=2) + "\n")
    if completed.returncode:
        print("Validation or measurement failed; see run.log", file=sys.stderr)
        return completed.returncode
    rows, meta = load_verified(output)
    (output / "report.md").write_text(render(rows, meta))
    print(f"Wrote {output / 'report.md'}: {len(rows)} configurations", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
