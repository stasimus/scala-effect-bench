# scala-effect-bench

JMH benchmarks comparing [Kyo](https://getkyo.io) 1.0.0-RC6 with [cats-effect](https://typelevel.org/cats-effect/)
3.7.1 on Scala 3.8.4. Every benchmark exists as a pair: `ce*` runs on `IO`, `kyo*` runs the closest Kyo
equivalent, so the JMH output lines up side by side.

## Running

```
sbt "bench/Jmh/run -i 5 -wi 5 -f1 .*AlgebraBench.*"
sbt "bench/Jmh/run -p n=10000 -p limit=8 .*SemaphoreCancelBench.*"
sbt "bench/Jmh/run -prof gc .*PipingBench.*"
sbt "bench/Jmh/run -rf json -rff results/all.json .*"
```

The default JMH config is 5 warmup and 5 measurement iterations in a single fork, throughput mode. Most
classes carry `@Param` values that multiply out quickly, so pass `-p` to pin them when you only care about
one size.

## What is measured

`AlgebraBench` pairs cats typeclasses with the Kyo combinator that does the same job. Kyo has no typeclass
hierarchy, so the mapping is by role:

| cats / cats-effect | Kyo |
| --- | --- |
| `Monoid[IO[Int]]` via `combineAll` | `Kyo.foldLeft` |
| `Foldable.foldMapM` | `Kyo.foreach` + fold |
| `Traverse.traverse` | `Kyo.foreach` |
| `Semigroupal.mapN` | sequential Kyo maps |
| `Parallel.parMapN` | `Async.zip` |
| `Parallel.parTraverse` | `Async.foreach(concurrency = size)` |
| `parTraverseN` | `Async.foreach(concurrency = n)` |
| `MonadError.attempt` + partition | `Abort.run` + `Result` partition |
| unbounded success collection | `Async.gather` |

`ContextBench` covers the transformer-shaped abstractions: `Kleisli` against `Env`, `StateT` against `Var`,
`WriterT` with a `Chain` against `Emit`, `Resource` against `Scope`, and the error paths
(`handleErrorWith`, `orElse`) against `Abort.recover` and `Abort.fold`.

`CoreLoopBench` compares deep binds, left-associated binds, long `map` chains, raise and handle round
trips, and pure binds. Pure Kyo maps can execute eagerly on plain values, while IO constructs a computation;
those rows compare representations rather than equal interpreter instruction counts.

`PrimitivesBench` covers `Ref`/`AtomicRef`, `Deferred`/`Promise`, `Queue`/`Channel`, `Semaphore`/`Meter`,
race, timeout, and fiber spawn and join, both uncontended and with several producers.

`BoundedTraversalBench` and `SemaphoreIsolationBench` take the `parTraverseN` gap apart. The first adds a
worker pool hand rolled on `IO` next to `parTraverseN` and `Async.foreach`, to test the cost of the traversal
strategy. Remaining differences include runtime and combinator implementation costs. The second pins the fiber count on both sides and runs plain acquire and release cycles,
which is `Semaphore` and the package-private `MiniSemaphore` (the one `parTraverseN` actually uses, reached
through a shim in the `cats.effect.kernel` package) against `Meter` with nothing else in the way. `RunnerOverheadBench` measures what the
two run helpers cost.

`PipingBench` runs fs2 against Kyo's `Stream`: a pure pipeline, an effectful one, `parEvalMap` against
`mapPar`, the unordered variants, merge, broadcast, a queue-backed producer and consumer, and early
termination of an infinite source.

## The cats-effect #4648 edge cases

[typelevel/cats-effect#4648](https://github.com/typelevel/cats-effect/pull/4648) landed in 3.7.1 and changed
how `Semaphore` and `MiniSemaphore` handle cancelation. Cancelation no longer filters the waiter queue,
which used to be O(n) under a CAS loop and O(n²) when a whole batch was canceled at once. A canceled waiter
now races its gate and gets swept lazily by a later release. The PR names three consequences: canceled
waiters stay in the queue until a release reaches them, `count` can transiently include them, and each dead
waiter costs one extra release pass.

`SemaphoreCancelBench` explores cancelation and contention. Its cancelation comparisons remain provisional:
the arrival latch does not establish queue occupancy, and Kyo result completion does not establish that
finalizers have finished. See [the audit](audit.md).

- `parTraverseNWithFailure` is the shape from issue #4434. A bounded traversal fails at index `limit`, which
  cancels every waiter still queued. Run it across `n = 1000/10000/100000` and the cost should scale roughly
  linearly now instead of quadratically.
- `massCancelWaiters` starts `n` would-be waiters and cancels them, with no task work in the way.
- `acquireReleaseContended` and `parTraverseNHappy` are the common paths, kept next to the cancel benchmarks
  so a regression there is visible.
- `permitCountUnderCancel` reads the permit count while dead waiters are still queued. The value is consumed
  by a blackhole rather than asserted, since the transient over-reporting is expected behavior after the PR.

The release-sweep benchmark was removed after the audit: it timed the full waiter lifecycle, had
unmatched cancellation completion, and did not establish the intended queue state.

## Caveats when reading the numbers

Both `runCE` and `runKyo` go through their runtime once per op and measured about 32 us in the original
environment. This matches empty-runner overhead, not all execution semantics. `runKyoSync` runs on the calling thread and is a thousand times cheaper, so it must
not be paired with `runCE`. `RunnerOverheadBench` measures all of them. Beyond that, the two runtimes are
not doing identical work, and a few differences matter:

- `Fiber.interrupt` in Kyo does not wait for the fiber to finish, while `Fiber.cancel` in cats-effect is
  backpressured. Awaiting Kyo's `getResult` after interrupt still does not await finalizer completion;
  the audit reproduced this. Cancellation comparisons need explicit cleanup acknowledgments.
- Kyo's `Meter` and cats-effect's `Semaphore` differ in API shape. `Meter` only exposes `run`, so every
  acquire is paired with its release, and there is no standalone `acquire` to leave dangling.
- `Async.foreach` takes an explicit concurrency limit, so the unbounded `parTraverse` comparison passes the
  collection size as the limit.
- fs2's `map` is pure while Kyo's `Stream.map` takes an effectful function, so the pure pipeline uses
  `mapPure` and `filterPure` on the Kyo side. The parallel-map sources use 4096-element chunks, but Kyo
  preserves output chunks while fs2 coordinates individual elements; buffers also differ. `mapN` pairs
  with a sequential Kyo chain and `parMapN` with `Async.zip`, `Ref` pairs with `AtomicRef` (CAS loop, not
  fetch-add), and races lose against `never` on both sides; see the fairness rounds in `result.md`.
- Kyo runs on its own scheduler and cats-effect on the `IORuntime` work-stealing pool. Neither is tuned here
  beyond defaults.

The [audit](audit.md) includes correctness checks, allocation measurements, streaming controls, and
reproduction commands. Streaming results describe complete pipelines; the queue pair also uses different
producer traversal and consumer draining strategies.

## Layout

```
build.sbt
project/plugins.sbt              sbt-jmh 0.4.8 (sbt is pinned to 1.13.0, sbt-jmh has no sbt 2 build)
bench/src/main/scala/bench/
  BaseBench.scala                shared JMH config and the two run helpers
  AlgebraBench.scala
  ContextBench.scala
  CoreLoopBench.scala
  PrimitivesBench.scala
  PipingBench.scala
  SemaphoreCancelBench.scala
  BoundedTraversalBench.scala
  SemaphoreIsolationBench.scala
  RunnerOverheadBench.scala
```

fs2 3.13.0 depends on cats-effect 3.7.0, which is evicted by the explicit 3.7.1 dependency. That eviction is
what puts the #4648 change on the classpath, so check `sbt evicted` after any dependency bump.
