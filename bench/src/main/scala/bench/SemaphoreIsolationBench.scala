package bench

import cats.effect.IO
import cats.effect.kernel.MiniSemaphoreAccess
import cats.effect.std.Semaphore
import cats.syntax.all.*
import kyo.*
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param

/** Semaphore against Meter with nothing else in the way.
  *
  * Both sides spawn exactly `fibers` fibers, and each fiber runs `OpsPerFiber` acquire and release cycles around an empty body. No traversal
  * combinator is involved, so this measures the permit primitives rather than how each library fans work out.
  */
class SemaphoreIsolationBench extends BaseBench:

    final val OpsPerFiber = 100

    @Param(Array("1", "8"))
    var permits: Int = 0

    @Param(Array("8", "64"))
    var fibers: Int = 0

    @Param(Array("true", "false"))
    var reentrant: Boolean = false

    @Benchmark def ceSemaphore(): Unit =
        runCE {
            Semaphore[IO](permits.toLong).flatMap { sem =>
                def loop(i: Int): IO[Unit] =
                    if i >= OpsPerFiber then IO.unit
                    else sem.permit.use(_ => IO.unit).flatMap(_ => loop(i + 1))
                List.fill(fibers)(loop(0)).parSequence_
            }
        }

    @Benchmark def ceMiniSemaphore(): Unit =
        runCE {
            MiniSemaphoreAccess(permits).flatMap { sem =>
                def loop(i: Int): IO[Unit] =
                    if i >= OpsPerFiber then IO.unit
                    else MiniSemaphoreAccess.withPermit(sem)(IO.unit).flatMap(_ => loop(i + 1))
                List.fill(fibers)(loop(0)).parSequence_
            }
        }

    @Benchmark def kyoMeter(): Unit =
        runKyo {
            Abort.run[Closed] {
                Meter.useSemaphore(permits, reentrant) { meter =>
                    def loop(i: Int): Unit < (Async & Abort[Closed]) =
                        if i >= OpsPerFiber then ()
                        else meter.run(()).andThen(loop(i + 1))
                    Async.foreachDiscard(0 until fibers, concurrency = fibers)(_ => loop(0))
                }
            }.map(_.getOrThrow)
        }

end SemaphoreIsolationBench
