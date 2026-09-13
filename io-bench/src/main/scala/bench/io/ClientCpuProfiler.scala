package bench.io

import java.lang.management.ManagementFactory
import java.util.{Collection, Collections}
import org.openjdk.jmh.infra.{BenchmarkParams, IterationParams}
import org.openjdk.jmh.profile.InternalProfiler
import org.openjdk.jmh.results.{AggregationPolicy, IterationResult, Result, ScalarResult}

/** Client process CPU, including runtime and NIO threads; the separate server is excluded. */
class ClientCpuProfiler extends InternalProfiler:
    private val os = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
    private var before = 0L
    def getDescription(): String = "Client process CPU time per completed batch"
    def beforeIteration(bench: BenchmarkParams, iteration: IterationParams): Unit = before = os.getProcessCpuTime
    def afterIteration(bench: BenchmarkParams, iteration: IterationParams, result: IterationResult): Collection[? <: Result[?]] =
        val cpu = os.getProcessCpuTime - before
        val operations = result.getMetadata.getAllOps
        require(cpu >= 0 && operations > 0, "CPU time or operation count unavailable")
        Collections.singletonList(new ScalarResult("client.cpu", cpu.toDouble / operations / 1000000,
            "ms/batch", AggregationPolicy.AVG))
