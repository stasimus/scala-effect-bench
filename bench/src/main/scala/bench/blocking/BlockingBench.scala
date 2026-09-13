package bench.blocking

import bench.matched.MatchedBase
import cats.effect.IO
import cats.syntax.all.*
import java.util.concurrent.{Executors, TimeUnit}
import kyo.*
import org.openjdk.jmh.annotations.*
import scala.concurrent.ExecutionContextExecutorService

@BenchmarkMode(Array(Mode.Throughput, Mode.SampleTime))
@OutputTimeUnit(TimeUnit.SECONDS)
class BlockingBench extends MatchedBase:
    @Param(Array("ce", "ceVirtual", "kyo")) var runtime: String = ""
    @Param(Array("256")) var size: Int = 0
    @Param(Array("8", "64")) var parallelism: Int = 0
    @Param(Array("0", "1000")) var delayMicros: Int = 0
    private var fixture: SocketFixture = null
    private var virtual: ExecutionContextExecutorService = null

    @Setup(Level.Trial) def setup(): Unit =
        require(Runtime.version().feature() >= 21, "Virtual threads require JDK 21+")
        require(Set("ce", "ceVirtual", "kyo").contains(runtime))
        require(size >= 0 && parallelism > 0 && delayMicros >= 0)
        require(!java.lang.Boolean.getBoolean("kyo.scheduler.virtualizeWorkers"), "This suite uses Kyo's default platform workers")
        if runtime == "ceVirtual" then
            virtual = scala.concurrent.ExecutionContext.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())
        try fixture = new SocketFixture(parallelism, delayMicros)
        catch
            case error: Throwable =>
                teardown()
                throw error

    @TearDown(Level.Trial) def teardown(): Unit =
        if fixture != null then fixture.close()
        if virtual != null then
            virtual.shutdownNow()
            virtual.awaitTermination(10, TimeUnit.SECONDS)

    // CE 3.7 may execute IO.blocking in-place on a compute worker. Merely replacing
    // IORuntime.blocking does not ensure virtual-thread execution. Shift this call explicitly.
    def ceBlocking[A](body: => A): IO[A] =
        val io = IO.blocking(body)
        if runtime == "ceVirtual" then io.evalOn(virtual) else io

    /** Static lanes: one request at a time per socket, identical indices and ordered storage.
      * Capture failures per worker, join every worker, then propagate any failure.
      */
    def ceBatch(operation: (Int, Int) => Int): IO[Vector[Int]] = IO.defer {
        val output = new Array[Int](size)
        def worker(lane: Int, index: Int): IO[Unit] =
            if index >= size then IO.unit
            else ceBlocking(operation(lane, index)).flatMap { result =>
                IO(output(index) = result) *> worker(lane, index + parallelism)
            }
        Vector.range(0, math.min(size, parallelism)).traverse(lane => worker(lane, lane).attempt.start).flatMap { fibers =>
            fibers.traverse(_.joinWithNever).flatMap { results =>
                results.traverse_(IO.fromEither) *> IO(output.toVector)
            }
        }
    }

    def kyoBatch(operation: (Int, Int) => Int)(using Frame): Vector[Int] < (Async & Abort[Throwable]) = Sync.defer {
        val output = new Array[Int](size)
        def worker(lane: Int, index: Int): Unit < Sync =
            if index >= size then ()
            else Sync.defer(operation(lane, index)).map { result =>
                Sync.defer(output(index) = result).andThen(worker(lane, index + parallelism))
            }
        Kyo.foreach(0 until math.min(size, parallelism)) { lane =>
            Fiber.initUnscoped(Abort.run[Throwable](worker(lane, lane)))
        }.map { fibers =>
            Kyo.foreach(fibers)(_.get).map { results =>
                Sync.defer {
                    results.foreach(_.getOrThrow)
                    output.toVector
                }
            }
        }
    }

    def batch(operation: (Int, Int) => Int): Vector[Int] =
        if runtime == "kyo" then ky(kyoBatch(operation))
        else ce(ceBatch(operation))

    @Benchmark def requests(): Vector[Int] = batch(fixture.exchange)
