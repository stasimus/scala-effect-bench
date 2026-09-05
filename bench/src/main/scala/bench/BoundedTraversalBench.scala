package bench

import cats.effect.IO
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup

/** Where the parTraverseN gap comes from.
  *
  * `parTraverseN` spawns a fiber per element and gates each one on a MiniSemaphore permit. `Async.foreach` spawns `limit` workers that pull
  * elements by index. `ceWorkerPool` is the second strategy hand rolled on IO, so the difference between it and `ceParTraverseN` is the
  * algorithm and the difference between it and `kyoAsyncForeach` is the runtime.
  */
class BoundedTraversalBench extends BaseBench:

    @Param(Array("4096"))
    var size: Int = 0

    @Param(Array("8", "64"))
    var limit: Int = 0

    var list: List[Int]     = Nil
    var vec: Vector[Int]    = Vector.empty
    var chunk: Chunk[Int]   = Chunk.empty

    @Setup(Level.Trial)
    def setup(): Unit =
        list = List.range(0, size)
        vec = list.toVector
        chunk = Chunk.from(list)

    @Benchmark def ceParTraverseN(): Int =
        runCE(list.parTraverseN(limit)(i => IO(i + 1)).map(_.sum))

    @Benchmark def ceWorkerPool(): Int =
        runCE {
            IO(new AtomicInteger(0)).flatMap { idx =>
                IO(new Array[Int](size)).flatMap { out =>
                    def worker: IO[Unit] =
                        IO(idx.getAndIncrement()).flatMap { i =>
                            if i >= size then IO.unit
                            else IO(i + 1).flatMap(v => IO(out(i) = v)).flatMap(_ => worker)
                        }
                    List.fill(limit)(worker).parSequence_.flatMap(_ => IO(out.sum))
                }
            }
        }

    @Benchmark def cePoolParTraverseN(): Int =
        runCE(PoolParTraverse.parTraverseNPool(limit)(vec)(i => IO(i + 1)).map(_.sum))

    private val boom = new RuntimeException("boom") with scala.util.control.NoStackTrace

    @Benchmark def ceParTraverseNFailure(): Either[Throwable, Int] =
        runCE(list.parTraverseN(limit)(i => if i == limit then IO.raiseError[Int](boom) else IO(i + 1)).map(_.sum).attempt)

    @Benchmark def cePoolParTraverseNFailure(): Either[Throwable, Int] =
        runCE(PoolParTraverse.parTraverseNPool(limit)(vec)(i => if i == limit then IO.raiseError[Int](boom) else IO(i + 1)).map(_.sum).attempt)

    @Benchmark def kyoAsyncForeach(): Int =
        runKyo(Async.foreach(chunk, concurrency = limit)(i => Sync.defer(i + 1)).map(_.sum))

end BoundedTraversalBench
