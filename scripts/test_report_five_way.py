import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from report_five_way import BASE_ARGS, EXPECTED, FILES, PROFILES, VALIDATION_MARKERS, load_verified, render, verify


def complete_results(profile="measured"):
    settings = PROFILES[profile]
    rows = []
    for benchmark, mode, params in EXPECTED:
        score = 100.0 if mode == "thrpt" else 0.01
        raw = [[score] * settings["measurementIterations"] for _ in range(settings["forks"])]
        metric = dict(score=score, scoreError=score * 0.1, scoreUnit="ops/s" if mode == "thrpt" else "s/op")
        if mode == "sample":
            metric.update(rawDataHistogram=[[[[v, 100]] for v in fork] for fork in raw],
                          scorePercentiles={"50.0": 0.01, "99.0": 0.02})
        else:
            metric["rawData"] = raw
        rows.append(dict(benchmark=benchmark, mode=mode, params=dict(params),
            forks=settings["forks"], warmupIterations=settings["warmupIterations"],
            measurementIterations=settings["measurementIterations"], warmupTime=settings["time"],
            measurementTime=settings["time"], threads=1, jvm="/example/jdk/bin/java", jdkVersion="25.0.3",
            jmhVersion="1.37", jvmArgs=sorted(BASE_ARGS), primaryMetric=metric,
            secondaryMetrics={"gc.alloc.rate.norm": dict(score=1000.0, scoreUnit="B/op"),
                              "client.cpu": dict(score=0.5, scoreUnit="ms/batch")}))
    return rows


class FiveWayReportValidation(unittest.TestCase):
    def test_complete_report_labels_and_units(self):
        rows = complete_results()
        self.assertEqual(len(verify(rows, "measured")), 130)
        report = render(rows, dict(profile="measured", started_at="test"))
        self.assertEqual(report.count("| Loom / JDK |"), 22)
        self.assertIn("0.5000 | 10.000 | 20.000", report)
        self.assertIn("not an equivalent bind-chain implementation", report)
        self.assertIn("CIs overlap", report)
        self.assertIn("JDK promises, permits and atomics", report)
        for section in report.split("## ")[1:]:
            if section.startswith("Sequential arithmetic"):
                self.assertEqual(section.count("| n/a |"), 5)

    def test_missing_duplicate_or_unexpected_workloads(self):
        rows = complete_results()
        variants = [rows[:-1], rows + [rows[0]]]
        unknown = copy.deepcopy(rows)
        unknown[0]["params"]["runtime"] = "unknown"
        variants.append(unknown)
        extra_param = copy.deepcopy(rows)
        extra_param[0]["params"]["extra"] = "1"
        variants.append(extra_param)
        for variant in variants:
            with self.assertRaises(ValueError):
                verify(variant, "measured")

    def test_rejects_mixed_or_short_runs(self):
        for field, value in (("jdkVersion", "21"), ("threads", 2), ("forks", 1),
                             ("warmupIterations", 1), ("measurementIterations", 1), ("measurementTime", "100 ms")):
            rows = complete_results()
            rows[0][field] = value
            with self.assertRaises(ValueError):
                verify(rows, "measured")
        for mode, raw_key in (("thrpt", "rawData"), ("sample", "rawDataHistogram")):
            rows = complete_results()
            row = next(r for r in rows if r["mode"] == mode)
            row["primaryMetric"][raw_key][0].pop()
            with self.assertRaises(ValueError):
                verify(rows, "measured")

    def test_rejects_hidden_tuning_and_invalid_metrics(self):
        rows = complete_results()
        rows[0]["jvmArgs"].append("-Dkyo.scheduler.coreWorkers=64")
        with self.assertRaises(ValueError):
            verify(rows, "measured")

    def test_accepts_identical_repeated_jvm_options(self):
        for profile in PROFILES:
            rows = complete_results(profile)
            for index, row in enumerate(rows):
                row["jvmArgs"] *= 2 if index % 2 else 3
            self.assertEqual(len(verify(rows, profile)), 130)

    def test_repeated_options_cannot_hide_conflicting_configuration(self):
        for conflict in ("-Xmx4g", "-Xms1g", "-XX:+UseZGC", "-Dkyo.scheduler.coreWorkers=64"):
            for prepend in (True, False):
                rows = complete_results()
                flags = rows[0]["jvmArgs"] * 2
                rows[0]["jvmArgs"] = [conflict] + flags if prepend else flags + [conflict]
                with self.assertRaises(ValueError):
                    verify(rows, "measured")
        for name, value in (("score", 0), ("scoreError", float("nan")), ("scoreUnit", "ops/ms")):
            rows = complete_results()
            rows[0]["primaryMetric"][name] = value
            with self.assertRaises(ValueError):
                verify(rows, "measured")
        rows = complete_results()
        rows[0]["secondaryMetrics"]["client.cpu"]["score"] = -1
        with self.assertRaises(ValueError):
            verify(rows, "measured")

    def test_smoke_is_not_a_performance_report(self):
        rows = complete_results("smoke")
        for row in rows:
            row["primaryMetric"]["scoreError"] = "NaN"
        report = render(rows, dict(profile="smoke", started_at="test"))
        self.assertIn("SMOKE TEST ONLY", report)
        self.assertNotIn("1.00x", report)
        self.assertEqual(report.count("| n/a |"), 110)
        with self.assertRaises(ValueError):
            verify(rows, "measured")

    def test_provenance_and_validation_cannot_be_omitted(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            rows = complete_results()
            for name, data in zip(FILES, (rows[:90], rows[90:])):
                (directory / name).write_text(json.dumps(data))
            (directory / "run.log").write_text("\n".join(VALIDATION_MARKERS))
            meta = dict(profile="measured", exit_code=0, source_changed_during_run=False,
                        validation=dict(markers=VALIDATION_MARKERS, passed=True),
                        result_sha256={name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in FILES},
                        run_log_sha256=hashlib.sha256((directory / "run.log").read_bytes()).hexdigest())
            def write_metadata(value):
                (directory / "metadata.json").write_text(json.dumps(value))
            write_metadata(meta)
            self.assertEqual(len(load_verified(directory)[0]), 130)
            for field, value in (("exit_code", 1), ("source_changed_during_run", True),
                                 ("validation", dict(markers=["unrelated success"] * 3, passed=True))):
                changed = copy.deepcopy(meta)
                changed[field] = value
                write_metadata(changed)
                with self.assertRaises(ValueError):
                    load_verified(directory)
            write_metadata(meta)
            (directory / FILES[0]).write_text("[]")
            with self.assertRaises(ValueError):
                load_verified(directory)

    def test_log_must_contain_the_correctness_markers(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "run.log").write_text("PASS unrelated test")
            meta = dict(profile="measured", exit_code=0, source_changed_during_run=False,
                        validation=dict(markers=VALIDATION_MARKERS, passed=True),
                        run_log_sha256=hashlib.sha256((directory / "run.log").read_bytes()).hexdigest())
            (directory / "metadata.json").write_text(json.dumps(meta))
            with self.assertRaises(ValueError):
                load_verified(directory)
