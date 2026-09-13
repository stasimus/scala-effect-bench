# Four-library TCP comparison

CE 3.7.1, Kyo 1.0.0-RC6, Gears 0.3.1 and Ox 1.0.6 on Scala 3.8.4 and JDK 25.0.3.
This is a separate suite; the earlier synthetic and blocking results remain historical measurements.

[Measured results](../results/four-io-measured/report.md), including confidence intervals, latency, allocation and CPU.

[Published blog](https://sgektor.blogspot.com/2026/09/blocking-io-made-kyo-30x-slower-then-i.html),
[article source](blog/kyo-blocking.md), [Blogger HTML](blog/kyo-blocking-blogger.html),
and [LinkedIn draft](blog/kyo-blocking-linkedin.txt).

Each batch completes 256 integer request/response exchanges through 8 or 64 persistent TCP connections.
Lane `n` processes indices `n, n + parallelism, ...`, with one outstanding request per connection.
Both transports write four bytes and read four bytes, handling partial reads; no extra batching is used.
Every implementation starts a root task on its runtime, creates all lane tasks, stores ordered results,
joins every lane, then propagates the first lane-ordered error or returns the same Vector.
Lane errors are captured to prevent one failure from cancelling healthy lanes before they finish.
Scope bookkeeping is included using each library's APIs. These APIs need not have identical behavior
under external parent cancellation, which this performance comparison does not exercise.

The same server implementation runs in a separate JVM for every trial. It requests a 1 ms sleep
before replying. Actual delay includes timer and scheduling overhead. Its allocation and CPU are
excluded from client resource metrics, but it shares the host's CPU. Server startup, connection setup,
connection buffers and optional executors are outside measurement.

| Client transport | CE | Kyo | Gears | Ox |
| --- | --- | --- | --- | --- |
| Blocking `java.net.Socket` | `IO.blocking` | `Sync.defer` | Direct call inside a Future | Direct call inside a fork |
| Nonblocking `AsynchronousSocketChannel` | `IO.async` | Fiber promise | `Future.withResolver` and await | Buffered one-element channel and receive |

The nonblocking transport, partial-read/write handling and two platform completion threads are shared
across all four adapters. It is callback-driven socket I/O, not a blocking socket call moved to an executor.
Gears and Ox use their default virtual-thread execution in both modes: a direct-style wait need not hold
an OS carrier thread. Completion callbacks publish exactly one result; Ox's one-element buffer prevents
the callback from waiting for its receiver. The adapter includes a cancellation hook that closes an
unfinished exchange's connection, while doing nothing after completion.

CE uses a per-batch Supervisor and explicit joins; Kyo uses scoped fibers and explicit joins;
Gears uses scoped Futures; Ox uses an unsupervised scope and explicit joins. All measured requests
succeed. Validation also checks per-lane failure handling and joining healthy workers before returning.
The benchmark measures these integrations, including their scope and callback costs, not an isolated interpreter.

## Defaults and alternatives

Default settings are measured separately for each library and transport at both concurrency limits.
Application concurrency is equal; internal worker and thread counts follow each runtime's defaults.
Equal request limits do not imply equal CPU or memory consumption.
Blocking-only alternatives at concurrency 64 are:

| Alternative | Change |
| --- | --- |
| CE virtual lanes | Shift each whole lane once to a virtual-thread executor; retain default compute runtime |
| Kyo queue flush | Call `Scheduler.get.flush()` before every exchange |
| Kyo fixed64 | Flush plus `coreWorkers=minWorkers=maxWorkers=scheduleStride=64` |

These alternatives address the previous blocking investigation. They are not an exhaustive tuning contest.
Scheduler properties are set before JVM initialization, never changed during a trial. Validation verifies
platform versus virtual execution. Default Kyo may adapt during warmup and measurement; its variation
and confidence intervals are part of the result. Automatic adaptation is not frozen for the default row.

## Measurement and reproduction

Run `python3 scripts/run_four_io.py --output results/four-io` with a fresh output directory.
Allow about 40 minutes after compilation. The runner validates all adapters, including the tuned Kyo
configuration in a fresh JVM, then measures 38 configurations: throughput and sampled batch latency,
three JVM forks, ten 1-second warmup and five 1-second measurement iterations, one JMH caller,
fixed 2 GiB G1 client heap. Only one performance process runs at a time.

The report requires every expected configuration, fork and iteration, matching workload/JVM settings,
correct units, correctness markers and unchanged source hashes. Raw results, commands, dependency hashes
and logs are retained. Allocation comes from JMH's GC profiler; process CPU per operation comes from
the shared client profiler. CPU includes the client's runtime, GC and completion threads.

Throughput confidence intervals are JMH's 99.9% intervals. Overlapping intervals do not establish a winner.
Configuration order is recorded in the commands and logs; it is not randomized. Host drift remains a limitation.
Latency is for complete batches under a closed-loop driver, with limited precision in the tails.
Neither allocation nor CPU measures retained memory or native thread-stack costs. No zero-delay or
production HTTP/JDBC workload is implied by this 1 ms loopback result. Parent cancellation, resource-safety
equivalence and open-loop overload behavior require separate experiments.
The shared completion pool and local server may cap throughput; close scores can reflect an I/O bottleneck.

Validation covers empty/partial/full batches, ordered values, exactly-once execution, concurrency limits,
actual worker overlap, virtual/platform thread placement, inline callback failures, join-all failure handling,
connection reuse and callback-transport cancellation. The success measurements do not claim identical
cross-library cancellation guarantees.

The recorded run passed 405 checks before measurement. Another 33 checks at concurrency 64 passed
afterward, covering exact outputs, thread placement, joined failures and connection reuse. These test
sources were added after measurement; measured code and build settings are unchanged. See the
[additional validation log](../results/four-io-measured/additional-validation.log) and
[source hashes](../results/four-io-measured/additional-validation.json). Future runs include these checks.

## Sources

- [Gears 0.3.1 release](https://github.com/lampepfl/gears/releases/tag/v0.3.1),
  [default JVM support](https://github.com/lampepfl/gears/blob/v0.3.1/jvm/src/main/scala/async/DefaultSupport.scala),
  [Futures and callback resolver](https://github.com/lampepfl/gears/blob/v0.3.1/shared/src/main/scala/async/futures.scala).
- [Ox 1.0.6 release](https://github.com/softwaremill/ox/releases/tag/v1.0.6),
  [callback integration guidance](https://github.com/softwaremill/ox/blob/v1.0.6/doc/other/best-practices.md),
  [unsupervised scopes](https://github.com/softwaremill/ox/blob/v1.0.6/core/src/main/scala/ox/unsupervised.scala).
- [CE 3.7.1 blocking implementation](https://github.com/typelevel/cats-effect/blob/v3.7.1/core/jvm-native/src/main/scala/cats/effect/unsafe/WorkerThread.scala#L990),
  [Kyo RC6 fibers and promises](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-core/shared/src/main/scala/kyo/Fiber.scala).
- [JDK 25 asynchronous sockets](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/AsynchronousSocketChannel.html).

Local sources: [runtime adapters](../io-bench/src/main/scala/bench/io/IoBench.scala),
[TCP transport](../io-bench/src/main/scala/bench/io/TcpFixture.scala),
[validation](../io-bench/src/test/scala/bench/io/Validation.scala),
[concurrency-64 checks](../io-bench/src/test/scala/bench/io/MeasuredIoValidation.scala).
