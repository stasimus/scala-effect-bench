package bench.matched

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import java.util.concurrent.atomic.{AtomicInteger, AtomicIntegerArray}
import kyo.*

/** Run with bench/Test/runMain bench.matched.Validation. No checks are inside JMH methods. */
object Validation extends MatchedBase:
    private var checks = 0
    private def equal[A](actual: A, expected: A): Unit =
        assert(actual == expected, s"expected $expected, got $actual")
        checks += 1

    def main(args: Array[String]): Unit =
        val core = new CoreBench
        for depth <- Vector(0, 1, 17, 1000, 10000) do
            core.depth = depth
            Vector(core.ceDeepBind(), core.kyoDeepBind(), core.ceLeftBind(), core.kyoLeftBind(),
                core.ceMapChain(), core.kyoMapChain()).foreach(equal(_, depth))
        println("PASS suspended core chains, including depth 10000")

        val parallel = new ParallelBench
        for size <- Vector(0, 1, 7, 17, 4096); limit <- Vector(1, 8); rounds <- Vector(0, 64) do
            parallel.size = size
            parallel.parallelism = limit
            parallel.work = rounds
            parallel.setup()
            val expected = parallel.values.map(Work(_, rounds))
            equal(parallel.ceWorkers(), expected)
            equal(parallel.kyoWorkers(), expected)
            val successes = parallel.values.filter(i => (i & 1023) != 0).map(Work(_, rounds))
            equal(parallel.ceCollectSuccesses(), successes)
            equal(parallel.kyoCollectSuccesses(), successes)
        for input <- Vector(Vector.empty, Vector(0), Vector(0, 1024, 2048), Vector(1, 2, 3)) do
            val expected = input.filter(i => (i & 1023) != 0).map(Work(_, parallel.work))
            equal(ce(parallel.ceSuccesses(input)), expected)
            equal(ky(parallel.kyoSuccesses(input)), expected)
        println("PASS worker and success-collection outputs, ordering, empty/all-failed inputs")

        for size <- Vector(1, 17, 64); limit <- Vector(1, 4, 8) do
            checkCeWorkers(size, limit)
            checkKyoWorkers(size, limit)
        println("PASS exact-once execution, worker concurrency, and completion")

        val primitives = new PrimitivesBench
        for count <- Vector(0, 1, 17, 1000); capacity <- Vector(1, 64) do
            primitives.ops = count
            primitives.capacity = capacity
            val expected = count.toLong * (count - 1) / 2
            equal(primitives.ceRef(), count)
            equal(primitives.kyoRef(), count)
            equal(primitives.ceDeferred(), expected)
            equal(primitives.kyoPromise(), expected)
            equal(primitives.ceQueue(), expected)
            equal(primitives.kyoQueue(), expected)
            equal(primitives.ceSemaphore(), 1)
            equal(primitives.kyoSemaphore(), 1)
            equal(primitives.ceSpawnJoin(), expected)
            equal(primitives.kyoSpawnJoin(), expected)
        println("PASS primitives, queue backpressure at capacity 1, and returned permits")

        val streams = new StreamBench
        for size <- Vector(0, 1, 17, 129, 10000); chunk <- Vector(1, 64); rounds <- Vector(0, 64) do
            streams.size = size
            streams.chunkSize = chunk
            streams.capacity = 1
            streams.parallelism = 4
            streams.work = rounds
            streams.setup()
            val expected = Vector.tabulate(size)(i => Work(i, rounds))
            val sum = expected.foldLeft(0L)(_ + _.toLong)
            equal(streams.ceEvalChunks(), sum)
            equal(streams.kyoEvalChunks(), sum)
            equal(streams.ceParallelChunks(), sum)
            equal(streams.kyoParallelChunks(), sum)
            equal(streams.ceQueueChunks(), sum)
            equal(streams.kyoQueueChunks(), sum)
            equal(ce(streams.ceEvalStream.compile.toVector), expected)
            equal(ky(streams.kyoEvalStream.run.map(_.toVector)), expected)
            equal(ce(streams.ceParallelStream.compile.toVector), expected)
            equal(ky(streams.kyoParallelStream.run.map(_.toVector)), expected)
            val expectedChunks = expected.grouped(chunk).toVector
            equal(ce(streams.ceParallelStream.chunks.map(_.toVector).compile.toVector), expectedChunks)
            val kyoChunks = ky {
                val chunks = Vector.newBuilder[Vector[Int]]
                streams.kyoParallelStream.foreachChunk(batch => Sync.defer { chunks += batch.toVector; () })
                    .andThen(Sync.defer(chunks.result()))
            }
            equal(kyoChunks, expectedChunks)
        println("PASS stream contents, order, chunk boundaries, partial final batches and capacity 1")
        println(s"PASS $checks checks")

    private def checkCeWorkers(size: Int, limit: Int): Unit =
        val active = new AtomicInteger(0)
        val peak = new AtomicInteger(0)
        val visits = new AtomicIntegerArray(size)
        val count = math.min(size, limit)
        val out = ce {
            Deferred[IO, Unit].flatMap { gate =>
                Workers.ce(Vector.range(0, size), limit) { i =>
                    IO {
                        visits.incrementAndGet(i)
                        val n = active.incrementAndGet()
                        peak.accumulateAndGet(n, (a, b) => math.max(a, b))
                        n
                    }.flatMap(n => (if n == count then gate.complete(()).void else IO.unit) *> gate.get)
                        .as(i).guarantee(IO { active.decrementAndGet(); () })
                }
            }.timeout(scala.concurrent.duration.Duration(10, "seconds"))
        }
        equal(out, Vector.range(0, size))
        equal(active.get(), 0)
        equal(peak.get(), count)
        (0 until size).foreach(i => equal(visits.get(i), 1))

    private def checkKyoWorkers(size: Int, limit: Int): Unit =
        val active = new AtomicInteger(0)
        val peak = new AtomicInteger(0)
        val visits = new AtomicIntegerArray(size)
        val count = math.min(size, limit)
        val out = ky {
            Async.timeout(10.seconds) {
                Latch.init(count).map { gate =>
                    Workers.ky(Vector.range(0, size), limit) { i =>
                        Sync.ensure(Sync.defer { active.decrementAndGet(); () }) {
                            Sync.defer {
                                visits.incrementAndGet(i)
                                val n = active.incrementAndGet()
                                peak.accumulateAndGet(n, (a, b) => math.max(a, b))
                                ()
                            }.andThen(gate.release).andThen(gate.await).andThen(i)
                        }
                    }
                }
            }.handle(Abort.run[Timeout](_)).map(_.getOrThrow)
        }
        equal(out, Vector.range(0, size))
        equal(active.get(), 0)
        equal(peak.get(), count)
        (0 until size).foreach(i => equal(visits.get(i), 1))
