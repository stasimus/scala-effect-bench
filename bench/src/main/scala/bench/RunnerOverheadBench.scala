package bench

import cats.effect.IO
import cats.effect.SyncIO
import kyo.*
import org.openjdk.jmh.annotations.Benchmark

/** Cost of the harness itself: how much of every measured op is just the runner handing work to a runtime. */
class RunnerOverheadBench extends BaseBench:

    @Benchmark def ceUnsafeRunSync(): Int = runCE(IO.pure(1))

    @Benchmark def ceUnsafeRunSyncDelay(): Int = runCE(IO(1))

    @Benchmark def ceSyncIO(): Int = SyncIO.pure(1).unsafeRunSync()

    @Benchmark def kyoSyncEval(): Int = runKyoSync(Sync.defer(1))

    @Benchmark def kyoPureEval(): Int = (1: Int < Any).eval

    @Benchmark def kyoFiberBlock(): Int = runKyo(Sync.defer(1))

end RunnerOverheadBench
