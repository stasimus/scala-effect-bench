package bench.blocking

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicIntegerArray}

/** Fork this main so the server inherits the complete dependency classpath. */
object Validation:
    private var checks = 0
    private def check(condition: Boolean, clue: => String): Unit =
        assert(condition, clue)
        checks += 1

    def main(args: Array[String]): Unit =
        for runtime <- Vector("ce", "ceVirtual", "kyo"); limit <- Vector(1, 8, 64) do
            val bench = new BlockingBench
            bench.runtime = runtime
            bench.parallelism = limit
            bench.size = 256
            bench.delayMicros = 1000
            bench.setup()
            try
                for size <- Vector(0, 1, 17, 256) do
                    bench.size = size
                    check(bench.requests() == Vector.tabulate(size)(_ + 1), s"$runtime/$limit/$size socket results")
                bench.size = 129
                val visits = new AtomicIntegerArray(bench.size)
                val active = new AtomicInteger()
                val peak = new AtomicInteger()
                val wrongThread = new AtomicInteger()
                val gate = new CountDownLatch(limit)
                val output = bench.batch { (lane, index) =>
                    checkLane(lane, index, limit)
                    visits.incrementAndGet(index)
                    if Thread.currentThread().isVirtual != (runtime == "ceVirtual") then wrongThread.incrementAndGet()
                    val n = active.incrementAndGet()
                    peak.accumulateAndGet(n, (a, b) => math.max(a, b))
                    try
                        if index < limit then
                            gate.countDown()
                            assert(gate.await(15, TimeUnit.SECONDS), s"$runtime failed to reach $limit concurrent calls")
                        index + 1
                    finally active.decrementAndGet()
                }
                check(output == Vector.tabulate(bench.size)(_ + 1), s"$runtime ordered results")
                check((0 until bench.size).forall(visits.get(_) == 1), s"$runtime exactly once")
                check(active.get() == 0 && peak.get() == limit, s"$runtime concurrency: ${peak.get()}")
                check(wrongThread.get() == 0, s"$runtime thread placement")

                val completed = new AtomicInteger()
                val failure = new IllegalStateException("expected")
                val observed = try
                    bench.batch { (_, index) =>
                        if index == 0 then throw failure
                        completed.incrementAndGet()
                        index
                    }
                    None
                catch case error: Throwable => Some(error)
                check(observed.contains(failure), s"$runtime propagates worker failure")
                val failedLaneSize = (bench.size + limit - 1) / limit
                check(completed.get() == bench.size - failedLaneSize, s"$runtime joins other workers after failure")
                check(bench.requests() == Vector.tabulate(bench.size)(_ + 1), s"$runtime recovers after failure")
            finally bench.teardown()
        // Exercise the no-delay server configuration separately.
        for runtime <- Vector("ce", "ceVirtual", "kyo") do
            val bench = new BlockingBench
            bench.runtime = runtime
            bench.parallelism = 8
            bench.size = 17
            bench.delayMicros = 0
            bench.setup()
            try check(bench.requests() == Vector.tabulate(17)(_ + 1), s"$runtime zero delay")
            finally bench.teardown()
        println(s"PASS $checks blocking checks")

    private def checkLane(lane: Int, index: Int, limit: Int): Unit =
        assert(index % limit == lane, s"Socket lane mismatch: $lane/$index/$limit")
