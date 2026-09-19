#!/usr/bin/env python3
"""Measure CE/Loom process footprint and occupied heap in fresh macOS JVMs."""
import argparse
import ctypes
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import queue
import re
import signal
import subprocess
import sys
import threading
import time

from run_four_io import snapshot as io_snapshot
from run_matched import ROOT

JVM_ARGS = ["--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xms64m", "-Xmx2g", "-XX:+UseG1GC"]
MARKER = "PASS 16 memory workload checks"


class RusageV4(ctypes.Structure):
    # Darwin SDK sys/resource.h, struct rusage_info_v4. All sizes are bytes.
    _fields_ = [("uuid", ctypes.c_uint8 * 16)] + [(name, ctypes.c_uint64) for name in (
        "user_time system_time pkg_idle_wkups interrupt_wkups pageins wired_size resident_size "
        "phys_footprint proc_start_abstime proc_exit_abstime child_user_time child_system_time "
        "child_pkg_idle_wkups child_interrupt_wkups child_pageins child_elapsed_abstime "
        "diskio_bytesread diskio_byteswritten cpu_time_qos_default cpu_time_qos_maintenance "
        "cpu_time_qos_background cpu_time_qos_utility cpu_time_qos_legacy cpu_time_qos_user_initiated "
        "cpu_time_qos_user_interactive billed_system_time serviced_system_time logical_writes "
        "lifetime_max_phys_footprint instructions cycles billed_energy serviced_energy "
        "interval_max_phys_footprint runnable_time").split()]


class MacMemory:
    def __init__(self):
        if platform.system() != "Darwin":
            raise RuntimeError("This runner uses macOS proc_pid_rusage; it does not silently substitute other metrics")
        self.lib = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
        self.lib.proc_pid_rusage.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_void_p]
        self.lib.proc_pid_rusage.restype = ctypes.c_int

    def read(self, pid):
        usage = RusageV4()
        if self.lib.proc_pid_rusage(pid, 4, ctypes.byref(usage)):
            raise OSError(ctypes.get_errno(), f"Cannot read process memory for PID {pid}")
        result = dict(rss=usage.resident_size, footprint=usage.phys_footprint,
                      peak_footprint=usage.lifetime_max_phys_footprint)
        # These kernel counters are not a single atomic reading: current footprint
        # can briefly be one page ahead of the reported lifetime high-water mark.
        if min(result.values()) <= 0:
            raise ValueError(f"Invalid process memory metrics: {result}")
        return result


def cases(profile):
    tasks = (1000, 10000, 100000) if profile == "measured" else (8,)
    connections = (8, 64) if profile == "measured" else (8,)
    return [("parked", n, str(payload)) for n in tasks for payload in (0, 1024)] + [
        ("tcp", n, transport) for n in connections for transport in ("blocking", "nonblocking")]


def snapshot(command, started):
    meta = io_snapshot(command, started)
    paths = list((ROOT / "io-bench/src/main/scala/bench/memory").glob("*.scala"))
    paths += list((ROOT / "io-bench/src/test/scala/bench/memory").glob("*.scala"))
    paths += [ROOT / "scripts/run_memory.py", ROOT / "scripts/report_memory.py", ROOT / "docs/memory.md"]
    meta["source_sha256"].update({str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths})
    return meta


def probe(java, classpath, output, runtime, mode, count, setting, fork, profile, sampler):
    case_id = f"{mode}-{count}-{setting}-{runtime}-{fork}"
    stderr_path = output / f"{case_id}.stderr.log"
    gc_path = output / f"{case_id}.gc.log"
    command = [java, *JVM_ARGS, f"-Xlog:gc=info:file={gc_path}", "-cp", classpath,
               "bench.memory.MemoryProbe", runtime, mode, str(count), setting]
    events, samples = [], []
    result = dict(id=case_id, runtime=runtime, mode=mode, count=count, setting=setting, fork=fork,
                  jvm_args=JVM_ARGS, command=command, events=events, samples=samples)
    with stderr_path.open("w") as stderr:
        process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=stderr, text=True, bufsize=1, start_new_session=True)
        lines = queue.Queue()

        def read_lines():
            try:
                for line in process.stdout:
                    lines.put(line)
            finally:
                lines.put(None)

        threading.Thread(target=read_lines, daemon=True).start()

        def expect(stage):
            deadline = time.monotonic() + 120
            while True:
                line = lines.get(timeout=max(.01, deadline - time.monotonic()))
                if line is None:
                    raise RuntimeError(f"{case_id} ended before {stage}; see {stderr_path}")
                if line.startswith("MEMORY "):
                    event = json.loads(line[len("MEMORY "):])
                    events.append(event)
                    if event["stage"] != stage:
                        raise ValueError(f"Expected {stage}; got {event}")
                    return event

        def send(command):
            process.stdin.write(command + "\n")
            process.stdin.flush()

        def sample(stage, n):
            for _ in range(n):
                samples.append(dict(stage=stage, monotonic=time.monotonic(), **sampler.read(process.pid)))
                time.sleep(.1)

        try:
            hello = expect("hello")
            if hello["pid"] != process.pid:
                raise ValueError("Memory sampler must observe the client JVM itself")
            if hello["input_args"] != [*JVM_ARGS, f"-Xlog:gc=info:file={gc_path}"] or hello["jdk"] != "25.0.3":
                raise ValueError("Use JDK 25.0.3 with the matched JVM settings")
            expect("baseline")
            sample("baseline", 5)
            send("START")
            if mode == "parked":
                expect("held")
                sample("held", 10)
                send("GC")
                expect("live")
                sample("live", 5)
                send("RELEASE")
                expect("released")
                sample("released", 5)
            else:
                expect("active")
                sample("active", 50 if profile == "measured" else 10)
                send("STOP")
                expect("idle")
                sample("idle", 5)
            # Kernel lifetime peak read before exit; includes startup, warmup and measurement.
            result["final_process_memory"] = sampler.read(process.pid)
            observed = [*samples, result["final_process_memory"]]
            result["peak_footprint_before_exit"] = max(max(s["footprint"], s["peak_footprint"]) for s in observed)
            send("EXIT")
            expect("complete")
            process.wait(timeout=30)
            if process.returncode:
                raise RuntimeError(f"Probe failed with exit {process.returncode}: {case_id}")
            result["exit_code"] = process.returncode
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            process.stdin.close()
            process.stdout.close()
    result["gc_log_sha256"] = hashlib.sha256(gc_path.read_bytes()).hexdigest()
    result["stderr_sha256"] = hashlib.sha256(stderr_path.read_bytes()).hexdigest()
    (output / f"{case_id}.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", choices=("smoke", "measured"), default="measured")
    args = parser.parse_args()
    if any(os.environ.get(name) for name in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")):
        parser.error("Unset injected JVM option variables for a controlled memory comparison")
    output = args.output.resolve()
    if output.exists():
        parser.error("Use a fresh output directory")
    sampler = MacMemory()
    sampler.read(os.getpid())
    output.mkdir(parents=True)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    options = "Seq(" + ",".join(map(json.dumps, JVM_ARGS)) + ")"
    command = ["sbt", "ioBench/Test/compile", "set ioBench / Test / fork := true",
               "set ioBench / Test / javaOptions := " + options,
               "ioBench/Test/runMain bench.memory.Validation", "show ioBench / Compile / fullClasspath"]
    before = snapshot(command, started)
    before.update(profile=args.profile, complete=False)
    (output / "metadata.json").write_text(json.dumps(before, indent=2) + "\n")
    with (output / "validation.log").open("w") as log:
        subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    log_text = (output / "validation.log").read_text()
    if MARKER not in log_text:
        raise ValueError("Memory workload validation did not pass")
    cp = re.findall(r"\* Attributed\((.+)\)", log_text)
    if not cp or not all(Path(p).exists() for p in cp):
        raise ValueError("Cannot resolve the compiled classpath")
    classpath = os.pathsep.join(cp)
    java = str(Path(os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/default/Contents/Home")) / "bin/java")
    if not Path(java).exists():
        java = "/usr/bin/java"
    version = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True)
    rows = []
    forks = 3 if args.profile == "measured" else 1
    total = forks * 2 * len(cases(args.profile))
    for fork in range(forks):
        for mode, count, setting in cases(args.profile):
            for runtime in (("ce", "loom") if fork % 2 == 0 else ("loom", "ce")):
                print(f"{len(rows)+1}/{total}: {runtime} {mode} count={count} setting={setting} fork={fork+1}", flush=True)
                rows.append(probe(java, classpath, output, runtime, mode, count, setting, fork, args.profile, sampler))
    after = snapshot(command, started)
    raw = json.dumps(rows, indent=2) + "\n"
    (output / "memory.json").write_text(raw)
    after.update(profile=args.profile, forks=forks, complete=True, java_version=version,
                 finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                 source_changed_during_run=any(before[k] != after[k] for k in ("source_sha256", "report_tool_sha256")),
                 validation=dict(marker=MARKER, passed=True),
                 validation_log_sha256=hashlib.sha256((output / "validation.log").read_bytes()).hexdigest(),
                 result_sha256=hashlib.sha256(raw.encode()).hexdigest())
    (output / "metadata.json").write_text(json.dumps(after, indent=2) + "\n")
    from report_memory import load_verified, render
    verified, meta = load_verified(output)
    (output / "report.md").write_text(render(verified, meta))
    print(f"Wrote {output / 'report.md'}: {len(rows)} fresh JVM measurements", flush=True)


if __name__ == "__main__":
    main()
