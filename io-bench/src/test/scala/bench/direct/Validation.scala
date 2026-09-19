package bench.direct

import bench.matched.Work
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicIntegerArray}
import scala.util.Try

object Validation:
    private var checks = 0
    private def equal[A](actual: A, expected: A): Unit =
        assert(actual == expected, s"expected $expected, got $actual")
        checks += 1

    def main(args: Array[String]): Unit =
        for runtime <- Vector("ce", "kyo", "loom", "ox", "gears") do
            val parallel = new ParallelBench
            parallel.runtime = runtime
            parallel.setupBackend()
            try
                for size <- Vector(0, 1, 17, 4096); limit <- Vector(1, 8); rounds <- Vector(0, 64) do
                    parallel.size = size
                    parallel.parallelism = limit
                    parallel.work = rounds
                    parallel.setup()
                    equal(parallel.workers(), Vector.tabulate(size)(Work(_, rounds)))
                    equal(parallel.collectSuccesses(), Vector.range(0, size).filter(i => (i & 1023) != 0).map(Work(_, rounds)))
            finally parallel.closeBackend()

            val primitives = new PrimitivesBench
            primitives.runtime = runtime
            primitives.setupBackend()
            try
                for ops <- Vector(0, 1, 17, 1000); capacity <- Vector(1, 64) do
                    primitives.ops = ops
                    primitives.capacity = capacity
                    primitives.setup()
                    val sum = ops.toLong * (ops - 1) / 2
                    equal(primitives.ref(), ops)
                    equal(primitives.promise(), sum)
                    equal(primitives.queue(), sum)
                    equal(primitives.semaphore(), 1)
                    equal(primitives.spawnJoin(), sum)
            finally primitives.closeBackend()

            val pipeline = new PipelineBench
            pipeline.runtime = runtime
            pipeline.setupBackend()
            try
                for size <- Vector(0, 1, 17, 129, 10000); chunk <- Vector(1, 64); rounds <- Vector(0, 64) do
                    pipeline.size = size
                    pipeline.chunkSize = chunk
                    pipeline.parallelism = 4
                    pipeline.capacity = 1
                    pipeline.work = rounds
                    pipeline.setup()
                    val sum = Vector.tabulate(size)(i => Work(i, rounds).toLong).sum
                    equal(pipeline.sequentialChunks(), sum)
                    equal(pipeline.parallelChunks(), sum)
                    equal(pipeline.queueChunks(), sum)
            finally pipeline.closeBackend()

            val runner = new RunnerBench
            runner.runtime = runtime
            runner.setupBackend()
            try equal(runner.entry(), 1) finally runner.closeBackend()
            val sequential = new SequentialBaseline
            sequential.runtime = runtime
            sequential.setupBackend()
            try
                for depth <- Vector(0, 1, 17, 1000, 10000) do
                    sequential.depth = depth
                    sequential.setup()
                    equal(sequential.increment(), depth)
            finally sequential.closeBackend()
            println(s"PASS $runtime workload outputs")

        for name <- Vector("loom", "ox", "gears") do
            val b = Backend(name)
            try
                b.run {
                    equal(Thread.currentThread().isVirtual, true)
                    equal(b.join(b.fork(Thread.currentThread().isVirtual)), true)
                    for size <- Vector(1, 17, 64); limit <- Vector(1, 4, 8) do
                        val count = math.min(size, limit)
                        val gate = new CountDownLatch(count)
                        val visits = new AtomicIntegerArray(size)
                        val active = new AtomicInteger()
                        val peak = new AtomicInteger()
                        val output = Workloads.workers(b)(Vector.range(0, size), limit) { i =>
                            assert(Thread.currentThread().isVirtual)
                            visits.incrementAndGet(i)
                            val n = active.incrementAndGet()
                            peak.accumulateAndGet(n, math.max)
                            gate.countDown()
                            try
                                assert(gate.await(10, TimeUnit.SECONDS), s"$name serialized workers")
                                i
                            finally active.decrementAndGet()
                        }
                        equal(output, Vector.range(0, size))
                        equal(active.get(), 0)
                        equal(peak.get(), count)
                        equal((0 until size).forall(visits.get(_) == 1), true)

                    for input <- Vector(Vector.empty, Vector(0), Vector(0, 1024, 2048), Vector(1, 2, 3)) do
                        val visited = new AtomicInteger()
                        val result = Workloads.successes(b)(input) { i =>
                            visited.incrementAndGet()
                            if (i & 1023) == 0 then throw new IllegalStateException("expected")
                            Work(i, 64)
                        }
                        equal(result, input.filter(i => (i & 1023) != 0).map(Work(_, 64)))
                        equal(visited.get(), input.size)

                    // Producer cannot finish its second send until the first entry is consumed.
                    val q = b.queue[Int](1)
                    val sending = new CountDownLatch(1)
                    val sent = new CountDownLatch(1)
                    val producer = b.fork {
                        b.put(q, 41)
                        sending.countDown()
                        b.put(q, 42)
                        sent.countDown()
                    }
                    assert(sending.await(10, TimeUnit.SECONDS))
                    equal(sent.await(30, TimeUnit.MILLISECONDS), false)
                    equal(b.take(q), 41)
                    equal(b.take(q), 42)
                    b.join(producer)
                    equal(sent.getCount, 0L)

                    val permit = b.permit()
                    val failure = new IllegalStateException("permit body failed")
                    equal(Try(b.withPermit(permit)(throw failure)).failed.get eq failure, true)
                    equal(b.available(permit), 1)
                    val acquired = new CountDownLatch(1)
                    val waiting = new CountDownLatch(1)
                    val entered = new CountDownLatch(1)
                    val release = b.promise[Unit]()
                    val holder = b.fork {
                        b.withPermit(permit) {
                            acquired.countDown()
                            b.await(release)
                        }
                    }
                    assert(acquired.await(10, TimeUnit.SECONDS))
                    val waiter = b.fork {
                        waiting.countDown()
                        b.withPermit(permit)(entered.countDown())
                    }
                    assert(waiting.await(10, TimeUnit.SECONDS))
                    equal(entered.await(30, TimeUnit.MILLISECONDS), false)
                    b.complete(release, ())
                    b.join(holder)
                    b.join(waiter)
                    equal(entered.getCount, 0L)
                    equal(b.available(permit), 1)

                    for size <- Vector(0, 1, 17, 129); chunk <- Vector(1, 64); limit <- Vector(0, 4) do
                        val batches = Vector.range(0, size).grouped(chunk).toVector
                        val expected = batches.map(_.map(Work(_, 64)))
                        val out = Vector.newBuilder[Vector[Int]]
                        val evaluated = new AtomicInteger()
                        var emitted = 0
                        Workloads.chunks(b)(batches, limit) { i =>
                            evaluated.incrementAndGet()
                            Work(i, 64)
                        } { batch =>
                            emitted += batch.size
                            equal(evaluated.get(), emitted)
                            out += batch
                        }
                        equal(out.result(), expected)
                        val queued = Vector.newBuilder[Vector[Int]]
                        Workloads.queueChunks(b)(batches, 1)(batch => { queued += batch; () })
                        equal(queued.result(), batches)
                }
            finally b.close()
            println(s"PASS $name virtual threads, exact-once workers, overlap, ordering, backpressure, permits and batch barriers")
        println(s"PASS $checks five-way synthetic checks")
