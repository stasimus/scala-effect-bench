package bench.blocking

import bench.matched.MatchedBase
import kyo.scheduler.Scheduler
import org.openjdk.jmh.annotations.*

/** Explicit blocking declarations: CE IO.blocking and Kyo's documented queue flush. */
@BenchmarkMode(Array(Mode.Throughput, Mode.SampleTime))
class BlockingHintBench extends MatchedBase:
    @Param(Array("ce", "kyoFlush")) var runtime: String = ""
    @Param(Array("256")) var size: Int = 0
    @Param(Array("8", "64")) var parallelism: Int = 0
    @Param(Array("0", "1000")) var delayMicros: Int = 0
    private val delegate = new BlockingBench
    private var fixture: SocketFixture = null

    @Setup(Level.Trial) def setup(): Unit =
        require(Set("ce", "kyoFlush").contains(runtime))
        require(size >= 0 && parallelism > 0 && delayMicros >= 0)
        require(!java.lang.Boolean.getBoolean("kyo.scheduler.virtualizeWorkers"))
        delegate.runtime = "ce"
        delegate.size = size
        delegate.parallelism = parallelism
        fixture = new SocketFixture(parallelism, delayMicros)

    @TearDown(Level.Trial) def teardown(): Unit =
        if fixture != null then fixture.close()

    @Benchmark def requests(): Vector[Int] =
        if runtime == "ce" then ce(delegate.ceBatch(fixture.exchange))
        else ky(delegate.kyoBatch { (lane, index) =>
            Scheduler.get.flush()
            fixture.exchange(lane, index)
        })
