import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from report_four_io import EXPECTED, FILES, TUNING, load_verified, measurements, render
from test_report_blocking import complete_results as old_fixture


def complete_results():
    templates = {r["mode"]: r for r in old_fixture()}
    rows = []
    for mode, transport, limit, runtime in sorted(EXPECTED):
        row = copy.deepcopy(templates[mode])
        row.update(benchmark="bench.io.IoBench.requests", warmupIterations=10)
        row["params"] = dict(size="256", delayMicros="1000", parallelism=limit, runtime=runtime, transport=transport)
        row["secondaryMetrics"]["client.cpu"] = dict(score=2, scoreUnit="ms/batch")
        if runtime == "kyoTuned":
            row["jvmArgs"] += sorted(TUNING)
        rows.append(row)
    return rows


class FourIoReportValidation(unittest.TestCase):
    def test_complete_and_units(self):
        rows = complete_results()
        self.assertEqual(len(measurements(rows)), 38)
        report = render(rows, {})
        self.assertIn("10.000 | 20.000 | 1,000 | 2.000", report)
        self.assertEqual(report.count("CIs overlap)"), 15)
        self.assertNotIn("\u2014", report)

    def test_rejects_partial_or_mixed_runs(self):
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

    def test_rejects_hidden_tuning(self):
        for runtime in ("ce", "kyoTuned"):
            rows = complete_results()
            row = next(r for r in rows if r["params"]["runtime"] == runtime)
            row["jvmArgs"] = [a for a in row["jvmArgs"] if a not in TUNING]
            if runtime == "ce":
                row["jvmArgs"] += sorted(TUNING)
            with self.assertRaises(ValueError):
                measurements(rows)

    def test_hash_and_validation_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            rows = complete_results()
            for name, data in zip(FILES, (rows, [], [])):
                (directory / name).write_text(json.dumps(data))
            meta = dict(exit_code=0, source_changed_during_run=False,
                validation=dict(default_checks=367, tuned_checks=38, passed=True),
                result_sha256={name: hashlib.sha256((directory / name).read_bytes()).hexdigest() for name in FILES})
            (directory / "metadata.json").write_text(json.dumps(meta))
            self.assertEqual(len(load_verified(directory)[0]), 38)
            (directory / FILES[0]).write_text("[]")
            with self.assertRaises(ValueError):
                load_verified(directory)
