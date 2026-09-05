package bench

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import cats.effect.std.Queue
import cats.effect.std.Semaphore
import cats.effect.syntax.all.*
import cats.syntax.all.*
import kyo.*
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import scala.concurrent.duration.DurationInt

class PrimitivesBench extends BaseBench:

    @Param(Array("1000", "10000"))
    var ops: Int = 0

    @Param(Array("1", "4"))
    var producers: Int = 0

    var idxList: List[Int]    = Nil
    var perProducer: List[Int] = Nil

    @Setup(Level.Trial)
    def setup(): Unit =
        idxList = List.range(0, ops)
        perProducer = List.range(0, ops / producers)

    @Benchmark def ceRefUpdate(): Int =
        runCE {
            Ref.of[IO, Int](0).flatMap { ref =>
                def loop(i: Int): IO[Unit] = if i >= ops then IO.unit else ref.update(_ + 1).flatMap(_ => loop(i + 1))
                loop(0).flatMap(_ => ref.get)
            }
        }

    @Benchmark def kyoAtomicUpdate(): Int =
        runKyo {
            AtomicRef.init(0).map { ref =>
                def loop(i: Int): Unit < Sync = if i >= ops then () else ref.updateAndGet(_ + 1).map(_ => loop(i + 1))
                loop(0).map(_ => ref.get)
            }
        }

    @Benchmark def ceRefContended(): Int =
        runCE {
            Ref.of[IO, Int](0).flatMap { ref =>
                val per = ops / producers
                def loop(i: Int): IO[Unit] = if i >= per then IO.unit else ref.update(_ + 1).flatMap(_ => loop(i + 1))
                List.fill(producers)(loop(0)).parSequence_.flatMap(_ => ref.get)
            }
        }

    @Benchmark def kyoAtomicContended(): Int =
        runKyo {
            AtomicRef.init(0).map { ref =>
                val per = ops / producers
                def loop(i: Int): Unit < Sync = if i >= per then () else ref.updateAndGet(_ + 1).map(_ => loop(i + 1))
                Async.foreachDiscard(0 until producers, concurrency = producers)(_ => loop(0)).map(_ => ref.get)
            }
        }

    @Benchmark def ceDeferred(): Int =
        runCE {
            def loop(i: Int, acc: Int): IO[Int] =
                if i >= ops then IO.pure(acc)
                else
                    Deferred[IO, Int].flatMap { d =>
                        d.complete(i).flatMap(_ => d.get).flatMap(v => loop(i + 1, acc + v))
                    }
            loop(0, 0)
        }

    @Benchmark def kyoPromise(): Int =
        runKyo {
            def loop(i: Int, acc: Int): Int < (Sync & Async) =
                if i >= ops then acc
                else
                    Promise.init[Int, Any].map { p =>
                        p.completeDiscard(Result.succeed(i)).andThen(p.get).map(v => loop(i + 1, acc + v))
                    }
            loop(0, 0)
        }

    @Benchmark def ceQueue(): Int =
        runCE {
            Queue.bounded[IO, Int](64).flatMap { q =>
                val produce = List.range(0, producers).parTraverse_(_ => perProducer.traverse_(i => q.offer(i)))
                def consume(i: Int, acc: Int): IO[Int] =
                    if i >= ops then IO.pure(acc) else q.take.flatMap(v => consume(i + 1, acc + v))
                produce.start.flatMap(f => consume(0, 0).flatMap(r => f.joinWithNever.as(r)))
            }
        }

    @Benchmark def kyoChannel(): Int =
        runKyo {
            Channel.initUnscoped[Int](64).map { c =>
                val produce = Async.foreachDiscard(0 until producers, concurrency = producers) { _ =>
                    Kyo.foreachDiscard(0 until (ops / producers))(i => c.put(i))
                }
                def consume(i: Int, acc: Int): Int < (Async & Abort[Closed]) =
                    if i >= ops then acc else c.take.map(v => consume(i + 1, acc + v))
                Fiber.initUnscoped(produce).map(f => consume(0, 0).map(r => f.get.andThen(r)))
            }.handle(Abort.run[Closed](_)).map(_.getOrThrow)
        }

    @Benchmark def ceSemaphoreUncontended(): Int =
        runCE {
            Semaphore[IO](1).flatMap { sem =>
                def loop(i: Int): IO[Int] = if i >= ops then IO.pure(i) else sem.permit.use(_ => IO(i + 1)).flatMap(loop)
                loop(0)
            }
        }

    @Benchmark def kyoMeterUncontended(): Int =
        runKyo {
            Meter.useSemaphore(1) { meter =>
                def loop(i: Int): Int < (Async & Abort[Closed]) =
                    if i >= ops then i else meter.run(Sync.defer(i + 1)).map(loop)
                loop(0)
            }.handle(Abort.run[Closed](_)).map(_.getOrThrow)
        }

    @Benchmark def ceRace(): Int =
        runCE {
            def loop(i: Int): IO[Int] =
                if i >= ops then IO.pure(i)
                else IO.race(IO(i + 1), IO.never[Int]).flatMap(_.fold(loop, loop))
            loop(0)
        }

    @Benchmark def kyoRace(): Int =
        runKyo {
            def loop(i: Int): Int < Async =
                if i >= ops then i
                else Async.race(Sync.defer(i + 1), Async.never[Int]).map(loop)
            loop(0)
        }

    @Benchmark def ceTimeout(): Int =
        runCE {
            def loop(i: Int): IO[Int] =
                if i >= ops then IO.pure(i) else IO(i + 1).timeout(1.hour).flatMap(loop)
            loop(0)
        }

    @Benchmark def kyoTimeout(): Int =
        runKyo {
            def loop(i: Int): Int < (Async & Abort[Timeout]) =
                if i >= ops then i else Async.timeout(1.hour)(Sync.defer(i + 1)).map(loop)
            Abort.run[Timeout](loop(0)).map(_.getOrThrow)
        }

    @Benchmark def ceFiberSpawnJoin(): Int =
        runCE {
            idxList.parTraverse(i => IO(i).start.flatMap(_.joinWithNever)).map(_.sum)
        }

    @Benchmark def kyoFiberSpawnJoin(): Int =
        runKyo {
            Async.foreach(0 until ops, concurrency = ops)(i => Fiber.initUnscoped(Sync.defer(i)).map(_.get)).map(_.sum)
        }

end PrimitivesBench
