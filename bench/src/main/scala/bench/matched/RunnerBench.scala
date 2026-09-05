package bench.matched

import cats.effect.IO
import kyo.*
import org.openjdk.jmh.annotations.Benchmark

/** Baseline is reported, never subtracted from workload timings. */
class RunnerBench extends MatchedBase:
    @Benchmark def ceRunner(): Int = ce(IO(1))
    @Benchmark def kyoRunner(): Int = ky(Sync.defer(1))
