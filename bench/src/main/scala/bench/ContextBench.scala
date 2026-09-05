package bench

import cats.data.Chain
import cats.data.Kleisli
import cats.data.StateT
import cats.data.WriterT
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import kyo.*
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param

class ContextBench extends BaseBench:

    @Param(Array("1000", "10000"))
    var ops: Int = 0

    @Benchmark def ceKleisli(): Int =
        def loop(i: Int, acc: Int): Kleisli[IO, Int, Int] =
            if i >= ops then Kleisli.pure(acc)
            else Kleisli.ask[IO, Int].flatMap(v => Kleisli.liftF(IO(acc + v))).flatMap(loop(i + 1, _))
        runCE(loop(0, 0).run(1))

    @Benchmark def kyoEnv(): Int =
        runKyo {
            def loop(i: Int, acc: Int): Int < (Env[Int] & Sync) =
                if i >= ops then acc
                else Env.use[Int](v => Sync.defer(acc + v)).map(loop(i + 1, _))
            Env.run(1)(loop(0, 0))
        }

    @Benchmark def ceStateT(): Int =
        def loop(i: Int): StateT[IO, Int, Unit] =
            if i >= ops then StateT.pure(())
            else StateT.modifyF[IO, Int](s => IO(s + 1)).flatMap(_ => loop(i + 1))
        runCE(loop(0).runS(0))

    @Benchmark def kyoVar(): Int =
        runKyo {
            def loop(i: Int): Unit < (Var[Int] & Sync) =
                if i >= ops then ()
                else Var.updateDiscard[Int](_ + 1).map(_ => loop(i + 1))
            Var.runTuple(0)(loop(0)).map(_._1)
        }

    @Benchmark def ceWriterChain(): Int =
        def loop(i: Int): WriterT[IO, Chain[Int], Unit] =
            if i >= ops then WriterT.value(())
            else WriterT.tell[IO, Chain[Int]](Chain.one(i)).flatMap(_ => loop(i + 1))
        runCE(loop(0).written.map(_.size.toInt))

    @Benchmark def kyoEmit(): Int =
        runKyo {
            def loop(i: Int): Unit < (Emit[Int] & Sync) =
                if i >= ops then ()
                else Emit.value(i).map(_ => loop(i + 1))
            Emit.run(loop(0)).map(_._1.size)
        }

    @Benchmark def ceResourceNested(): Int =
        val res = (1 to 32).foldLeft(Resource.pure[IO, Int](0)) { (acc, _) =>
            acc.flatMap(v => Resource.make(IO(v + 1))(_ => IO.unit))
        }
        runCE(res.use(IO.pure))

    @Benchmark def kyoScopeNested(): Int =
        runKyo {
            def loop(i: Int, acc: Int): Int < (Scope & Sync) =
                if i >= 32 then acc
                else Scope.acquireRelease(Sync.defer(acc + 1))(_ => Sync.defer(())).map(loop(i + 1, _))
            Scope.run(loop(0, 0))
        }

    private val boom = new RuntimeException("boom") with scala.util.control.NoStackTrace

    @Benchmark def ceHandleErrorLoop(): Int =
        runCE {
            def loop(i: Int): IO[Int] =
                if i >= ops then IO.pure(i)
                else IO.raiseError[Int](boom).handleErrorWith(_ => IO(i + 1)).flatMap(loop)
            loop(0)
        }

    @Benchmark def kyoRecoverLoop(): Int =
        runKyo {
            def loop(i: Int): Int < Sync =
                if i >= ops then i
                else Abort.recover[Throwable](_ => Sync.defer(i + 1))(Abort.fail(boom)).map(loop)
            loop(0)
        }

    @Benchmark def ceOrElseLoop(): Int =
        runCE {
            def loop(i: Int): IO[Int] =
                if i >= ops then IO.pure(i)
                else IO.raiseError[Int](boom).orElse(IO(i + 1)).flatMap(loop)
            loop(0)
        }

    @Benchmark def kyoFoldLoop(): Int =
        runKyo {
            def loop(i: Int): Int < Sync =
                if i >= ops then i
                else Abort.fold[Throwable](_ => i + 1, _ => i + 1)(Abort.fail(boom): Int < Abort[Throwable]).map(loop)
            loop(0)
        }

end ContextBench
