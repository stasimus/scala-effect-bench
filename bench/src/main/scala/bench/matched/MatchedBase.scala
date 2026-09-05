package bench.matched

import bench.BaseBench
import cats.effect.IO
import kyo.*
import org.openjdk.jmh.annotations.*

/** Program construction and execution both begin on the corresponding runtime worker. */
@Fork(value = 3, jvmArgsPrepend = Array(
    "--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xms2g", "-Xmx2g", "-XX:+UseG1GC"))
abstract class MatchedBase extends BaseBench:
    def ce[A](body: => IO[A]): A = runCE(IO.defer(body))
    def ky[A](body: => A < (Async & Abort[Throwable]))(using Frame): A = runKyo(Sync.defer(body))

object Work:
    /** Same non-allocating CPU work on both sides; zero rounds is one increment. */
    def apply(value: Int, rounds: Int): Int =
        var result = value + 1
        var i = 0
        while i < rounds do
            result = java.lang.Integer.rotateLeft(result * 1664525 + 1013904223, 7)
            i += 1
        result
