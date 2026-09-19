package bench.io

import java.util.concurrent.atomic.AtomicIntegerArray
import scala.util.Try

/** Check the published concurrency without adding assertions to JMH methods. */
object MeasuredIoValidation:
    def main(args: Array[String]): Unit =
        val runtimes = if args.nonEmpty then args.toVector else Vector("ce", "kyo", "gears", "ox", "ceVirtual", "kyoFlush")
        var checks = 0
        for runtime <- runtimes do
            val modes = if Set("ceVirtual", "kyoFlush", "kyoTuned")(runtime) then Vector("blocking")
                else Vector("blocking", "nonblocking")
            for mode <- modes do
                val bench = new IoBench
                bench.runtime = runtime
                bench.transport = mode
                bench.size = 256
                bench.parallelism = 64
                bench.delayMicros = 1000
                bench.setup()
                try
                    val seen = new AtomicIntegerArray(256)
                    val observed = new Exchange:
                        def record(lane: Int, index: Int): Unit =
                            assert(index % 64 == lane)
                            assert(seen.incrementAndGet(index) == 1)
                            val expectedVirtual = Set("loom", "ceVirtual", "gears", "ox")(runtime)
                            assert(Thread.currentThread().isVirtual == expectedVirtual)
                        def blocking(lane: Int, index: Int): Int =
                            record(lane, index)
                            bench.exchange.blocking(lane, index)
                        def async(lane: Int, index: Int)(complete: Either[Throwable, Int] => Unit): () => Unit =
                            record(lane, index)
                            bench.exchange.async(lane, index)(complete)
                        def close(): Unit = ()
                    assert(bench.batch(observed) == Vector.tabulate(256)(_ + 1))
                    assert((0 until 256).forall(i => seen.get(i) == 1))
                    checks += 1
                    val failure = new IllegalStateException("measured-concurrency failure")
                    val completed = new AtomicIntegerArray(256)
                    val failing = new Exchange:
                        def blocking(lane: Int, index: Int): Int =
                            if index == 0 then throw failure
                            val result = bench.exchange.blocking(lane, index)
                            completed.incrementAndGet(index)
                            result
                        def async(lane: Int, index: Int)(complete: Either[Throwable, Int] => Unit): () => Unit =
                            if index == 0 then
                                complete(Left(failure))
                                () => ()
                            else bench.exchange.async(lane, index) { result =>
                                completed.incrementAndGet(index)
                                complete(result)
                            }
                        def close(): Unit = ()
                    assert(Try(bench.batch(failing)).failed.get eq failure)
                    assert((0 until 256).forall(i => completed.get(i) == (if i % 64 == 0 then 0 else 1)))
                    checks += 1
                    assert(bench.requests() == Vector.tabulate(256)(_ + 1))
                    checks += 1
                finally bench.teardown()
        println(s"PASS $checks measured-concurrency checks (${runtimes.mkString(",")})")
