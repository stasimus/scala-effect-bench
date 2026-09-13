package bench.blocking

import java.util.concurrent.atomic.AtomicInteger
import kyo.scheduler.Scheduler

/** Diagnostic instrumentation, deliberately outside the JMH throughput methods. */
object SchedulerResearchProbe:
    def main(args: Array[String]): Unit =
        require(args.length == 6 || args.length == 7, "runtime size parallelism delayMicros warmupSeconds samples [label]")
        val label = args.lift(6).getOrElse("manual")
        val runtime = args(0)
        require(Set("ce", "kyo", "kyoFlush").contains(runtime))
        val bench = new BlockingBench
        bench.runtime = if runtime == "ce" then "ce" else "kyo"
        bench.size = args(1).toInt
        bench.parallelism = args(2).toInt
        val delay = args(3).toInt
        val warmup = args(4).toInt
        val samples = args(5).toInt
        require(bench.size > 0 && bench.parallelism > 0 && delay >= 0 && warmup > 0 && samples > 0)
        val fixture = new SocketFixture(bench.parallelism, delay)
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean
            .asInstanceOf[com.sun.management.OperatingSystemMXBean]
        val threadBean = java.lang.management.ManagementFactory.getThreadMXBean
        def prepare(): Unit = if runtime == "kyoFlush" then Scheduler.get.flush()
        def execute(): Unit =
            val result = bench.batch { (lane, index) =>
                prepare()
                fixture.exchange(lane, index)
            }
            assert(result == Vector.tabulate(bench.size)(_ + 1))
        try
            val endWarmup = System.nanoTime() + warmup.toLong * 1000000000L
            while System.nanoTime() < endWarmup do execute()
            if runtime != "ce" then
                val status = Scheduler.get.status()
                println(s"STATE case=$label workers=${status.currentWorkers} executorThreads=${status.totalThreads}")
                // Read private timing configuration only; never change library state.
                def field(value: AnyRef, name: String): AnyRef =
                    val f = value.getClass.getDeclaredField(name)
                    f.setAccessible(true)
                    f.get(value)
                val monitor = field(Scheduler.get, "blockingMonitor")
                println(s"MONITOR case=$label minIntervalNs=${field(monitor, "minIntervalNs")} cadenceNs=${field(monitor, "cadenceNs")} threshold=${field(monitor, "effectiveBlockThreshold")}")
            try
                val process = new ProcessBuilder("/bin/ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString).start()
                val rss = process.inputReader().readLine()
                if process.waitFor() == 0 then println(s"RSS_AFTER_WARMUP_KIB case=$label value=${rss.trim}")
            catch case _: java.io.IOException => println(s"RSS_AFTER_WARMUP_KIB case=$label value=unavailable")
            for sample <- 0 until samples do
                val starts = new Array[Long](bench.size)
                val ends = new Array[Long](bench.size)
                val active = new AtomicInteger()
                val peak = new AtomicInteger()
                val virtualCalls = new AtomicInteger()
                val cpu0 = os.getProcessCpuTime
                val t0 = System.nanoTime()
                val result = bench.batch { (lane, index) =>
                    prepare()
                    starts(index) = System.nanoTime()
                    if Thread.currentThread().isVirtual then virtualCalls.incrementAndGet()
                    val n = active.incrementAndGet()
                    peak.accumulateAndGet(n, (a, b) => math.max(a, b))
                    try fixture.exchange(lane, index)
                    finally
                        ends(index) = System.nanoTime()
                        active.decrementAndGet()
                }
                val t1 = System.nanoTime()
                val cpu1 = os.getProcessCpuTime
                assert(result == Vector.tabulate(bench.size)(_ + 1))
                assert(active.get() == 0 && peak.get() <= bench.parallelism)
                val times = starts.indices.map(i => ends(i) - starts(i)).sorted
                val status = if runtime == "ce" then None else Some(Scheduler.get.status())
                println(s"SAMPLE case=$label runtime=$runtime sample=$sample size=${bench.size} parallelism=${bench.parallelism} delayMicros=$delay " +
                    s"batchNs=${t1-t0} socketP50Ns=${times(times.size/2)} socketMaxNs=${times.last} " +
                    s"socketTotalNs=${times.sum} peak=${peak.get()} virtualCalls=${virtualCalls.get()} " +
                    s"cpuNs=${cpu1-cpu0} platformThreads=${threadBean.getThreadCount} " +
                    s"workers=${status.map(_.currentWorkers).getOrElse(-1)} executorThreads=${status.map(_.totalThreads).getOrElse(-1)}")
                if sample == 0 then
                    println(s"LANE_STARTS_NS case=$label values=" + starts.take(math.min(bench.size, bench.parallelism)).map(_ - t0).mkString(","))
        finally fixture.close()
