package bench.matched

import cats.effect.IO
import cats.syntax.all.*
import kyo.*
import org.openjdk.jmh.annotations.*

class ParallelBench extends MatchedBase:
    @Param(Array("4096")) var size: Int = 0
    @Param(Array("8")) var parallelism: Int = 0
    @Param(Array("0", "64")) var work: Int = 0
    var values: Vector[Int] = Vector.empty
    private val boom = new RuntimeException("expected") with scala.util.control.NoStackTrace
    @Setup def setup(): Unit = values = Vector.tabulate(size)(identity)

    @Benchmark def ceWorkers(): Vector[Int] = ce(Workers.ce(values, parallelism)(i => IO(Work(i, work))))
    @Benchmark def kyoWorkers(): Vector[Int] = ky(Workers.ky(values, parallelism)(i => Sync.defer(Work(i, work))))

    // One fiber per element, sequential spawn and join, then the same ordered Option filtering.
    // Failure selection is suspended on both sides; all-failure input returns an empty Vector.
    def ceSuccesses(input: Vector[Int]): IO[Vector[Int]] =
        input.traverse { i =>
            IO.defer(if (i & 1023) == 0 then IO.raiseError[Int](boom) else IO(Work(i, work)))
                .attempt.map(_.toOption).start
        }.flatMap(_.traverse(_.joinWithNever)).map(_.flatten)

    def kyoSuccesses(input: Vector[Int])(using Frame): Vector[Int] < (Async & Abort[Throwable]) =
        Kyo.foreach(input) { i =>
            Fiber.initUnscoped {
                Abort.run[Throwable](Sync.defer {
                    if (i & 1023) == 0 then Abort.fail(boom) else Sync.defer(Work(i, work))
                }).map {
                    case Result.Success(v) => Some(v): Option[Int]
                    case Result.Failure(_) => None: Option[Int]
                    case Result.Panic(_)  => None: Option[Int]
                }
            }
        }.map(fibers => Kyo.foreach(fibers)(_.get).map(_.toVector.flatten))

    @Benchmark def ceCollectSuccesses(): Vector[Int] = ce(ceSuccesses(values))
    @Benchmark def kyoCollectSuccesses(): Vector[Int] = ky(kyoSuccesses(values))
