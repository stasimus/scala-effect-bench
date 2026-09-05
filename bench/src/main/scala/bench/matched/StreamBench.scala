package bench.matched

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Chunk as FChunk, Stream as FStream}
import kyo.*
import org.openjdk.jmh.annotations.*

class StreamBench extends MatchedBase:
    @Param(Array("10000")) var size: Int = 0
    @Param(Array("64")) var chunkSize: Int = 0
    @Param(Array("4")) var parallelism: Int = 0
    @Param(Array("64")) var capacity: Int = 0
    @Param(Array("0", "64")) var work: Int = 0
    var batches: Vector[Vector[Int]] = Vector.empty
    @Setup def setup(): Unit =
        require(chunkSize > 0 && capacity > 0 && (capacity & (capacity - 1)) == 0)
        batches = Vector.tabulate(size)(identity).grouped(chunkSize).toVector

    def ceSource: FStream[IO, Int] =
        FStream.emits(batches).covary[IO].flatMap(batch => FStream.chunk(FChunk.from(batch)))
    def kyoSource: Stream[Int, Any] =
        Stream.init(batches, chunkSize = 1).mapChunkPure(_.flatten)

    // Both evaluate one whole chunk sequentially, then emit it before processing the next.
    def ceEvalStream: FStream[IO, Int] =
        ceSource.chunks.evalMap(_.toVector.traverse(i => IO(Work(i, work)))).flatMap(FStream.emits)
    def kyoEvalStream: Stream[Int, Sync] =
        kyoSource.mapChunk(batch => Kyo.foreach(batch.toVector)(i => Sync.defer(Work(i, work))).map(_.toVector))
    @Benchmark def ceEvalChunks(): Long = ce(ceEvalStream.compile.fold(0L)(_ + _.toLong))
    @Benchmark def kyoEvalChunks(): Long = ky(kyoEvalStream.foldPure(0L)(_ + _.toLong))

    // Same fixed workers per batch, indexed result array, ordered output and batch barrier.
    def ceParallelStream: FStream[IO, Int] =
        ceSource.chunks.evalMap(batch => Workers.ce(batch.toVector, parallelism)(i => IO(Work(i, work))))
            .flatMap(FStream.emits)
    def kyoParallelStream: Stream[Int, Async & Abort[Throwable]] =
        kyoSource.mapChunk(batch => Workers.ky(batch.toVector, parallelism)(i => Sync.defer(Work(i, work))))
    @Benchmark def ceParallelChunks(): Long = ce(ceParallelStream.compile.fold(0L)(_ + _.toLong))
    @Benchmark def kyoParallelChunks(): Long = ky(kyoParallelStream.foldPure(0L)(_ + _.toLong))

    // Same prebuilt Vector chunks and capacity in chunks. The producer loop is direct on both
    // sides. Consumers take exactly batches.size entries, emit each as a chunk and transform it.
    @Benchmark def ceQueueChunks(): Long = ce {
        Queue.bounded[IO, Vector[Int]](capacity).flatMap { q =>
            def produce(i: Int): IO[Unit] =
                if i == batches.size then IO.unit else q.offer(batches(i)).flatMap(_ => produce(i + 1))
            val consume = FStream.unfoldEval(0) { i =>
                if i == batches.size then IO.pure(None)
                else q.take.map(batch => Some((batch, i + 1)))
            }.flatMap(batch => FStream.chunk(FChunk.from(batch)))
                .map(i => Work(i, work)).compile.fold(0L)(_ + _.toLong)
            produce(0).start.flatMap(f => consume.flatMap(sum => f.joinWithNever.map(_ => sum)))
        }
    }
    @Benchmark def kyoQueueChunks(): Long = ky {
        Abort.run[Closed] {
            Channel.initUnscoped[Vector[Int]](capacity).map { q =>
                def produce(i: Int): Unit < (Async & Abort[Closed]) =
                    if i == batches.size then () else q.put(batches(i)).andThen(produce(i + 1))
                val consume = Stream.unfold(0, chunkSize = 1) { i =>
                    if i == batches.size then Maybe.empty[(Vector[Int], Int)]
                    else q.take.map(batch => Maybe((batch, i + 1)))
                }.mapChunkPure(_.flatten).mapPure(i => Work(i, work)).foldPure(0L)(_ + _.toLong)
                Fiber.initUnscoped(produce(0)).map(f => consume.map(sum => f.get.andThen(sum)))
            }
        }.map(_.getOrThrow)
    }
