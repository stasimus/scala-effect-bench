#!/usr/bin/env python3
"""Validate and measure blocking TCP separately from the published matched suite."""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys

from run_matched import ROOT, metadata


def snapshot(command, started):
    result = metadata(command, started)
    sources = sorted((ROOT / "bench/src/main/scala/bench/blocking").glob("*.scala"))
    sources += sorted((ROOT / "bench/src/test/scala/bench/blocking").glob("*.scala"))
    scripts = [ROOT / "scripts/run_blocking.py", ROOT / "scripts/report_blocking.py"]
    for field, paths in (("source_sha256", sources), ("report_tool_sha256", scripts)):
        result[field].update({str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "results/blocking")
    args = parser.parse_args()
    output = args.output.resolve()
    # Reserve a fresh directory; interrupted or failed runs must also be preserved.
    if output.exists():
        parser.error(f"{output} exists; choose a fresh directory")
    output.mkdir(parents=True)
    result_file = output / "current.json"
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    command = ["sbt", "set bench / Test / fork := true",
               "bench/Test/runMain bench.blocking.Validation",
               "bench/Jmh/run -i 5 -wi 5 -f 3 -r 1s -w 1s -foe true -prof gc -rf json "
               f"-rff {json.dumps(str(result_file))} .*bench.blocking.BlockingBench.requests"]
    before = snapshot(command, started)
    (output / "metadata.json").write_text(json.dumps(before, indent=2) + "\n")
    print(f"Validation and benchmark output: {output / 'run.log'}", flush=True)
    with (output / "run.log").open("w") as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    after = snapshot(command, started)
    after["validation"] = {"checks": 102, "passed": "PASS 102 blocking checks" in (output / "run.log").read_text()}
    after["source_changed_during_run"] = any(before[k] != after[k] for k in ("source_sha256", "report_tool_sha256"))
    after["exit_code"] = result.returncode
    after["result_sha256"] = hashlib.sha256(result_file.read_bytes()).hexdigest() if result_file.exists() else None
    (output / "metadata.json").write_text(json.dumps(after, indent=2) + "\n")
    if result.returncode or after["source_changed_during_run"] or not after["validation"]["passed"]:
        print("Validation, measurement, or provenance failed; no report generated.", file=sys.stderr)
        return result.returncode or 1
    return subprocess.call([sys.executable, str(ROOT / "scripts/report_blocking.py"), str(result_file)], cwd=ROOT)


if __name__ == "__main__":
    sys.exit(main())
