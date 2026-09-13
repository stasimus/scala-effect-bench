# Why Kyo lost the blocking benchmark

6 September 2026. For benchmark readers and Scala developers. Scope: Cats Effect 3.7.1 and Kyo 1.0.0-RC6, JDK 25.0.3, 16-core Apple Silicon, macOS 26.6.1.

Kyo supports blocking, and the original benchmark used a supported construction. Its large loss comes primarily from scheduling short blocking tasks: queued children stay concentrated on a few workers, adaptation supplies limited concurrency, and placement can miss idle workers. Benchmark-side controls addressing all three bring Kyo close to CE in repeated diagnostic runs. This does not establish equal performance generally.

## We used the right blocking construction

Kyo's own [BlockingContentionBench, RC6](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-bench/src/main/scala/kyo/bench/arena/BlockingContentionBench.scala#L5-L22) compares Sync.defer(block()) with CE IO.blocking(block()). Our native Async.foreach control reproduced the slowdown. We did not omit a mandatory Kyo blocking constructor. Its documented [flush hook](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-scheduler/README.md#L403-L418) is an optional queue optimization, not a declaration equivalent to IO.blocking.

## Three causes, tested separately

First, [Kyo prefers the submitting worker](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Scheduler.scala#L296-L331). Children accumulate locally; idle workers do not continuously steal. Plain scheduler tasks reproduce the problem when submitted inside a worker, but run much faster when submitted externally. This isolates the scheduler from effect interpretation and fiber joins. [Submission controls](../results/blocking-investigation/report.md).

Calling flush before each exchange redistributes queued work. Increasing worker capacity alone does little: 64 workers still produce roughly 201 ms batches without flushing. Changing the admission probe interval from its default 100 ms to 1 ms lowers the unflushed batch to 28 ms; 1,000 ms raises it to 327 ms. These interventions support incidental worker activation by externally submitted [admission probes](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/regulator/Admission.scala#L183-L201).

Second, flushing does not add workers. The adaptive run used 22 to 27 logical workers; fixing capacity at 64 improved it again. Each lane performs four exchanges, usually finishing in about 5 to 7 ms. Kyo's default stalling threshold is 10 ms; its CPU sampling monitor selected 6.4 or 12.8 ms cadence in these delayed-I/O JVMs and required two unchanged comparisons. Short recurring waits therefore need not trigger prompt compensation. Growth is gradual and depends on measured scheduling jitter. Thirty seconds of warmup reached 38 workers, rather than 64. [Worker checks](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Worker.scala#L216-L262), [monitor](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/BlockingMonitor.scala#L67-L94), [regulation](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/regulator/Regulator.scala#L151-L181).

By comparison, CE's IO.blocking immediately hands the worker's queued work to a cached or newly created replacement thread. It does not wait for sampled blocking detection. All 64 socket calls overlapped in the CE diagnostics. [CE blocking handoff, v3.7.1](https://github.com/typelevel/cats-effect/blob/v3.7.1/core/jvm-native/src/main/scala/cats/effect/unsafe/WorkerThread.scala#L990-L1054).

Third, Kyo's placement scan defaults to 16 workers on this host, even when capacity is fixed at 64. It can miss the remaining idle workers and queue a lane behind an active one. Sampling first-call start times exposed a few late lanes. Scanning all 64 workers removed most of the residual delay; keeping 128 workers for the same 64 application lanes also helped. This supports placement as the residual cause, without proving every individual queue transition. [Placement algorithm](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/Scheduler.scala#L304-L330), [scan default](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-scheduler/shared/src/main/scala/kyo/scheduler/Flags.scala#L5-L13).

## Measured controls

Same 256 TCP exchanges, 64 persistent connections, requested 1 ms server delay, ordered output and worker joins. Lower batch time is better. The server runs in a separate JVM on the same host. Each diagnostic checks the returned values.

| Construction | Median batch, ms | Peak overlapping calls |
| --- | ---: | ---: |
| CE default | 6.835 | 64 |
| Kyo default | 200.043 | 3 |
| Kyo, fixed 64 workers | 200.739 | 3 |
| Kyo, flush | 16.946 | 22 to 27 |
| Kyo, flush and fixed 16 workers | 22.141 | 16 |
| Kyo, flush and fixed 64 workers | 10.569 | 63 to 64 |

These are ten instrumented batches after six seconds of warmup in one fresh JVM per configuration. [Raw controls](../results/kyo-blocking-research/controls/observations.json), [commands and hashes](../results/kyo-blocking-research/controls/metadata.json).

The decisive placement control was repeated in three fresh JVMs per configuration. Each cell below is that JVM's median of ten batches, in milliseconds.

| Construction | JVM 1 | JVM 2 | JVM 3 |
| --- | ---: | ---: | ---: |
| CE default | 7.315 | 6.808 | 6.250 |
| Kyo, flush and fixed 64 workers | 10.846 | 10.581 | 10.635 |
| Kyo, flush, fixed 64, scan all 64 | 7.014 | 6.848 | 6.608 |
| Kyo, flush and fixed 128 workers | 6.918 | 7.300 | 7.119 |

[Raw replication data](../results/kyo-blocking-research/placement/observations.json), [commands and hashes](../results/kyo-blocking-research/placement/metadata.json). These diagnostic medians are not JMH scores or confidence intervals.

## Other explanations checked

scala.concurrent.blocking did not help Kyo. Native Async.foreach and plain nested scheduler tasks retained the slowdown. Kyo virtual workers also retained it: about 201 ms without flush and 17 ms with flush, despite verified virtual execution. Changing carriers alone does not remove the logical worker queues and capacity. Longer lanes, longer waits, a shorter time slice, and zero server delay produced different behavior, consistent with a workload-sensitive scheduler interaction. [Diagnostic log](../results/kyo-blocking-research/controls/run.log), [earlier construction controls](../results/blocking-investigation/report.md).

The CE virtual variant shifts every request to its virtual executor and back. Default CE can retain a blocking thread across consecutive requests. Its result includes this integration cost and cannot rank virtual threads generally. [Benchmark source](../bench/src/main/scala/bench/blocking/BlockingBench.scala), [CE execution path](https://github.com/typelevel/cats-effect/blob/v3.7.1/core/shared/src/main/scala/cats/effect/IOFiber.scala#L1013).

## What is fair to publish

The original roughly 31x gap is a real result for this default configuration and workload. It is not evidence that Kyo cannot handle blocking or that CE is generally 31x faster. The existing [flush JMH control](../results/blocking-hint/report.md) already reduces the ratio of mean throughputs to about 2.8x, with substantial Kyo uncertainty. The additional scheduler controls explain most of that remainder.

Keep defaults and tuned configurations separately labeled. Before publishing a tuned throughput ratio, run that exact configuration through JMH and report its settings and resource use. More worker capacity is a tradeoff; diagnostic CPU samples and one RSS snapshot per JVM do not establish equivalent resource efficiency.

This investigation covers one host, one release pair and short loopback blocking calls. It does not measure production throughput, cancellation, resource safety or cross-platform behavior. No library implementation changed. Exact source revisions were checked against the release source JARs. The benchmark's original measured Scala sources remain unchanged.

## Reproduce

Run on the same JDK and host for comparable diagnostics; use fresh output directories. Scheduler properties are set before initialization in separate JVMs.

```sh
python3 scripts/research_kyo_blocking.py --output results/research-controls --cases ce plain flush flush16 flush64 plain64 admission1 admission1000 slice1 flushWarm30 flushVirtual plainVirtual longLane longWait ceZero flush64Zero
python3 scripts/research_kyo_blocking.py --output results/research-placement --cases ce flush64 flush64Stride64 flush128 --repeat 3
```

The final tuning uses Scheduler.get.flush() before every exchange plus kyo.scheduler.coreWorkers=64, minWorkers=64, maxWorkers=64, and scheduleStride=64. These are experimental settings, not a general application recommendation. [Probe source](../bench/src/test/scala/bench/blocking/SchedulerResearchProbe.scala), [runner](../scripts/research_kyo_blocking.py).
