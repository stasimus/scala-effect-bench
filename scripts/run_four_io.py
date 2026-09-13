#!/usr/bin/env python3
"""Validate and measure matched blocking/nonblocking TCP on four runtimes."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys

from report_four_io import FILES, TUNING, load_verified, render
from run_matched import ROOT, metadata

BASE = ["--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xms2g", "-Xmx2g", "-XX:+UseG1GC"]


def snapshot(command, started):
    result = metadata(command, started)
    paths = sorted((ROOT / "io-bench/src").rglob("*.scala"))
    paths += sorted((ROOT / "bench/src/main/scala/bench/blocking").glob("*.scala"))
    paths += [ROOT / "scripts/run_four_io.py", ROOT / "scripts/report_four_io.py"]
    result["source_sha256"].update({str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths})
    export = ROOT / "io-bench/target/streams/compile/dependencyClasspath/_global/streams/export"
    if export.exists():
        jars = [Path(p) for p in export.read_text().strip().split(":" ) if p.endswith(".jar")]
        result["dependency_artifacts"] = [dict(file=p.name, sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in jars]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "results/four-io")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error("Choose a fresh output directory")
    output.mkdir(parents=True)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    command = ["sbt", "set ioBench / Test / fork := true",
               "set ioBench / Test / javaOptions := Seq(" + ",".join(map(json.dumps, BASE)) + ")",
               "ioBench/Test/runMain bench.io.Validation",
               "set ioBench / Test / javaOptions := Seq(" + ",".join(map(json.dumps, BASE + sorted(TUNING))) + ")",
               "ioBench/Test/runMain bench.io.Validation kyoTuned",
               "set ioBench / Test / javaOptions := Seq(" + ",".join(map(json.dumps, BASE)) + ")"]
    common = "ioBench/Jmh/run -i 5 -wi 10 -f 3 -t 1 -r 1s -w 1s -foe true -prof gc -prof bench.io.ClientCpuProfiler -rf json "
    common += "-p size=256 -p delayMicros=1000 "
    for filename, params in (
        (FILES[0], "-p runtime=ce,kyo,gears,ox -p transport=blocking,nonblocking -p parallelism=8,64"),
        (FILES[1], "-p runtime=ceVirtual,kyoFlush -p transport=blocking -p parallelism=64"),
        (FILES[2], "-p runtime=kyoTuned -p transport=blocking -p parallelism=64 -jvmArgsAppend " + json.dumps(" ".join(sorted(TUNING))))
    ):
        command.append(common + f"-rff {json.dumps(str(output / filename))} {params} .*bench.io.IoBench.requests")
    before = snapshot(command, started)
    (output / "metadata.json").write_text(json.dumps(before, indent=2) + "\n")
    print(f"Validation and measurement: {output / 'run.log'}", flush=True)
    with (output / "run.log").open("w") as log:
        completed = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    after = snapshot(command, started)
    log = (output / "run.log").read_text()
    after["exit_code"] = completed.returncode
    after["source_changed_during_run"] = any(before[k] != after[k] for k in ("source_sha256", "report_tool_sha256"))
    after["validation"] = dict(default_checks=367, tuned_checks=38,
        passed="PASS 367 four-library I/O checks (ce,kyo,gears,ox,ceVirtual,kyoFlush)" in log and
               "PASS 38 four-library I/O checks (kyoTuned)" in log)
    after["result_sha256"] = {name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in FILES if (output / name).exists()}
    after["run_log_sha256"] = hashlib.sha256((output / "run.log").read_bytes()).hexdigest()
    (output / "metadata.json").write_text(json.dumps(after, indent=2) + "\n")
    if completed.returncode:
        return completed.returncode
    rows, meta = load_verified(output)
    (output / "report.md").write_text(render(rows, meta))
    print(f"Wrote {output / 'report.md'}: {len(rows)} configurations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
