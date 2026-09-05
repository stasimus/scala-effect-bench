package bench

import cats.effect.IO
import cats.effect.std.CountDownLatch
import cats.effect.std.Semaphore
import cats.effect.syntax.all.*
import cats.syntax.all.*
import kyo.*
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.infra.Blackhole

/** Edge cases from typelevel/cats-effect#4648 (O(1) cancelation for Semaphore and MiniSemaphore, in 3.7.1 but not 3.7.0).
  *
  * Cancelation no longer filters the waiter queue: canceled waiters linger and are swept lazily by releases. These benchmarks measure both
  * sides of that trade: the cancelation path (should now scale linearly in `n`) and the release path that pays for the sweep.
  */
class SemaphoreCancelBench extends BaseBench:

    @Param(Array("1000", "10000", "100000"))
    var n: Int = 0

    @Param(Array("8", "64"))
    var limit: Int = 0

    private val boom = new RuntimeException("boom") with scala.util.control.NoStackTrace

    // 1. The issue #4434 shape: bounded parallel traversal aborted by an early failure,
    //    which cancels every waiter still queued on the semaphore.

    @Benchmark def ceParTraverseNWithFailure(): Either[Throwable, List[Int]] =
        runCE {
            List.range(0, n).parTraverseN(limit) { i =>
                if i == limit then IO.raiseError[Int](boom) else IO(i)
            }.attempt
        }

    @Benchmark def kyoForeachConcurrencyWithFailure(): Result[Throwable, Chunk[Int]] =
        runKyo {
            Abort.run[Throwable] {
                Async.foreach(0 until n, concurrency = limit) { i =>
                    if i == limit then Abort.fail(boom) else Sync.defer(i)
                }
            }
        }

    // 2. Mass cancelation isolated from task cost: fill the waiter queue, cancel all of it.

    @Benchmark def ceMassCancelWaiters(): Long =
        runCE {
            (Semaphore[IO](1), CountDownLatch[IO](n)).tupled.flatMap { (sem, arrived) =>
                sem.acquire.flatMap { _ =>
                    List.range(0, n).traverse(_ => (arrived.release *> sem.acquire).start).flatMap { fibers =>
                        arrived.await *>
                            fibers.traverse_(_.cancel) *>
                            sem.release *>
                            sem.count
                    }
                }
            }
        }

    @Benchmark def kyoMassCancelWaiters(): Int =
        runKyo {
            Abort.run[Closed] {
                for
                    meter   <- Meter.initSemaphoreUnscoped(1)
                    arrived <- Latch.init(n)
                    gate    <- Latch.init(1)
                    holder  <- Fiber.initUnscoped(meter.run(gate.await))
                    waiters <- Kyo.foreach(0 until n)(_ => Fiber.initUnscoped(arrived.release.andThen(meter.run(()))))
                    _       <- arrived.await
                    _       <- Kyo.foreachDiscard(waiters)(f => f.interrupt.andThen(f.getResult))
                    _       <- gate.release
                    _       <- holder.getResult
                    permits <- meter.availablePermits
                yield permits
            }.map(_.getOrThrow)
        }

    // 4. Contended common path, so a regression there shows up next to the improved cancel path.

    @Benchmark def ceAcquireReleaseContended(): Unit =
        runCE {
            Semaphore[IO](limit).flatMap { sem =>
                List.range(0, n).parTraverseN(limit * 4)(_ => sem.permit.use(_ => IO.unit)).void
            }
        }

    @Benchmark def kyoAcquireReleaseContended(): Unit =
        runKyo {
            Abort.run[Closed] {
                Meter.useSemaphore(limit) { meter =>
                    Async.foreachDiscard(0 until n, concurrency = limit * 4)(_ => meter.run(()))
                }
            }.map(_.getOrThrow)
        }

    // 5. MiniSemaphore path: the same PR changed what backs parTraverseN on the happy path.

    @Benchmark def ceParTraverseNHappy(): List[Int] =
        runCE(List.range(0, n).parTraverseN(limit)(i => IO(i)))

    @Benchmark def kyoForeachConcurrencyHappy(): Chunk[Int] =
        runKyo(Async.foreach(0 until n, concurrency = limit)(i => Sync.defer(i)))

    // 6. Observed permit count while canceled waiters are still queued. Post-#4648 the CE
    //    count transiently includes them; recorded rather than asserted.

    @Benchmark def cePermitCountUnderCancel(bh: Blackhole): Unit =
        val count = runCE {
            (Semaphore[IO](1), CountDownLatch[IO](n)).tupled.flatMap { (sem, arrived) =>
                sem.acquire.flatMap { _ =>
                    List.range(0, n).traverse(_ => (arrived.release *> sem.acquire).start).flatMap { fibers =>
                        arrived.await *>
                            fibers.traverse_(_.cancel) *>
                            sem.count.flatTap(_ => sem.release)
                    }
                }
            }
        }
        bh.consume(count)
    end cePermitCountUnderCancel

    @Benchmark def kyoPermitCountUnderCancel(bh: Blackhole): Unit =
        val count = runKyo {
            Abort.run[Closed] {
                for
                    meter   <- Meter.initSemaphoreUnscoped(1)
                    arrived <- Latch.init(n)
                    gate    <- Latch.init(1)
                    holder  <- Fiber.initUnscoped(meter.run(gate.await))
                    waiters <- Kyo.foreach(0 until n)(_ => Fiber.initUnscoped(arrived.release.andThen(meter.run(()))))
                    _       <- arrived.await
                    _       <- Kyo.foreachDiscard(waiters)(f => f.interrupt.andThen(f.getResult))
                    permits <- meter.availablePermits
                    _       <- gate.release
                    _       <- holder.getResult
                yield permits
            }.map(_.getOrThrow)
        }
        bh.consume(count)
    end kyoPermitCountUnderCancel

end SemaphoreCancelBench
