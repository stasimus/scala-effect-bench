package bench

import kyo.*
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

object AuditProbe extends BaseBench:
    def main(args: Array[String]): Unit =
        val core = new CoreLoopBench
        core.depth = 10000
        assert(core.ceBindLeftAssoc() == 10000)
        assert(core.kyoBindLeftAssoc() == 10000)
        println("PASS left-associated chains both return 10000")

        val algebra = new AlgebraBench
        algebra.size = 4096
        algebra.setup()
        val ce = algebra.ceGatherSuccesses()
        val kyo = algebra.kyoGatherSuccesses().toSeq
        assert(ce == kyo && ce.size == 4092)
        println("PASS gather both return the same 4092 ordered successes")

        val piping = new PipingBench
        piping.size = 10000
        piping.par = 4
        piping.setup()
        val expected = 50005000
        assert(piping.ceParEvalMap() == expected)
        assert(piping.kyoMapPar() == expected)
        assert(piping.ceQueuePipeline() == expected)
        assert(piping.kyoChannelPipeline() == expected)
        println("PASS parallel map and queue pipelines all return 50005000")

        val variants = new AuditPipingBench
        variants.setup()
        assert(variants.ceQueueDirectProducer() == expected)
        assert(variants.kyoParSingletonChunks() == expected)
        println("PASS diagnostic queue and singleton-chunk variants return 50005000")

        val started = new CountDownLatch(1)
        val finalizerEntered = new CountDownLatch(1)
        val releaseFinalizer = new CountDownLatch(1)
        val finalizerDone = new CountDownLatch(1)
        val fiber = runKyo {
            Fiber.initUnscoped {
                Sync.ensure(Sync.defer {
                    finalizerEntered.countDown()
                    releaseFinalizer.await(10, TimeUnit.SECONDS)
                    finalizerDone.countDown()
                }) {
                    Sync.defer(started.countDown()).andThen(Async.never[Unit])
                }
            }
        }
        assert(started.await(5, TimeUnit.SECONDS))
        try
            runKyo(fiber.interrupt.andThen(fiber.getResult).andThen(()))
            assert(finalizerEntered.await(5, TimeUnit.SECONDS))
            assert(finalizerDone.getCount == 1)
            println("CONFIRMED interrupt + getResult returns before finalizer completion")
        finally releaseFinalizer.countDown()
        assert(finalizerDone.await(5, TimeUnit.SECONDS))

        val active = new AtomicInteger(0)
        val peak = new AtomicInteger(0)
        val result = runKyo {
            val source: Stream[Int, Async & Abort[Throwable]] = Stream.init(0 until 64)
            source.mapPar(4) { i =>
                Sync.ensure(Sync.defer { active.decrementAndGet(); () }) {
                    Sync.defer {
                        val current = active.incrementAndGet()
                        peak.accumulateAndGet(current, (a: Int, b: Int) => math.max(a, b))
                        ()
                    }.andThen(Async.sleep(1.millis)).andThen(i)
                }
            }.foldPure(0)(_ + _)
        }
        assert(result == (0 until 64).sum)
        println(s"mapPar(4) observed peak concurrency = ${peak.get()}")
