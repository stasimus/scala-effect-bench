# Results

Kyo 1.0.0-RC6 against cats-effect 3.7.1, Scala 3.8.4, run on 2026-09-01. Throughput in ops/s, higher is
better. The ratio column says which side was faster and by how much.

## How this was run

```
sbt "bench/Jmh/run -i 3 -wi 2 -f 1 -r 1s -w 1s -p <params> .*<Class>.*"
```

One fork, short iterations, one `@Param` value per class. This command describes the later runs;
`fair-coreloop.json` and the semaphore results instead have one warmup and two measurements, each one
second. Those two-measurement results have no calculable confidence interval. This is an exploratory
pass: exact ratios need longer runs and multiple forks, and there is no fixed significance threshold.

The harness went through two fairness rounds, both described at the end. Both sides run through their
scheduler once per op (`unsafeRunSync()` against `Fiber.initUnscoped` + `block`, measured equal at ~32 us),
cats-effect keeps its default `tracing.mode=cached`, and earlier corrections matched sequential/parallel
combinators, source chunk sizes, and primitives. The [2026-09-05 audit](audit.md) found remaining
differences in output chunking, buffering, producer traversal, and cancellation completion. The
release-sweep benchmark and its result were removed; historical raw output is retained for the audit.

Machine: 12 cores, aarch64, 19 GB RAM, OpenJDK 25.0.4.1 (Temurin), inside a container. Both runtimes on
default scheduler settings. Raw JMH output in `results/fixed-run.log`, `results/fair-run.log` and
`results/run10.log`, per class JSON alongside.

## Numbers

### Core interpreter loops

`CoreLoopBench`, depth = 10000, from the symmetric-runner run

| scenario | cats-effect | Kyo | ratio |
| --- | ---: | ---: | --- |
| deep bind chain | 4,984.5 | 4,261.7 | cats-effect 1.17x |
| left-associated bind chain | 3,156.4 | 2.7 | cats-effect 1179.39x |
| map chain | 5,250.2 | 5,876.9 | Kyo 1.12x |
| pure bind chain | 4,376.2 | 7,844.2 | Kyo 1.79x |
| raise and handle | 4,807.2 | 2,703.6 | cats-effect 1.78x |

The pure Kyo map/bind loops operate eagerly on plain values; IO builds a computation to interpret.
Those rows compare representations rather than equal interpreter instruction counts. Both sides
suspend each increment in the left-associated bind row.

### Typeclasses and their Kyo counterparts

`AlgebraBench`, size = 4096

| scenario | cats-effect | Kyo | ratio |
| --- | ---: | ---: | --- |
| Monoid combineAll / Kyo.foldLeft | 4,107.1 | 6,371.3 | Kyo 1.55x |
| foldMapM / foreach + fold | 2,510.5 | 4,081.6 | Kyo 1.63x |
| traverse / Kyo.foreach | 3,125.3 | 5,367.8 | Kyo 1.72x |
| mapN / sequential map (both sequential) | 28,038.0 | 28,468.6 | Kyo 1.02x |
| parMapN / Async.zip (both parallel) | 22,760.3 | 26,885.9 | Kyo 1.18x |
| parTraverse / Async.foreach | 773.6 | 1,449.9 | Kyo 1.87x |
| parTraverseN(8) / Async.foreach(8) | 121.8 | 6,826.0 | Kyo 56.03x |
| attempt + partition / Abort.run | 594.8 | 1,243.0 | Kyo 2.09x |
| collect successes / Async.gather | 590.3 | 105.3 | cats-effect 5.61x |

The success-collection pair returns the same 4,092 ordered successes for this input. Kyo gather also
sorts completed results by input index. When all tasks fail, Kyo propagates an error while the CE code
returns an empty vector; the row does not test that case.

### Context effects and error handling

`ContextBench`, ops = 10000

| scenario | cats-effect | Kyo | ratio |
| --- | ---: | ---: | --- |
| Kleisli / Env | 960.1 | 1,850.3 | Kyo 1.93x |
| StateT / Var | 840.6 | 2,857.9 | Kyo 3.40x |
| WriterT[Chain] / Emit | 1,159.0 | 2,715.4 | Kyo 2.34x |
| Resource / Scope, 32 nested | 22,727.5 | 19,382.4 | cats-effect 1.17x |
| handleErrorWith / Abort.recover | 1,753.3 | 1,819.5 | Kyo 1.04x |
| orElse / Abort.fold | 1,701.6 | 2,435.5 | Kyo 1.43x |

### Concurrency primitives

`PrimitivesBench`, ops = 1000, producers = 4

| scenario | cats-effect | Kyo | ratio |
| --- | ---: | ---: | --- |
| Ref / AtomicRef, single fiber | 16,752.7 | 13,117.4 | cats-effect 1.28x |
| Ref / AtomicRef, 4 fibers | 12,124.0 | 17,077.4 | Kyo 1.41x |
| Deferred / Promise | 6,871.3 | 5,943.8 | cats-effect 1.16x |
| Queue / Channel | 3,898.9 | 5,975.0 | Kyo 1.53x |
| Semaphore / Meter, uncontended | 1,817.8 | 3,479.8 | Kyo 1.91x |
| race (against never on both sides) | 662.0 | 1,324.6 | Kyo 2.00x |
| timeout | 604.7 | 1,958.8 | Kyo 3.24x |
| fiber spawn and join | 1,446.1 | 2,400.3 | Kyo 1.66x |

### Streaming pipelines (fs2 against Kyo Stream)

`PipingBench`, size = 10000, par = 4. Parallel-map sources use 4096-element chunks; output chunking
and buffering differ. The left column is fs2 3.13.0 running on `IO`, not cats-effect itself.

| scenario | fs2 | Kyo | ratio |
| --- | ---: | ---: | --- |
| pure pipeline | 6,003.1 | 5,271.9 | fs2 1.14x |
| effectful pipeline | 158.9 | 987.3 | Kyo 6.21x |
| parEvalMap / mapPar | 19.1 | 536.1 | Kyo 28.01x |
| parEvalMapUnordered / mapParUnordered | 21.3 | 328.6 | Kyo 15.44x |
| merge via parJoin / Stream.collectAll | 2,976.5 | 5,778.7 | Kyo 1.94x |
| broadcast, both branches consumed concurrently | 2,041.5 | 4,093.7 | Kyo 2.01x |
| queue-backed producer and consumer | 51.4 | 1,061.7 | Kyo 20.67x |
| early termination of infinite source | 139.0 | 821.3 | Kyo 5.91x |

### Semaphore cancelation, the #4648 shapes

`SemaphoreCancelBench`, n = 10000, limit = 8. Cancellation rows are provisional: the arrival latch
does not establish queue occupancy, and Kyo `interrupt` followed by `getResult` does not wait for
finalizers as CE `cancel` does. Their raw scores remain below, but are not equivalent completed-work
comparisons. The happy path and contention row also use different traversal strategies.

| scenario | cats-effect | Kyo | ratio |
| --- | ---: | ---: | --- |
| parTraverseN aborted by early failure | 607.8 | 22,635.0 | not comparable |
| parTraverseN happy path | 43.6 | 3,448.1 | Kyo 79.07x |
| mass cancel of would-be waiters | 6.8 | 97.9 | not comparable |
| contended acquire and release | 44.5 | 963.1 | Kyo 21.66x |
| permit count read under cancel | 12.3 | 105.6 | not comparable |

## What stands out

The strongest interpreter finding is the left-associated bind pathology. Several other rows favor Kyo,
but counting wins would mix noisy results, different algorithms, and unmatched cancellation semantics.
The tables describe these specific workloads rather than a general ranking of the runtimes.

Bounded parallel traversal remains the widest real gap: `parTraverseN(8)` at 122 ops/s against
`Async.foreach(concurrency = 8)` at 6,826, with the same shape in the semaphore table. `parTraverseN` spawns
a fiber per element and gates each on a `MiniSemaphore` permit; `Async.foreach` runs a fixed set of worker
fibers pulling elements by index. The next section takes that gap apart.

The left-associated chain `acc.map(v => Sync.defer(v + 1))` exhibits quadratic behavior when evaluated.
The audit reproduced it across two JVM forks: increasing depth from 1000 to 10000 raised Kyo allocation
from 16.1 MB to 1.60 GB per operation, almost 100x. Both sides returned the correct result. This is a
stress case; the right-associated bind benchmark does not show the same pathology.

Streaming costs include output chunking and coordination. In the audit environment, Kyo's ordered map
fell from 762 to 110 ops/s with singleton source chunks, against fs2's 21 ops/s. The queue gap survived
a producer control: changing CE to a direct collection traversal made it slower (31 versus 49 ops/s).
These controls explain limitations of the comparison; they do not replace the historical table, since
they ran on macOS/Oracle JDK 25.0.3 rather than the original Linux/Temurin environment. See [audit.md](audit.md).

## Isolating the semaphore from the traversal

The tables above mix two things: how each library fans work out, and how its permit primitive behaves.
`BoundedTraversalBench` and `SemaphoreIsolationBench` separate them.

`ceWorkerPool` is Kyo's strategy hand rolled on `IO`: an `AtomicInteger` index and `limit` fibers pulling
from it, no permits anywhere. Comparing it with `ceParTraverseN` tests the traversal strategy. Comparing
it with `kyoAsyncForeach` still includes runtime, collection, and combinator implementation differences.

`BoundedTraversalBench`, size = 4096

| limit | `parTraverseN` | worker pool on IO | `Async.foreach` |
| ---: | ---: | ---: | ---: |
| 8 | 124.2 | 3,005.9 | 5,935.7 |
| 64 | 147.7 | 2,326.8 | 5,773.2 |

The IO worker pool improves throughput roughly 24x at limit 8 and 16x at limit 64. The remaining gaps
to Kyo are about 2x and 2.5x. This supports a substantial cost from the traversal strategy, but does
not isolate the runtime's contribution to the remaining difference.

`PoolParTraverse.parTraverseNPool` packages that strategy as a drop-in replacement with `parTraverseN`'s
signature and failure semantics: `limit` workers pull elements by an atomic index, results keep their order,
and the first error cancels everything in flight through `parSequence_`. Measured against the real thing at
size 4096:

| limit | `parTraverseN` | `parTraverseNPool` | with failure at index `limit` | pool, same failure |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 117.1 | 3,169.0 | 1,649.6 | 4,009.7 |
| 64 | 150.6 | 2,416.2 | 1,512.1 | 2,690.4 |

The catch is fiber granularity. Elements share worker fibers, so `IOLocal` writes leak between elements
handled by the same worker, an element that self-cancels takes its worker down rather than one fiber, and a
fiber dump shows `limit` workers instead of one fiber per element. Fiber-per-element is what makes
`parTraverseN`'s semantics uniform with `parTraverse`, which is the likely reason cats-effect keeps it, and
why this would land upstream as a new combinator rather than a swap.

`SemaphoreIsolationBench` then pins the fiber count on both sides and has each fiber run 100 acquire and
release cycles around an empty body. No traversal combinator, same number of fibers, same work.
`MiniSemaphore` is the `private[kernel]` cut-down semaphore that actually backs `parTraverseN`, reached
through a shim in the `cats.effect.kernel` package. The `Meter` column is non-reentrant, matching the
cats-effect semaphores; Kyo's default reentrancy tracking costs it a further 30-55% (for example 8,327
against 11,904 at 8 fibers and 1 permit).

| fibers | permits | `Semaphore` | `MiniSemaphore` | `Meter` (non-reentrant) | `Meter` over mini |
| ---: | ---: | ---: | ---: | ---: | --- |
| 8 | 1 | 750.2 | 1,108.9 | 11,903.9 | 10.7x |
| 8 | 8 | 3,608.9 | 3,854.2 | 11,878.5 | 3.1x |
| 64 | 1 | 90.1 | 130.5 | 1,758.7 | 13.5x |
| 64 | 8 | 199.6 | 261.6 | 2,487.7 | 9.5x |

Even with the traversal removed, the fiber count matched, and reentrancy matched, `Meter` beats both
cats-effect semaphores by 3x to 13x, and the gap grows with contention. `MiniSemaphore` runs 1.3x to 1.5x
ahead of the full `Semaphore` (it skips the weighted `acquireN` bookkeeping) but sits on the same
`Ref` + immutable queue + `Deferred` structure, so it degrades the same way. The uncontended single fiber
case in `PrimitivesBench` is only 1.9x, so the gap is contention handling rather than the cost of one
acquire.

## The #4648 edge cases

[PR 4648](https://github.com/typelevel/cats-effect/pull/4648) made `Semaphore` and `MiniSemaphore`
cancelation O(1) by leaving canceled waiters in the queue for a later release to sweep. It shipped in 3.7.1,
which is what these numbers are.

The historical failure-path measurements are consistent with an improvement: `parTraverseNWithFailure`
runs at 4,366 ops/s at n = 1000 and 608 at n = 10000. These short runs do not establish asymptotic
complexity or equal completed work across runtimes.

`massCancelWaiters` does not follow. It goes from 720 ops/s at n = 1000 to 6.78 at n = 10000, about a
hundredfold for ten times the waiters. Kyo's `Meter` equivalent goes 1,362 to 97.9 over the same range,
close to linear. This benchmark cancels every waiter sequentially rather than letting a failed traversal do
it, so the difference may be in the fiber cancelation path rather than in the semaphore. Worth checking at
n = 100000 before drawing a conclusion, and the n = 1000 numbers came from an earlier single iteration pass,
so the two points are not from the same run config.

The release-sweep result was withdrawn because it combined setup, cancellation, and release, lacked
queue-state guarantees, and had incompatible cancellation completion. Its CE samples varied by more
than 10x. The removed benchmark's raw JSON is archived in `results/archive/sweep.json`; the original
combined log remains in `results/run10.log`.

## Fairness rounds

Round one: the first pass paired `io.unsafeRunSync()` with `Sync.Unsafe.evalOrThrow`, giving Kyo a 32 us per
op head start (`RunnerOverheadBench`: 30,887 ops/s for the cats-effect runner and the Kyo fiber-block
runner alike, 33.9M for the calling-thread eval). Re-running with symmetric runners flipped three rows to
cats-effect (deep bind chain, `Kleisli`, single-fiber `Ref`) and shrank most interpreter gaps. cats-effect's
default tracing costs a further 25-40% in the core loops (`ceBindDeep` 4,583 default against 6,489 with
`tracing.mode=none`); the tables keep the default since that is what users run.

Round two, after an audit of every pair:

- `mapN` had been paired with the parallel `Async.zip`. Split into two honest pairs; both are now near even
  (sequential 28,038 against 28,469, parallel 22,760 against 26,886).
- `kyoRace` had been racing a real 1-hour timer against `IO.never`. With `Async.never` the number barely
  moved, so the timer registration was cheap.
- `Ref` had been compared against a raw `AtomicInteger` fetch-add. Against the honest mirror
  (`AtomicRef.updateAndGet`, a CAS loop over a closure) the single-fiber row flips to cats-effect 1.28x and
  the contended row stays Kyo 1.41x.
- fs2 sources were re-chunked to match Kyo's 4096 (`Stream.iterate` emits singleton chunks; that alone had
  inflated the early-termination gap, which dropped from 7.4x to 5.9x).
- `ceMerge` moved from a pairwise `merge` tree to the idiomatic `parJoin` (2,722 to 2,976, Kyo still 1.9x),
  and `kyoBroadcast` now consumes both branches concurrently (unchanged, Kyo 2.0x).
- The CE transformer chains (`Kleisli`, `StateT`, `WriterT`) were rebuilt as recursion to match the Kyo
  loops. `ceKleisli` got slower, so its earlier win had been a construction artifact; the row is Kyo 1.9x.
- `combineAll`'s `IO` values had been pre-allocated in `@Setup` while Kyo built suspensions at runtime;
  building them in the measured op costs cats-effect about 20%.

Those source-chunk corrections left the streaming ratios largely intact. The later audit found that
output chunking, buffering, and producer strategies still differ; see the qualifications above.

## Reading these with care

The two runtimes still are not doing identical work in a few benchmarks:

- `Fiber.interrupt` in Kyo does not wait for the fiber to finish while `Fiber.cancel` in cats-effect does.
  Awaiting Kyo's fiber result after interrupt still does not await finalizer completion. The cancellation
  rows therefore cannot support relative completed-work performance claims.
- `Meter` has no standalone acquire, so every Kyo acquire is paired with its release by construction.
- `Async.foreach` always takes a concurrency limit, so the unbounded `parTraverse` comparison passes the
  collection size as the limit.
- `Async.gather` and `parTraverse(_.attempt)` return the same successes for the benchmark input, but
  differ when every task fails and use different collection algorithms.
- fs2 `map` is pure and Kyo `Stream.map` takes an effectful function, so the pure pipeline uses `mapPure`
  and `filterPure` on the Kyo side. `ceQueuePipeline` pays an `Option` wrapper per element for its
  None-termination idiom.
- Kyo 1.0.0-RC6 is a release candidate. cats-effect 3.7.1 has years of tuning behind it, and spends cycles
  on fairness yielding, backpressured cancelation and tracing that these benchmarks do not reward.

None of this measures semantics, ecosystem, laws, or behavior under a real workload. It measures throughput
on microbenchmarks, on one machine, on one afternoon.
