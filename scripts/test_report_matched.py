import copy
import unittest
from pathlib import Path

from report_matched import PAIRS, PARAMS, ROOT, pairs, render


def complete_results():
    rows = []
    for cls, _, left, right in PAIRS:
        for params in PARAMS[cls]:
            for method, score in ((left, 100.0), (right, 200.0)):
                rows.append({
                    "benchmark": f"bench.matched.{cls}.{method}", "params": params.copy(),
                    "forks": 3, "warmupIterations": 5, "measurementIterations": 5,
                    "warmupTime": "1 s", "measurementTime": "1 s", "threads": 1,
                    "jvm": "/example/java", "jdkVersion": "25.0.3", "jmhVersion": "1.37",
                    "jvmArgs": ["-Xms2g", "-Xmx2g", "-XX:+UseG1GC"],
                    "primaryMetric": {"score": score, "scoreError": 1.0, "scoreUnit": "ops/s",
                                      "rawData": [[score] * 5 for _ in range(3)]},
                    "secondaryMetrics": {"gc.alloc.rate.norm": {"score": 1000.0}},
                })
    return rows


class ReportValidation(unittest.TestCase):
    def test_complete_report(self):
        rows = complete_results()
        self.assertEqual(len(rows), 44)
        self.assertEqual(len(pairs(rows)), 22)
        text = render(rows, ROOT / "results/matched/current.json", {"machine": {}})
        self.assertEqual(text.count("Kyo ~2.00×"), 22)
        self.assertNotIn("ReleaseSweep", text)

    def test_rejects_missing_pair(self):
        rows = complete_results()
        rows.pop()
        with self.assertRaises((ValueError, KeyError)):
            pairs(rows)

    def test_marks_overlapping_intervals(self):
        rows = complete_results()
        for row in rows:
            row["primaryMetric"]["scoreError"] = 100.0
        text = render(rows, ROOT / "results/matched/current.json", {"machine": {}})
        self.assertEqual(text.count("(CIs overlap)"), 22)

    def test_rejects_partial_forks(self):
        rows = complete_results()
        rows[0]["primaryMetric"]["rawData"].pop()
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            pairs(rows)

    def test_rejects_duplicates(self):
        rows = complete_results()
        rows.append(copy.deepcopy(rows[0]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            pairs(rows)

    def test_rejects_mixed_jvms(self):
        rows = complete_results()
        rows[0]["jdkVersion"] = "21"
        with self.assertRaisesRegex(ValueError, "Mixed"):
            pairs(rows)

    def test_rejects_historical_data(self):
        rows = complete_results()
        rows[0]["benchmark"] = "bench.CoreLoopBench.ceBindDeep"
        with self.assertRaisesRegex(ValueError, "historical"):
            pairs(rows)

    def test_rejects_unknown_parameters(self):
        rows = complete_results()
        rows[0]["params"] = {"work": "100"}
        with self.assertRaisesRegex(ValueError, "parameters"):
            pairs(rows)


if __name__ == "__main__":
    unittest.main()
