# Kyo blocking investigation

Kyo 1.0.0-RC6 supports blocking without a special IO.blocking constructor. Its scheduler detects blocking automatically.
It also documents Scheduler.get.flush() before blocking I/O to drain work waiting on the current worker.

Each diagnostic used a fresh JVM, six seconds of warmup, and three instrumented batches of 256 requests on 64 persistent connections with 1 ms requested server delay.
These are diagnostic timings, not JMH estimates or confidence intervals. Peak counts cover time inside socket calls.

| Construction | Median batch ms | Peak simultaneous calls |
| --- | ---: | ---: |
| Original Kyo fibers | 198.824 | 3 |
| With scala.concurrent.blocking | 199.103 | 3 |
| With Scheduler.get.flush() before I/O | 17.604 | 22 |
| Native Async.foreach over the same lanes | 199.743 | 3 |
| Plain tasks submitted from the main thread | 17.127 | 22 |
| Plain tasks submitted from a scheduler task | 201.117 | 3 |

The plain-task control reproduces the slowdown without effect handling or fiber joins. Changing only where tasks are submitted removes most of it.
Scheduler.schedule prefers the current worker. Worker.wakeup starts an idle worker only when work reaches its queue; idle workers do not continuously poll for work to steal.
Here, each child performs only four socket exchanges. Short children can finish within the default 10 ms time slice, and automatic blocking compensation does not redistribute their queued siblings promptly.
This explanation combines source inspection with the submission controls. It does not identify a defect in the CPU-time sampling implementation.

The benchmark used a supported default construction, but omitted a documented blocking optimization. The 31x figure should not be presented as Kyo's general blocking performance.
A separate [JMH control](../blocking-hint/report.md) measures CE and Kyo with the hint using the original worker loops and socket fixture. Libraries remain unchanged.

The [deeper source investigation](../../docs/kyo-blocking-research.html) explains the remaining gap
through worker capacity and placement, with repeated diagnostic controls. Flush is a queue optimization,
not a blocking declaration equivalent to IO.blocking.

[Probe source](../../bench/src/test/scala/bench/blocking/BlockingTimingProbe.scala), [raw log](run.log), [source hashes](metadata.json), [Kyo documentation](https://getkyo.io/latest/kyo-scheduler/#flush).
