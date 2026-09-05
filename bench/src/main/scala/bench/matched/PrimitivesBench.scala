package bench.matched

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.{Queue, Semaphore}
import kyo.*
import org.openjdk.jmh.annotations.*

class PrimitivesBench extends MatchedBase:
    @Param(Array("1000")) var ops: Int = 0
    @Param(Array("64")) var capacity: Int = 0

    @Benchmark def ceRef(): Int = ce {
        Ref.of[IO, Int](0).flatMap { ref =>
            def loop(i: Int): IO[Int] =
                if i == ops then ref.get else ref.update(_ + 1).flatMap(_ => loop(i + 1))
            loop(0)
        }
    }
    @Benchmark def kyoRef(): Int = ky {
        AtomicRef.init(0).map { ref =>
            def loop(i: Int): Int < Sync =
                if i == ops then ref.get else ref.updateAndGet(_ + 1).map(_ => loop(i + 1))
            loop(0)
        }
    }

    @Benchmark def ceDeferred(): Long = ce {
        def loop(i: Int, sum: Long): IO[Long] =
            if i == ops then IO.pure(sum)
            else Deferred[IO, Int].flatMap(d => d.complete(i).flatMap(_ => d.get).flatMap(v => loop(i + 1, sum + v)))
        loop(0, 0L)
    }
    @Benchmark def kyoPromise(): Long = ky {
        def loop(i: Int, sum: Long): Long < (Async & Abort[Throwable]) =
            if i == ops then sum
            else Promise.init[Int, Any].map(p => p.completeDiscard(Result.succeed(i)).andThen(p.get).map(v => loop(i + 1, sum + v)))
        loop(0, 0L)
    }

    // A single producer and consumer, Int entries, fixed count, no close/sentinel or batch draining.
    @Benchmark def ceQueue(): Long = ce {
        Queue.bounded[IO, Int](capacity).flatMap { q =>
            def produce(i: Int): IO[Unit] =
                if i == ops then IO.unit else q.offer(i).flatMap(_ => produce(i + 1))
            def consume(i: Int, sum: Long): IO[Long] =
                if i == ops then IO.pure(sum) else q.take.flatMap(v => consume(i + 1, sum + v))
            produce(0).start.flatMap(f => consume(0, 0L).flatMap(sum => f.joinWithNever.map(_ => sum)))
        }
    }
    @Benchmark def kyoQueue(): Long = ky {
        Abort.run[Closed] {
            Channel.initUnscoped[Int](capacity).map { q =>
                def produce(i: Int): Unit < (Async & Abort[Closed]) =
                    if i == ops then () else q.put(i).andThen(produce(i + 1))
                def consume(i: Int, sum: Long): Long < (Async & Abort[Closed]) =
                    if i == ops then sum else q.take.map(v => consume(i + 1, sum + v))
                Fiber.initUnscoped(produce(0)).map(f => consume(0, 0L).map(sum => f.get.andThen(sum)))
            }
        }.map(_.getOrThrow)
    }

    @Benchmark def ceSemaphore(): Int = ce {
        Semaphore[IO](1).flatMap { sem =>
            def loop(i: Int): IO[Int] =
                if i == ops then sem.available.map(_.toInt)
                else sem.permit.use(_ => IO.unit).flatMap(_ => loop(i + 1))
            loop(0)
        }
    }
    @Benchmark def kyoSemaphore(): Int = ky {
        Abort.run[Closed] {
            Meter.initSemaphoreUnscoped(1, reentrant = false).map { sem =>
                def loop(i: Int): Int < (Async & Abort[Closed]) =
                    if i == ops then sem.availablePermits
                    else sem.run(()).andThen(loop(i + 1))
                loop(0)
            }
        }.map(_.getOrThrow)
    }

    // Exactly one child at a time, with a successful join before the next spawn.
    @Benchmark def ceSpawnJoin(): Long = ce {
        def loop(i: Int, sum: Long): IO[Long] =
            if i == ops then IO.pure(sum)
            else IO(i).start.flatMap(_.joinWithNever).flatMap(v => loop(i + 1, sum + v))
        loop(0, 0L)
    }
    @Benchmark def kyoSpawnJoin(): Long = ky {
        def loop(i: Int, sum: Long): Long < (Async & Abort[Throwable]) =
            if i == ops then sum
            else Fiber.initUnscoped(Sync.defer(i)).map(_.get).map(v => loop(i + 1, sum + v))
        loop(0, 0L)
    }
