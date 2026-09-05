package bench

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.Stream as FStream
import kyo.*
import org.openjdk.jmh.annotations.{Benchmark, Setup}

/** Diagnostic variants only; size = 10000, parallelism = 4, capacity = 64. */
class AuditPipingBench extends BaseBench:
    private val original = new PipingBench

    @Setup def setup(): Unit =
        original.size = 10000
        original.par = 4
        original.setup()

    @Benchmark def ceQueueOriginal(): Int = original.ceQueuePipeline()
    @Benchmark def kyoQueueOriginal(): Int = original.kyoChannelPipeline()
    @Benchmark def ceParOriginal(): Int = original.ceParEvalMap()
    @Benchmark def kyoParOriginal(): Int = original.kyoMapPar()

    @Benchmark def ceQueueDirectProducer(): Int =
        runCE {
            Queue.bounded[IO, Option[Int]](64).flatMap { q =>
                val produce = original.vec.traverse_(i => q.offer(Some(i))) *> q.offer(None)
                val consume = FStream.fromQueueNoneTerminated(q).map(_ + 1).compile.foldMonoid
                produce.start.flatMap(f => consume.flatMap(r => f.joinWithNever.as(r)))
            }
        }

    @Benchmark def kyoParSingletonChunks(): Int =
        runKyo {
            val source: Stream[Int, Abort[Throwable] & Async] = Stream.init(original.vec, chunkSize = 1)
            source.mapPar(4)(i => Sync.defer(i + 1)).foldPure(0)(_ + _)
        }
