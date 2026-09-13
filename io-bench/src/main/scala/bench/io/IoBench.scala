package bench.io

import bench.matched.MatchedBase
import cats.effect.IO
import cats.effect.std.Supervisor
import cats.syntax.all.*
import java.util.concurrent.{Executors, TimeUnit}
import kyo.{<, Abort, Async, Fiber, Frame, Kyo, Result, Scope, Sync}
import kyo.AllowUnsafe.embrace.danger
import org.openjdk.jmh.annotations.{Scope as JmhScope, *}
import scala.concurrent.ExecutionContextExecutorService
import scala.util.Try

@BenchmarkMode(Array(Mode.Throughput, Mode.SampleTime))
class IoBench extends MatchedBase:
    @Param(Array("ce", "kyo", "gears", "ox")) var runtime: String = ""
    @Param(Array("blocking", "nonblocking")) var transport: String = ""
    @Param(Array("256")) var size: Int = 0
    @Param(Array("8", "64")) var parallelism: Int = 0
    @Param(Array("1000")) var delayMicros: Int = 0
    private var fixture: TcpFixture = null
    private var virtual: ExecutionContextExecutorService = null

    private[io] def exchange: Exchange = fixture

    @Setup(Level.Trial) def setup(): Unit =
        require(Runtime.version().feature() >= 21)
        require(Set("ce", "kyo", "gears", "ox", "ceVirtual", "kyoFlush", "kyoTuned").contains(runtime))
        require(size >= 0 && parallelism > 0 && delayMicros >= 0)
        require(Set("blocking", "nonblocking").contains(transport))
        require(runtime == "ce" || runtime == "kyo" || runtime == "gears" || runtime == "ox" || transport == "blocking")
        if runtime.startsWith("kyo") then
            require(!java.lang.Boolean.getBoolean("kyo.scheduler.virtualizeWorkers"))
            val keys = Vector("coreWorkers", "minWorkers", "maxWorkers", "scheduleStride")
            if runtime == "kyoTuned" then
                keys.foreach(key => require(System.getProperty(s"kyo.scheduler.$key") == "64", s"Set $key=64 before startup"))
            else keys.foreach(key => require(System.getProperty(s"kyo.scheduler.$key") == null, s"Unexpected tuning: $key"))
        if runtime == "ceVirtual" then
            virtual = scala.concurrent.ExecutionContext.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())
        try fixture = new TcpFixture(transport, parallelism, delayMicros)
        catch
            case error: Throwable =>
                teardown()
                throw error

    @TearDown(Level.Trial) def teardown(): Unit =
        try if fixture != null then fixture.close()
        finally if virtual != null then
            virtual.shutdownNow()
            require(virtual.awaitTermination(10, TimeUnit.SECONDS))

    private def ceCall(io: Exchange, lane: Int, index: Int): IO[Int] =
        if transport == "blocking" then IO.blocking(io.blocking(lane, index))
        else IO.async[Int](callback => IO {
            val cancel = io.async(lane, index)(callback)
            Some(IO(cancel()))
        })

    private def ceBatch(io: Exchange): IO[Vector[Int]] = IO.defer {
        val output = new Array[Int](size)
        def worker(lane: Int, index: Int): IO[Unit] =
            if index >= size then IO.unit
            else ceCall(io, lane, index).flatMap { value =>
                IO(output(index) = value) *> worker(lane, index + parallelism)
            }
        Supervisor[IO](await = true).use { supervisor =>
            Vector.range(0, math.min(size, parallelism)).traverse { lane =>
                val task = worker(lane, lane)
                supervisor.supervise((if runtime == "ceVirtual" then task.evalOn(virtual) else task).attempt)
            }.flatMap { fibers =>
                fibers.traverse(_.joinWithNever).flatMap { results =>
                    results.traverse_(IO.fromEither) *> IO(output.toVector)
                }
            }
        }
    }

    private def kyoCall(io: Exchange, lane: Int, index: Int)(using Frame): Int < Async =
        if transport == "blocking" then Sync.defer {
            if runtime != "kyo" then kyo.scheduler.Scheduler.get.flush()
            io.blocking(lane, index)
        }
        else Sync.defer {
            val promise = Fiber.Promise.Unsafe.init[Either[Throwable, Int], Any]()
            val cancel = io.async(lane, index)(result => promise.completeDiscard(Result.succeed(result)))
            Sync.ensure(cancel())(promise.safe.get.map(_.fold(throw _, identity)))
        }

    private def kyoBatch(io: Exchange)(using Frame): Vector[Int] < (Async & Abort[Throwable]) = Scope.run {
        Sync.defer {
            val output = new Array[Int](size)
            def worker(lane: Int, index: Int): Unit < Async =
                if index >= size then ()
                else kyoCall(io, lane, index).map { value =>
                    Sync.defer(output(index) = value).andThen(worker(lane, index + parallelism))
                }
            Kyo.foreach(0 until math.min(size, parallelism)) { lane =>
                Fiber.init(Abort.run[Throwable](worker(lane, lane)))
            }.map { fibers =>
                Kyo.foreach(fibers)(_.get).map { results =>
                    Sync.defer {
                        results.foreach(_.getOrThrow)
                        output.toVector
                    }
                }
            }
        }
    }

    private def gearsBatch(io: Exchange): Vector[Int] =
        import gears.async.{Async as GAsync, Future as GFuture, *}
        import gears.async.default.given
        GAsync.blocking {
            GFuture {
                val output = new Array[Int](size)
                val workers = Vector.range(0, math.min(size, parallelism)).map { lane =>
                    GFuture { Try {
                        var index = lane
                        while index < size do
                            output(index) = if transport == "blocking" then io.blocking(lane, index)
                            else
                                val f = GFuture.withResolver[Int] { resolver =>
                                    val cancel = io.async(lane, index)(_.fold(resolver.reject, resolver.resolve))
                                    resolver.onCancel(cancel)
                                }
                                cancellationScope(f)(f.await)
                            index += parallelism
                    } }
                }
                val results = workers.map(_.await)
                results.foreach(_.get)
                output.toVector
            }.await
        }

    private def oxBatch(io: Exchange): Vector[Int] = ox.unsupervised {
        ox.forkUnsupervised {
            val output = new Array[Int](size)
            val workers = Vector.range(0, math.min(size, parallelism)).map { lane =>
                ox.forkUnsupervised { Try {
                    var index = lane
                    while index < size do
                        output(index) = if transport == "blocking" then io.blocking(lane, index)
                        else
                            val channel = ox.channels.Channel.buffered[Either[Throwable, Int]](1)
                            val cancel = io.async(lane, index)(channel.send)
                            try channel.receive().fold(throw _, identity)
                            finally cancel()
                        index += parallelism
                } }
            }
            val results = workers.map(_.join())
            results.foreach(_.get)
            output.toVector
        }.join()
    }

    def batch(io: Exchange): Vector[Int] = runtime match
        case "ce" | "ceVirtual" => ce(ceBatch(io))
        case "kyo" | "kyoFlush" | "kyoTuned" => ky(kyoBatch(io))
        case "gears" => gearsBatch(io)
        case "ox" => oxBatch(io)

    @Benchmark def requests(): Vector[Int] = batch(fixture)
