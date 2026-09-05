package bench

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.util.concurrent.TimeUnit
import kyo.<
import kyo.Abort
import kyo.AllowUnsafe.embrace.danger
import kyo.Async
import kyo.Duration
import kyo.Fiber
import kyo.Frame
import kyo.Sync
import org.openjdk.jmh.annotations.*

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 1,
    jvmArgsPrepend = Array("--add-opens=java.base/java.lang=ALL-UNNAMED")
)
abstract class BaseBench:

    def runKyo[A](v: => A < (Async & Abort[Throwable]))(using Frame): A =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(v).flatMap(_.block(Duration.Infinity))).getOrThrow

    def runCE[A](io: IO[A]): A =
        io.unsafeRunSync()

    def runKyoSync[A](v: => A < (Sync & Abort[Throwable]))(using Frame): A =
        Sync.Unsafe.evalOrThrow(v)

end BaseBench
