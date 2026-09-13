import copy
import hashlib
import unittest

from report_blocking import EXPECTED, measurements, render, validate_provenance


def complete_results():
    rows = []
    for mode, delay, limit, runtime in sorted(EXPECTED):
        score = 100 if mode == "thrpt" else 0.01
        metric = {"score": score, "scoreError": score / 10,
                  "scoreUnit": "ops/s" if mode == "thrpt" else "s/op"}
        if mode == "thrpt":
            metric["rawData"] = [[score] * 5 for _ in range(3)]
        else:
            metric["rawDataHistogram"] = [[[[0.01, 100]]] * 5 for _ in range(3)]
            metric["scorePercentiles"] = {"50.0": 0.01, "99.0": 0.02}
        rows.append({"benchmark": "bench.blocking.BlockingBench.requests", "mode": mode,
                     "params": {"size": "256", "delayMicros": delay, "parallelism": limit, "runtime": runtime},
                     "forks": 3, "warmupIterations": 5, "measurementIterations": 5, "threads": 1,
                     "warmupTime": "1 s", "measurementTime": "1 s", "jvm": "/java", "jdkVersion": "25.0.3",
                     "jmhVersion": "1.37", "jvmArgs": ["-Xms2g", "-Xmx2g", "-XX:+UseG1GC"],
                     "primaryMetric": metric,
                     "secondaryMetrics": {"gc.alloc.rate.norm": {"score": 1000, "scoreUnit": "B/op"}}})
    return rows


class BlockingReportValidation(unittest.TestCase):
    def test_complete_and_units(self):
        rows = complete_results()
        self.assertEqual(len(measurements(rows)), 24)
        report = render(rows, {})
        self.assertEqual(report.count("CIs overlap)"), 8)
        self.assertIn("10.000 | 20.000 | 1,000", report)
        self.assertNotIn("\u2014", report)

    def test_incomplete_duplicate_or_mixed(self):
        rows = complete_results()
        variants = [rows[:-1], rows + [rows[0]]]
        mixed = copy.deepcopy(rows)
        mixed[0]["jdkVersion"] = "21"
        variants.append(mixed)
        partial = copy.deepcopy(rows)
        partial[0]["primaryMetric"]["rawDataHistogram"].pop()
        variants.append(partial)
        for variant in variants:
            with self.assertRaises(ValueError):
                measurements(variant)

    def test_rejects_wrong_unit_or_percentiles(self):
        for field, value in (("scoreUnit", "ms/op"), ("scorePercentiles", {"50.0": 0.02, "99.0": 0.01})):
            rows = complete_results()
            rows[0]["primaryMetric"][field] = value
            with self.assertRaises(ValueError):
                measurements(rows)

    def test_provenance(self):
        data = b"[]"
        meta = {"source_changed_during_run": False, "exit_code": 0,
                "validation": {"checks": 102, "passed": True}, "result_sha256": hashlib.sha256(data).hexdigest()}
        validate_provenance(meta, data)
        for key, value in (("source_changed_during_run", True), ("exit_code", 1),
                           ("validation", {}), ("result_sha256", "wrong")):
            with self.assertRaises(ValueError):
                validate_provenance(dict(meta, **{key: value}), data)


if __name__ == "__main__":
    unittest.main()
