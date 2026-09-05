package bench

import cats.effect.IO
import cats.effect.syntax.all.*
import cats.syntax.all.*
import kyo.*
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup

class AlgebraBench extends BaseBench:

    @Param(Array("128", "4096", "65536"))
    var size: Int = 0

    var vec: Vector[Int]  = Vector.empty
    var chunk: Chunk[Int] = Chunk.empty

    @Setup(Level.Trial)
    def setup(): Unit =
        vec = Vector.tabulate(size)(identity)
        chunk = Chunk.from(vec)

    @Benchmark def ceMonoidCombineAll(): Int =
        runCE(vec.map(i => IO(i)).combineAll)

    @Benchmark def kyoMonoidCombineAll(): Int =
        runKyo(Kyo.foldLeft(chunk)(0)((acc, i) => Sync.defer(acc + i)))

    @Benchmark def ceFoldMapM(): Int =
        runCE(vec.foldMapM(i => IO(i)))

    @Benchmark def kyoFoldMap(): Int =
        runKyo(Kyo.foreach(chunk)(i => Sync.defer(i)).map(_.foldLeft(0)(_ + _)))

    @Benchmark def ceTraverse(): Vector[Int] =
        runCE(vec.traverse(i => IO(i + 1)))

    @Benchmark def kyoForeach(): Chunk[Int] =
        runKyo(Kyo.foreach(chunk)(i => Sync.defer(i + 1)))

    @Benchmark def ceMapN(): Int =
        runCE((IO(1), IO(2), IO(3)).mapN(_ + _ + _))

    @Benchmark def kyoMapSequential(): Int =
        runKyo(Sync.defer(1).map(a => Sync.defer(2).map(b => Sync.defer(3).map(c => a + b + c))))

    @Benchmark def ceParMapN(): Int =
        runCE((IO(1), IO(2), IO(3)).parMapN(_ + _ + _))

    @Benchmark def kyoZip(): Int =
        runKyo(Async.zip(Sync.defer(1), Sync.defer(2), Sync.defer(3)).map(_ + _ + _))

    @Benchmark def ceParTraverse(): Vector[Int] =
        runCE(vec.parTraverse(i => IO(i + 1)))

    @Benchmark def kyoAsyncForeach(): Chunk[Int] =
        runKyo(Async.foreach(chunk, concurrency = size)(i => Sync.defer(i + 1)))

    @Benchmark def ceParTraverseBounded(): Vector[Int] =
        runCE(vec.parTraverseN(8)(i => IO(i + 1)))

    @Benchmark def kyoAsyncForeachBounded(): Chunk[Int] =
        runKyo(Async.foreach(chunk, concurrency = 8)(i => Sync.defer(i + 1)))

    private val boom = new RuntimeException("boom") with scala.util.control.NoStackTrace

    private def ceMaybeFail(i: Int): IO[Int] =
        if (i & 1023) == 0 then IO.raiseError(boom) else IO(i)

    private def kyoMaybeFail(i: Int): Int < (Sync & Abort[Throwable]) =
        if (i & 1023) == 0 then Abort.fail(boom) else Sync.defer(i)

    @Benchmark def ceAttemptAccumulate(): (Vector[Throwable], Vector[Int]) =
        runCE(vec.parTraverse(i => ceMaybeFail(i).attempt).map(_.partitionMap(identity)))

    @Benchmark def kyoAbortAccumulate(): (Chunk[Throwable], Chunk[Int]) =
        runKyo {
            Async.foreach(chunk, concurrency = size)(i => Abort.run(kyoMaybeFail(i))).map { results =>
                val ok  = results.collect { case Result.Success(v) => v }
                val err = results.collect { case Result.Failure(e) => e }
                (err, ok)
            }
        }

    @Benchmark def ceGatherSuccesses(): Vector[Int] =
        runCE(vec.parTraverse(i => ceMaybeFail(i).attempt).map(_.collect { case Right(v) => v }))

    @Benchmark def kyoGatherSuccesses(): Chunk[Int] =
        runKyo(Async.gather(chunk.map(i => kyoMaybeFail(i))))

end AlgebraBench
