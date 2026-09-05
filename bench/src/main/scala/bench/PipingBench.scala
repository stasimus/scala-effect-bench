package bench

import cats.effect.IO
import cats.effect.std.Queue as CEQueue
import cats.syntax.all.*
import fs2.Chunk as FChunk
import fs2.Pure
import fs2.Stream as FStream
import kyo.*
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup

class PipingBench extends BaseBench:

    @Param(Array("10000", "100000"))
    var size: Int = 0

    @Param(Array("4", "16"))
    var par: Int = 0

    var vec: Vector[Int]           = Vector.empty
    var fsrc: FStream[Pure, Int]   = FStream.empty
    val fs2ChunkSize               = 4096

    @Setup(Level.Trial)
    def setup(): Unit =
        vec = Vector.tabulate(size)(identity)
        fsrc = vec.grouped(fs2ChunkSize).foldLeft(FStream.empty: FStream[Pure, Int]) { (s, g) =>
            s ++ FStream.chunk(FChunk.from(g))
        }

    private def kyoSource: Stream[Int, Abort[Throwable] & Async] =
        Stream.init(vec)

    @Benchmark def ceLinearPipeline(): Int =
        runCE {
            fsrc.covary[IO]
                .map(_ + 1)
                .filter(_ % 3 != 0)
                .fold(0)(_ + _)
                .compile.lastOrError
        }

    @Benchmark def kyoLinearPipeline(): Int =
        runKyo {
            Stream.init(vec)
                .mapPure(_ + 1)
                .filterPure(_ % 3 != 0)
                .foldPure(0)(_ + _)
        }

    @Benchmark def ceEvalPipeline(): Int =
        runCE {
            fsrc.covary[IO]
                .evalMap(i => IO(i + 1))
                .filter(_ % 3 != 0)
                .compile.foldMonoid
        }

    @Benchmark def kyoEvalPipeline(): Int =
        runKyo {
            Stream.init(vec)
                .map(i => Sync.defer(i + 1))
                .filterPure(_ % 3 != 0)
                .foldPure(0)(_ + _)
        }

    @Benchmark def ceParEvalMap(): Int =
        runCE {
            fsrc.covary[IO]
                .parEvalMap(par)(i => IO(i + 1))
                .compile.foldMonoid
        }

    @Benchmark def kyoMapPar(): Int =
        runKyo {
            Abort.run[Throwable](kyoSource.mapPar(par)(i => Sync.defer(i + 1)).foldPure(0)(_ + _)).map(_.getOrThrow)
        }

    @Benchmark def ceParEvalMapUnordered(): Int =
        runCE {
            fsrc.covary[IO]
                .parEvalMapUnordered(par)(i => IO(i + 1))
                .compile.foldMonoid
        }

    @Benchmark def kyoMapParUnordered(): Int =
        runKyo {
            Abort.run[Throwable](kyoSource.mapParUnordered(par)(i => Sync.defer(i + 1)).foldPure(0)(_ + _)).map(_.getOrThrow)
        }

    @Benchmark def ceMerge(): Int =
        runCE {
            val parts = vec.grouped(math.max(1, size / 4)).map(v => FStream.emits(v).covary[IO]).toList
            FStream.emits(parts).covary[IO].parJoinUnbounded.compile.foldMonoid
        }

    @Benchmark def kyoMerge(): Int =
        runKyo {
            val streams: Seq[Stream[Int, Abort[Throwable] & Async]] =
                vec.grouped(math.max(1, size / 4)).map(v => Stream.init(v.toSeq)).toSeq
            Abort.run[Throwable](Stream.collectAll(streams).foldPure(0)(_ + _)).map(_.getOrThrow)
        }

    @Benchmark def ceBroadcast(): Int =
        runCE {
            fsrc.covary[IO]
                .broadcastThrough[IO, Int](_.map(_ + 1), _.map(_ + 1))
                .compile.foldMonoid
        }

    @Benchmark def kyoBroadcast(): Int =
        runKyo {
            Abort.run[Throwable] {
                Scope.run(kyoSource.broadcast2().map { (a, b) =>
                    Async.zip(a.mapPure(_ + 1).foldPure(0)(_ + _), b.mapPure(_ + 1).foldPure(0)(_ + _)).map(_ + _)
                })
            }.map(_.getOrThrow)
        }

    @Benchmark def ceQueuePipeline(): Int =
        runCE {
            CEQueue.bounded[IO, Option[Int]](64).flatMap { q =>
                val produce = fsrc.covary[IO].map(Some(_)).append(FStream.emit(None)).evalMap(q.offer).compile.drain
                val consume = FStream.fromQueueNoneTerminated(q).map(_ + 1).compile.foldMonoid
                produce.start.flatMap(f => consume.flatMap(r => f.joinWithNever.as(r)))
            }
        }

    @Benchmark def kyoChannelPipeline(): Int =
        runKyo {
            Abort.run[Closed] {
                Channel.initUnscoped[Int](64).map { c =>
                    val produce = Kyo.foreachDiscard(vec)(c.put).andThen(c.closeAwaitEmpty)
                    Fiber.initUnscoped(produce).map { f =>
                        c.streamUntilClosed().mapPure(_ + 1).foldPure(0)(_ + _).map(r => f.getResult.andThen(r))
                    }
                }
            }.map(_.getOrThrow)
        }

    @Benchmark def ceEarlyTermination(): Int =
        runCE {
            FStream.unfoldChunk(0)(i => Some((FChunk.from(i until i + fs2ChunkSize), i + fs2ChunkSize))).covary[IO]
                .evalMap(i => IO(i + 1))
                .take(size.toLong)
                .compile.foldMonoid
        }

    @Benchmark def kyoEarlyTermination(): Int =
        runKyo {
            Stream.range(0, Int.MaxValue)
                .map(i => Sync.defer(i + 1))
                .take(size)
                .foldPure(0)(_ + _)
        }

end PipingBench
