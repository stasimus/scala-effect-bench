# Blocking TCP and virtual threads

Separate extension to the [published synthetic baseline](../README.md), using the same library versions.

The later [four-library JMH comparison](../results/four-io-measured/report.md) measures nonblocking I/O
and the final Kyo tuning too. The results below document the earlier investigation.

[Default-runtime results](../results/blocking/report.md): CE default led these cases; virtual calls did not improve throughput.

Kyo supports blocking automatically, but its documented `Scheduler.get.flush()` hook matters here.
Short blocking children accumulate in the spawning worker's local queue. Native `Async.foreach`
and `scala.concurrent.blocking` reproduce the slowdown; draining the queue before I/O removes most of it.
See the [controlled investigation](../results/blocking-investigation/report.md) and the
[focused JMH control](../results/blocking-hint/report.md). The original 31x gap describes the unhinted construction,
not Kyo's best supported blocking performance.

In the focused control, CE measured 150.77 ± 3.83 batches/s and Kyo with flush 54.72 ± 15.17.
The ratio of means is about 2.8x; Kyo's uncertainty is substantial. This describes the queue-flush
variant, not equivalent blocking declarations: flush redistributes tasks without adding workers.

The [source investigation](kyo-blocking-research.html) identifies queue concentration, adaptive worker
capacity, and incomplete placement scans. Flushing with 64 fixed workers and a 64-worker scan brought
Kyo to 6.6 to 7.0 ms per batch versus CE's 6.3 to 7.3 ms across three fresh JVMs.
These are diagnostic medians, not new JMH scores. The original default construction is supported by Kyo.

| Variant | Socket call |
| --- | --- |
| CE default | `IO.blocking(exchange)` on the default runtime |
| CE virtual calls | `IO.blocking(exchange).evalOn(virtualExecutor)` per request; default compute runtime |
| Kyo default | `Sync.defer(exchange)` on the default adaptive scheduler |
| Kyo with queue flush | `Sync.defer { Scheduler.get.flush(); exchange }` |

CE 3.7 can execute blocking calls in place on compute workers. Replacing only `IORuntime.blocking`
does not guarantee virtual-thread execution. This benchmark explicitly shifts each socket call.
It tests the cost and benefit of that integration, including executor transitions and virtual-thread allocation.
Kyo's optional virtual workers are excluded from this comparison.

Each batch completes 256 binary TCP requests through 8 or 64 persistent connections.
Both libraries start the same number of workers, use the same static request indices and ordered array,
join all workers, and return a Vector. Each connection has at most one outstanding request.
Worker errors are captured, all workers are joined, then failures propagate.

An identical server runs in a separate JVM per trial. It adds either no delay or a requested 1 ms
sleep before each response. This is real loopback socket I/O with simulated service time;
actual delay includes timer and scheduling overhead. Client and server share the host's CPU.
Connections, executors, and server startup are outside measurement; batch creation and execution are inside.

JMH measures batch throughput, sampled batch latency (p50/p99), and client allocation.
Allocation excludes the server and does not measure native stacks, retained memory, or peak RSS.
Batch latency is a closed-loop measurement; it is not per-request latency under an external arrival rate.
Short iterations limit tail-latency precision; inspect the raw histograms before drawing conclusions from p99.
The small local server and socket stack may dominate these results.
Cancellation, resource safety, production HTTP/JDBC workloads, and full virtual compute runtimes need separate experiments.

Run on JDK 21+ (JDK 25 recommended to match the baseline):

```sh
python3 scripts/run_blocking.py --output results/blocking-new
```

The runner requires a fresh directory, validates 102 checks, then measures all 24 configurations:
three variants, two concurrency limits, two delays, and two JMH modes.
Each case uses three forks, five 1-second warmup and five 1-second measurement iterations.
Allow roughly 20 minutes. Results, logs, source/dependency hashes, and a report stay in that directory.
The published baseline is not regenerated.

Validation covers actual TCP responses, empty and partial batches, ordering, exactly-once execution,
concurrency, platform/virtual thread placement, worker failure propagation, and subsequent reuse.

```sh
sbt 'set bench / Test / fork := true' 'bench/Test/runMain bench.blocking.Validation'
python3 -m unittest discover -s scripts -p 'test_report_*.py'
```

Reproduce the diagnostic separately from measurement:

```sh
sbt 'set bench / Test / fork := true' 'bench/Test/runMain bench.blocking.BlockingTimingProbe ce 64' 'bench/Test/runMain bench.blocking.BlockingTimingProbe kyo 64'
```

The Kyo probe accepts a third argument: `plain`, `scalaBlocking`, `flush`, `native`, `external`, or `nested`.
The last two use plain scheduler tasks to isolate submission from outside versus inside a worker.
Each mode should run in a fresh JVM. Probe timings are diagnostic, not JMH scores.

Reproduce the focused 64-connection, 1 ms delay control with
`python3 scripts/run_blocking_hint.py --output results/blocking-hint-new`.
It validates 48 cases, then measures CE and Kyo with flush in both JMH modes using three forks each.

[CE virtual-thread discussion](https://github.com/typelevel/cats-effect/discussions/3927),
[Kyo scheduler](https://getkyo.io/latest/kyo-scheduler/),
[JDK allocation counter](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.management/com/sun/management/ThreadMXBean.html#getTotalThreadAllocatedBytes()).
