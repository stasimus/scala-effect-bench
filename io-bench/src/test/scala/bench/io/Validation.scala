package bench.io

import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicIntegerArray}
import scala.util.Try

object Validation:
    def main(args: Array[String]): Unit =
        val runtimes = if args.nonEmpty then args.toVector else Vector("ce", "kyo", "gears", "ox", "ceVirtual", "kyoFlush")
        var checks = 0
        for runtime <- runtimes do
            val transports = if Set("ceVirtual", "kyoFlush", "kyoTuned")(runtime) then Vector("blocking")
                else Vector("blocking", "nonblocking")
            for transport <- transports; parallelism <- Vector(1, 4, 8); delay <- Vector(0, 1000) do
                val bench = new IoBench
                bench.runtime = runtime
                bench.transport = transport
                bench.parallelism = parallelism
                bench.delayMicros = delay
                bench.size = 256
                bench.setup()
                try
                    for size <- Vector(0, 1, 17, 256) do
                        bench.size = size
                        val seen = new AtomicIntegerArray(size)
                        val active = new AtomicInteger()
                        val peak = new AtomicInteger()
                        val virtual = new AtomicInteger()
                        val observed = new Exchange:
                            def enter(lane: Int, index: Int): Unit =
                                assert(index % parallelism == lane)
                                assert(seen.incrementAndGet(index) == 1)
                                if Thread.currentThread().isVirtual then virtual.incrementAndGet()
                                val n = active.incrementAndGet()
                                peak.accumulateAndGet(n, math.max)
                            def blocking(lane: Int, value: Int): Int =
                                enter(lane, value)
                                try bench.exchange.blocking(lane, value)
                                finally active.decrementAndGet()
                            def async(lane: Int, value: Int)(complete: Either[Throwable, Int] => Unit): () => Unit =
                                enter(lane, value)
                                bench.exchange.async(lane, value) { result =>
                                    active.decrementAndGet()
                                    complete(result)
                                }
                            def close(): Unit = ()
                        assert(bench.batch(observed) == Vector.tabulate(size)(_ + 1), (runtime, transport, size))
                        assert(active.get() == 0 && peak.get() <= math.min(size, parallelism))
                        assert((0 until size).forall(i => seen.get(i) == 1))
                        val expectedVirtual = if Set("loom", "gears", "ox", "ceVirtual")(runtime) then size else 0
                        assert(virtual.get() == expectedVirtual, s"Unexpected threads: $runtime $transport ${virtual.get()}/$size")
                        checks += 1

                    bench.size = 17
                    val completed = new AtomicInteger()
                    val failure = new IllegalStateException("injected worker failure")
                    val failing = new Exchange:
                        def blocking(lane: Int, value: Int): Int =
                            if value == 0 then throw failure
                            val result = bench.exchange.blocking(lane, value)
                            completed.incrementAndGet()
                            result
                        def async(lane: Int, value: Int)(complete: Either[Throwable, Int] => Unit): () => Unit =
                            if value == 0 then
                                complete(Left(failure))
                                () => ()
                            else bench.exchange.async(lane, value) { result =>
                                completed.incrementAndGet()
                                complete(result)
                            }
                        def close(): Unit = ()
                    val result = Try(bench.batch(failing))
                    assert(result.failed.get eq failure, s"Lost failure: $runtime $transport $result")
                    assert(completed.get() == (0 until 17).count(_ % parallelism != 0), "Returned before joining healthy workers")
                    assert(bench.requests() == Vector.tabulate(17)(_ + 1), "Fixture unusable after worker failure")
                    checks += 2
                finally bench.teardown()

            // A gate verifies actual parallel worker execution, not just an upper bound.
            val bench = new IoBench
            bench.runtime = runtime
            bench.transport = "blocking"
            bench.parallelism = 4
            bench.delayMicros = 0
            bench.size = 4
            bench.setup()
            try
                val gate = new CountDownLatch(4)
                val gated = new Exchange:
                    def blocking(lane: Int, value: Int): Int =
                        gate.countDown()
                        assert(gate.await(10, TimeUnit.SECONDS), s"Workers serialized: $runtime")
                        value + 1
                    def async(lane: Int, value: Int)(complete: Either[Throwable, Int] => Unit): () => Unit =
                        throw new UnsupportedOperationException()
                    def close(): Unit = ()
                assert(bench.batch(gated) == Vector(1, 2, 3, 4))
                checks += 1
            finally bench.teardown()

        val fixture = new TcpFixture("nonblocking", 1, 500000)
        try
            val completed = new CompletableFuture[Either[Throwable, Int]]()
            val count = new AtomicInteger()
            val cancel = fixture.async(0, 1) { value =>
                count.incrementAndGet()
                completed.complete(value)
                ()
            }
            cancel()
            assert(completed.get(10, TimeUnit.SECONDS).isLeft)
            assert(count.get() == 1, "Cancellation completed more than once")
            checks += 1
        finally fixture.close()
        MeasuredIoValidation.main(args)
        val label = if runtimes.contains("loom") then "I/O" else "four-library I/O"
        println(s"PASS $checks $label checks (${runtimes.mkString(",")})")
