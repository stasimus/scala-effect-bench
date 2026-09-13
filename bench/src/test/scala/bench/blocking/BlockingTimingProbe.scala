package bench.blocking

import java.util.concurrent.atomic.AtomicInteger
import kyo.{System as _, *}

object BlockingTimingProbe:
    def main(args: Array[String]): Unit =
        require(args.length >= 2 && Set("ce", "kyo").contains(args(0)), "usage: ce|kyo parallelism [plain|flush|scalaBlocking|native|external|nested]")
        val bench = new BlockingBench
        bench.runtime = args(0)
        bench.parallelism = args(1).toInt
        require(bench.parallelism > 0)
        bench.size = 256
        val mode = args.lift(2).getOrElse("plain")
        require(Set("plain", "flush", "scalaBlocking", "native", "external", "nested").contains(mode))
        val fixture = new SocketFixture(bench.parallelism, 1000)
        def beforeCall(): Unit =
            if mode == "flush" then kyo.scheduler.Scheduler.get.flush()
        def call(lane: Int, index: Int): Int =
            if mode == "scalaBlocking" then scala.concurrent.blocking(fixture.exchange(lane, index))
            else fixture.exchange(lane, index)
        def batch(operation: (Int, Int) => Int): Vector[Int] = mode match
            case "native" => bench.ky(Sync.defer {
                val output = new Array[Int](bench.size)
                def worker(lane: Int, index: Int): Unit < Sync =
                    if index >= bench.size then ()
                    else Sync.defer(operation(lane, index)).map { result =>
                        Sync.defer(output(index) = result).andThen(worker(lane, index + bench.parallelism))
                    }
                Async.foreach(0 until bench.parallelism, bench.parallelism)(lane => worker(lane, lane))
                    .map(_ => output.toVector)
            })
            case "external" | "nested" =>
                val output = new Array[Int](bench.size)
                val done = new java.util.concurrent.CountDownLatch(bench.parallelism)
                val error = new java.util.concurrent.atomic.AtomicReference[Throwable]()
                def submit(): Unit =
                    for lane <- 0 until bench.parallelism do
                        kyo.scheduler.Scheduler.get.schedule(kyo.scheduler.Task {
                            try
                                var index = lane
                                while index < bench.size do
                                    output(index) = operation(lane, index)
                                    index += bench.parallelism
                            catch case ex: Throwable =>
                                error.compareAndSet(null, ex)
                                ()
                            finally done.countDown()
                        })
                if mode == "external" then submit()
                else kyo.scheduler.Scheduler.get.schedule(kyo.scheduler.Task(submit()))
                require(done.await(20, java.util.concurrent.TimeUnit.SECONDS), "Task timeout")
                if error.get() != null then throw error.get()
                output.toVector
            case _ => bench.batch(operation)
        try
            val warmUntil = System.nanoTime() + 6000000000L
            while System.nanoTime() < warmUntil do batch { (lane, index) =>
                beforeCall()
                call(lane, index)
            }
            if bench.runtime == "kyo" then
                val status = kyo.scheduler.Scheduler.get.status()
                println(s"scheduler workers=${status.currentWorkers}, executor threads=${status.totalThreads}")
            for _ <- 0 until 3 do
                val starts = new Array[Long](bench.size)
                val ends = new Array[Long](bench.size)
                val active = new AtomicInteger()
                val peak = new AtomicInteger()
                val t0 = System.nanoTime()
                val result = batch { (lane, index) =>
                    beforeCall()
                    starts(index) = System.nanoTime()
                    val n = active.incrementAndGet()
                    peak.accumulateAndGet(n, (a, b) => math.max(a, b))
                    try call(lane, index)
                    finally
                        ends(index) = System.nanoTime()
                        active.decrementAndGet()
                }
                val t1 = System.nanoTime()
                assert(result == Vector.tabulate(bench.size)(_ + 1))
                val latency = starts.indices.map(i => (ends(i) - starts(i)) / 1e6).sorted
                println(f"${bench.runtime}/$mode limit=${bench.parallelism} batch=${(t1-t0)/1e6}%.3f ms " +
                    f"socketP50=${latency(128)}%.3f ms socketMax=${latency.last}%.3f ms " +
                    f"lastStart=${(starts.max-t0)/1e6}%.3f ms afterLastReply=${(t1-ends.max)/1e6}%.3f ms peak=${peak.get()}")
        finally fixture.close()
