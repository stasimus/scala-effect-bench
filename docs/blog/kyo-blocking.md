# Blocking I/O made Kyo 30x slower. Then I tried flush().

Kyo still has my attention. After the [first synthetic comparison](https://sgektor.blogspot.com/2026/09/kyo-vs-cats-effect-promising-numbers.html), I wanted to see what happened when the programs had to wait for actual socket I/O.

I added Gears and Ox too. Same TCP exchanges, connection limits and ordered results. The nonblocking numbers were close. Blocking exposed a much bigger difference.

Kyo led cats-effect by about 12% for nonblocking I/O at concurrency 8. At 64, it trailed by about 3%. That is already too mixed for a blanket claim that Kyo is faster. The earlier synthetic wins still describe those particular operations.

For blocking I/O at concurrency 64, cats-effect completed about 149 batches per second. Default Kyo managed about 5.

Each batch contained 256 requests over 64 persistent connections. A separate server JVM requested a 1 ms sleep before each response. These measurements used cats-effect 3.7.1 and Kyo 1.0.0-RC6 on one Mac with JDK 25.0.3, recorded on 6 September 2026.

| Blocking configuration | Batches/s, higher is better |
| --- | ---: |
| cats-effect default | 149.00 ± 2.93 |
| Kyo default | 4.96 ± 0.02 |
| Kyo with flush before every exchange | 81.86 ± 25.62 |
| Kyo with flush and scheduler tuning | 144.89 ± 3.60 |

Three JVM forks per case. The ± values are JMH's 99.9% confidence-interval half-widths. Flush alone varied substantially; the tuned Kyo and default CE intervals overlap.

The [source investigation](https://github.com/stasimus/scala-effect-bench/blob/main/docs/kyo-blocking-research.html) pointed to queued children collecting on a few workers, slow adaptation to short blocking calls, and a placement scan that could miss idle workers.

Calling the scheduler's flush hook before each exchange redistributed queued work:

```scala
Sync.defer {
    kyo.scheduler.Scheduler.get.flush()
    io.blocking(lane, index)
}
```

That is the blocking branch from the benchmark. Flush made a huge difference, but reaching CE's range also required setting `coreWorkers`, `minWorkers`, `maxWorkers` and `scheduleStride` to 64. Those are experimental settings for this workload. No library code changed.

Flush does not move the blocking call to another thread or create more workers. It gives queued tasks another chance to run elsewhere. We called it before every exchange; we have not tested whether once per worker would be enough.

The manual step bothers me. Kyo supports blocking through `Sync.defer`, yet getting good performance here required knowing about a scheduler hook. CE handled the same workload well with `IO.blocking` and its default runtime.

Kyo's [Finagle integration already calls flush inside its blocking hook](https://github.com/getkyo/kyo/blob/2e58c0550b209317b85a30fc5787c24b7e4dd63c/kyo-scheduler-finagle/jvm/src/main/scala/kyo/scheduler/KyoFinagleSchedulerService.scala#L54-L67). When that integration is enabled and the call goes through the hook, it performs the flush automatically. That does not cover every arbitrary blocking call, and it does not apply our worker tuning. We did not benchmark Finagle.

Putting flush in every `Sync.defer` would also affect cheap side effects. My reading is that this could add scheduling overhead; I have no maintainer confirmation of that design rationale. A dedicated blocking helper seems worth exploring.

Gears and Ox landed around 139 blocking batches/s at concurrency 64 and allocated much less than CE. Their direct style is interesting even without a throughput win: ordinary loops, conditions and local helpers around I/O, with fewer effect combinators. That may matter more in everyday code than a small benchmark lead.

I still want to try Kyo in an application. I also want to know how much scheduler knowledge that application will need.

[Full tables, allocation, CPU and raw measurements](https://github.com/stasimus/scala-effect-bench/blob/main/results/four-io-measured/report.md), plus [methods and reproduction](https://github.com/stasimus/scala-effect-bench/blob/main/docs/four-library-io.md). The suite passed 438 correctness checks. This is one loopback workload; it does not establish production performance or equivalent cancellation guarantees.
