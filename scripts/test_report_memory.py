import ctypes
import unittest

from report_memory import verify
from run_memory import JVM_ARGS, RusageV4, cases


def fixture():
    rows = []
    for mode, count, setting in cases("measured"):
        for runtime in ("ce", "loom"):
            for fork in range(3):
                stages = ("baseline", "held", "live", "released", "complete") if mode == "parked" else (
                    "baseline", "active", "idle", "complete")
                events = [dict(stage="hello", runtime=runtime, mode=mode, count=count, setting=setting,
                               jdk="25.0.3", input_args=[*JVM_ARGS, "-Xlog:gc=info:file=test.gc.log"])]
                for i, stage in enumerate(stages):
                    event = dict(stage=stage, heap_used=100, heap_committed=200,
                                 nonheap_used=100, nonheap_committed=200, gc_count=3+i*3)
                    if stage in ("held", "live"):
                        event.update(waiting=count, completed=0)
                    if stage == "released":
                        event.update(completed=count)
                    if stage == "idle":
                        event.update(checked_batches=1)
                    events.append(event)
                counts = dict(baseline=5, held=10, live=5, released=5) if mode == "parked" else dict(baseline=5, active=50, idle=5)
                samples = [dict(stage=stage, rss=200, footprint=100, peak_footprint=150)
                           for stage,n in counts.items() for _ in range(n)]
                rows.append(dict(runtime=runtime, mode=mode, count=count, setting=setting, fork=fork,
                                 exit_code=0, jvm_args=list(JVM_ARGS), events=events, samples=samples,
                                 peak_footprint_before_exit=150))
    return rows


class MemoryReportTest(unittest.TestCase):
    def test_darwin_abi_layout(self):
        self.assertEqual([ctypes.sizeof(RusageV4), RusageV4.resident_size.offset,
                          RusageV4.phys_footprint.offset, RusageV4.lifetime_max_phys_footprint.offset],
                         [296, 64, 72, 240])

    def test_complete_matrix(self):
        verify(fixture(), "measured")

    def test_rejects_missing_process_and_samples(self):
        rows = fixture()
        with self.assertRaises(ValueError):
            verify(rows[:-1], "measured")
        rows[0]["samples"].pop()
        with self.assertRaises(ValueError):
            verify(rows, "measured")

    def test_rejects_different_work_and_early_completion(self):
        for change in (dict(waiting=999), dict(completed=1)):
            rows = fixture()
            rows[0]["events"][3].update(change)
            with self.assertRaises(ValueError):
                verify(rows, "measured")
        rows = fixture()
        rows[0]["events"][0]["setting"] = "8192"
        with self.assertRaises(ValueError):
            verify(rows, "measured")

    def test_rejects_ignored_gc_and_memory_errors(self):
        for field, value in (("gc_count", 3), ("heap_used", 201)):
            rows = fixture()
            rows[0]["events"][3][field] = value
            with self.assertRaises(ValueError):
                verify(rows, "measured")
        rows = fixture()
        rows[0]["samples"][0]["footprint"] = 151
        with self.assertRaises(ValueError):
            verify(rows, "measured")

    def test_rejects_unmatched_heap(self):
        rows = fixture()
        rows[0]["jvm_args"].append("-Xms2g")
        with self.assertRaises(ValueError):
            verify(rows, "measured")

    def test_non_atomic_kernel_counters(self):
        rows = fixture()
        rows[0]["samples"][0].update(footprint=151, peak_footprint=150)
        rows[0]["peak_footprint_before_exit"] = 151
        verify(rows, "measured")


if __name__ == "__main__":
    unittest.main()
