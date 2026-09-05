package bench.matched

import cats.effect.IO
import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicInteger
import kyo.*

/** Benchmark-local, successful-work worker pools with identical indexing and result storage.
  * Each starts min(size, parallelism) workers sequentially and joins them sequentially.
  * These helpers deliberately do not claim parTraverseN's error/cancellation semantics.
  */
object Workers:
    def ce(values: Vector[Int], parallelism: Int)(f: Int => IO[Int]): IO[Vector[Int]] = IO.defer {
        require(parallelism > 0)
        val index = new AtomicInteger(0)
        val output = new Array[Int](values.size)
        def worker: IO[Unit] =
            IO(index.getAndIncrement()).flatMap { i =>
                if i >= values.size then IO.unit
                else f(values(i)).flatMap(v => IO(output(i) = v)) *> worker
            }
        Vector.fill(math.min(values.size, parallelism))(worker).traverse(_.start).flatMap { fibers =>
            fibers.traverse_(_.joinWithNever) *> IO(output.toVector)
        }
    }

    def ky(values: Vector[Int], parallelism: Int)(f: Int => Int < (Async & Abort[Throwable]))(using Frame)
        : Vector[Int] < (Async & Abort[Throwable]) = Sync.defer {
        require(parallelism > 0)
        val index = new AtomicInteger(0)
        val output = new Array[Int](values.size)
        def worker: Unit < (Async & Abort[Throwable]) =
            Sync.defer(index.getAndIncrement()).map { i =>
                if i >= values.size then ()
                else f(values(i)).map(v => Sync.defer(output(i) = v)).andThen(worker)
            }
        Kyo.foreach(0 until math.min(values.size, parallelism))(_ => Fiber.initUnscoped(worker)).map { fibers =>
            Kyo.foreachDiscard(fibers)(_.get).andThen(Sync.defer(output.toVector))
        }
    }
