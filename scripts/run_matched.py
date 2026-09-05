#!/usr/bin/env python3
"""Validate, measure the matched suite, and regenerate README.md."""
import argparse
import datetime
import hashlib
import json
import pathlib
import platform
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]


def metadata(command, started):
    sources = [ROOT / "build.sbt", ROOT / "project/plugins.sbt", ROOT / "project/build.properties",
               ROOT / "bench/src/main/scala/bench/BaseBench.scala"]
    sources += sorted((ROOT / "bench/src/main/scala/bench/matched").glob("*.scala"))
    sources += sorted((ROOT / "bench/src/test/scala/bench/matched").glob("*.scala"))
    report_tools = [ROOT / "scripts/run_matched.py", ROOT / "scripts/report_matched.py"]
    cp = ROOT / "bench/target/streams/compile/dependencyClasspath/_global/streams/export"
    artifacts = []
    if cp.exists():
        for name in cp.read_text().strip().split(":" if platform.system() != "Windows" else ";"):
            path = pathlib.Path(name)
            if path.is_file() and path.suffix == ".jar":
                artifacts.append({"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    machine = {"platform": platform.platform(), "architecture": platform.machine()}
    if platform.system() == "Darwin":
        for key in ("hw.model", "hw.ncpu", "hw.memsize"):
            result = subprocess.run(["sysctl", "-n", key], capture_output=True, text=True)
            if result.returncode == 0:
                machine[key] = result.stdout.strip()
    return {
        "started_at": started,
        "recorded_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "command": command,
        "machine": machine,
        "source_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        "report_tool_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in report_tools},
        "dependency_artifacts": artifacts,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=ROOT / "results/matched")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    result_file = output / "current.json"
    if result_file.exists():
        parser.error(f"{result_file} exists; use --output with a fresh directory to retain previous measurements")
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    # Each sbt command is an individual subprocess argument; no shell is involved.
    quoted_result = json.dumps(str(result_file))
    command = ["sbt", "bench/clean", "bench/Test/runMain bench.matched.Validation",
               "bench/Jmh/run -i 5 -wi 5 -f 3 -r 1s -w 1s -prof gc -rf json "
               f"-rff {quoted_result} .*bench.matched.*Bench.*"]
    before = metadata(command, started)
    print(f"Validation and benchmark output: {output / 'run.log'}", flush=True)
    with (output / "run.log").open("w") as log:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    after = metadata(command, started)
    after["validation"] = {"command": "bench/Test/runMain bench.matched.Validation", "checks": 984,
                           "passed": "PASS 984 checks" in (output / "run.log").read_text()}
    after["source_changed_during_run"] = (before["source_sha256"] != after["source_sha256"] or
                                         before["report_tool_sha256"] != after["report_tool_sha256"])
    (output / "metadata.json").write_text(json.dumps(after, indent=2) + "\n")
    if result.returncode:
        print("Validation or measurement failed; README.md was not updated.", file=sys.stderr)
        return result.returncode
    if after["source_changed_during_run"]:
        print("Sources changed during measurement; README.md was not updated.", file=sys.stderr)
        return 1
    return subprocess.call([sys.executable, str(ROOT / "scripts/report_matched.py"), str(result_file)], cwd=ROOT)


if __name__ == "__main__":
    sys.exit(main())
