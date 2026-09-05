package bench

import cats.effect.IO
import kyo.*
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import scala.annotation.tailrec

class CoreLoopBench extends BaseBench:

    @Param(Array("1000", "10000", "100000"))
    var depth: Int = 0

    private def ceBind(i: Int): IO[Int] =
        if i >= depth then IO.pure(i)
        else IO(i + 1).flatMap(ceBind)

    private def kyoBind(i: Int): Int < Sync =
        if i >= depth then i
        else Sync.defer(i + 1).map(kyoBind)

    @Benchmark def ceBindDeep(): Int = runCE(ceBind(0))

    @Benchmark def kyoBindDeep(): Int = runKyo(kyoBind(0))

    private def ceBindLeft: IO[Int] =
        @tailrec def loop(acc: IO[Int], i: Int): IO[Int] =
            if i >= depth then acc else loop(acc.flatMap(v => IO(v + 1)), i + 1)
        loop(IO(0), 0)

    private def kyoBindLeft: Int < Sync =
        @tailrec def loop(acc: Int < Sync, i: Int): Int < Sync =
            if i >= depth then acc else loop(acc.map(v => Sync.defer(v + 1)), i + 1)
        loop(Sync.defer(0), 0)

    @Benchmark def ceBindLeftAssoc(): Int = runCE(ceBindLeft)

    @Benchmark def kyoBindLeftAssoc(): Int = runKyo(kyoBindLeft)

    private def ceMapped: IO[Int] =
        @tailrec def loop(acc: IO[Int], i: Int): IO[Int] =
            if i >= depth then acc else loop(acc.map(_ + 1), i + 1)
        loop(IO.pure(0), 0)

    private def kyoMapped: Int < Any =
        @tailrec def loop(acc: Int < Any, i: Int): Int < Any =
            if i >= depth then acc else loop(acc.map(_ + 1), i + 1)
        loop(0, 0)

    @Benchmark def ceMapChain(): Int = runCE(ceMapped)

    @Benchmark def kyoMapChain(): Int = runKyo(kyoMapped)

    private val boom = new RuntimeException("boom") with scala.util.control.NoStackTrace

    private def ceRaise(i: Int): IO[Int] =
        if i >= depth then IO.raiseError(boom)
        else IO(i + 1).flatMap(ceRaise)

    private def kyoRaise(i: Int): Int < (Sync & Abort[Throwable]) =
        if i >= depth then Abort.fail(boom)
        else Sync.defer(i + 1).map(kyoRaise)

    @Benchmark def ceRaiseAndHandle(): Either[Throwable, Int] =
        runCE(ceRaise(0).attempt)

    @Benchmark def kyoAbortAndHandle(): Result[Throwable, Int] =
        runKyo(Abort.run(kyoRaise(0)))

    @Benchmark def cePureBind(): Int =
        runCE {
            @tailrec def loop(acc: IO[Int], i: Int): IO[Int] =
                if i >= depth then acc else loop(acc.flatMap(v => IO.pure(v + 1)), i + 1)
            loop(IO.pure(0), 0)
        }

    @Benchmark def kyoPureBind(): Int =
        @tailrec def loop(acc: Int < Any, i: Int): Int < Any =
            if i >= depth then acc else loop(acc.map(v => v + 1), i + 1)
        runKyo(loop(0, 0))

end CoreLoopBench
