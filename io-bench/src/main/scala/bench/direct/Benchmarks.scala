package bench.direct

import bench.matched.{MatchedBase, Work}
import org.openjdk.jmh.annotations.*

abstract class DirectBase extends MatchedBase:
    @Param(Array("ce", "kyo", "loom", "ox", "gears")) var runtime: String = ""
    var backend: Backend = null
    @Setup(Level.Trial) def setupBackend(): Unit =
        require(Runtime.version().feature() >= 21)
        if runtime != "ce" && runtime != "kyo" then backend = Backend(runtime)
    @TearDown(Level.Trial) def closeBackend(): Unit = if backend != null then backend.close()

class ParallelBench extends DirectBase:
    @Param(Array("4096")) var size: Int = 0
    @Param(Array("8")) var parallelism: Int = 0
    @Param(Array("0", "64")) var work: Int = 0
    private val reference = new bench.matched.ParallelBench
    private val boom = new RuntimeException("expected") with scala.util.control.NoStackTrace
    @Setup def setup(): Unit =
        reference.size = size
        reference.parallelism = parallelism
        reference.work = work
        reference.setup()
    @Benchmark def workers(): Vector[Int] = runtime match
        case "ce" => reference.ceWorkers()
        case "kyo" => reference.kyoWorkers()
        case _ =>
            val b = backend
            b.run { Workloads.workers(b)(reference.values, parallelism)(Work(_, work)) }
    @Benchmark def collectSuccesses(): Vector[Int] = runtime match
        case "ce" => reference.ceCollectSuccesses()
        case "kyo" => reference.kyoCollectSuccesses()
        case _ =>
            val b = backend
            b.run { Workloads.successes(b)(reference.values) { i =>
                if (i & 1023) == 0 then throw boom else Work(i, work)
            } }

class PrimitivesBench extends DirectBase:
    @Param(Array("1000")) var ops: Int = 0
    @Param(Array("64")) var capacity: Int = 0
    private val reference = new bench.matched.PrimitivesBench
    @Setup def setup(): Unit =
        reference.ops = ops
        reference.capacity = capacity
    @Benchmark def ref(): Int = runtime match
        case "ce" => reference.ceRef()
        case "kyo" => reference.kyoRef()
        case _ => backend.run { Workloads.ref(ops) }
    @Benchmark def promise(): Long = runtime match
        case "ce" => reference.ceDeferred()
        case "kyo" => reference.kyoPromise()
        case _ =>
            val b = backend
            b.run { Workloads.promises(b)(ops) }
    @Benchmark def queue(): Long = runtime match
        case "ce" => reference.ceQueue()
        case "kyo" => reference.kyoQueue()
        case _ =>
            val b = backend
            b.run { Workloads.queue(b)(ops, capacity) }
    @Benchmark def semaphore(): Int = runtime match
        case "ce" => reference.ceSemaphore()
        case "kyo" => reference.kyoSemaphore()
        case _ =>
            val b = backend
            b.run { Workloads.permits(b)(ops) }
    @Benchmark def spawnJoin(): Long = runtime match
        case "ce" => reference.ceSpawnJoin()
        case "kyo" => reference.kyoSpawnJoin()
        case _ =>
            val b = backend
            b.run { Workloads.spawnJoin(b)(ops) }

class PipelineBench extends DirectBase:
    @Param(Array("10000")) var size: Int = 0
    @Param(Array("64")) var chunkSize: Int = 0
    @Param(Array("4")) var parallelism: Int = 0
    @Param(Array("64")) var capacity: Int = 0
    @Param(Array("0", "64")) var work: Int = 0
    private val reference = new bench.matched.StreamBench
    @Setup def setup(): Unit =
        reference.size = size
        reference.chunkSize = chunkSize
        reference.parallelism = parallelism
        reference.capacity = capacity
        reference.work = work
        reference.setup()
    def directChunks(parallel: Boolean): Long =
        val b = backend
        b.run {
            var sum = 0L
            Workloads.chunks(b)(reference.batches, if parallel then parallelism else 0)(Work(_, work)) {
                batch => batch.foreach(value => sum += value.toLong)
            }
            sum
        }
    @Benchmark def sequentialChunks(): Long = runtime match
        case "ce" => reference.ceEvalChunks()
        case "kyo" => reference.kyoEvalChunks()
        case _ => directChunks(false)
    @Benchmark def parallelChunks(): Long = runtime match
        case "ce" => reference.ceParallelChunks()
        case "kyo" => reference.kyoParallelChunks()
        case _ => directChunks(true)
    @Benchmark def queueChunks(): Long = runtime match
        case "ce" => reference.ceQueueChunks()
        case "kyo" => reference.kyoQueueChunks()
        case _ =>
            val b = backend
            b.run {
                var sum = 0L
                Workloads.queueChunks(b)(reference.batches, capacity) { batch =>
                    batch.foreach(value => sum += Work(value, work).toLong)
                }
                sum
            }

class RunnerBench extends DirectBase:
    private val reference = new bench.matched.RunnerBench
    @Benchmark def entry(): Int = runtime match
        case "ce" => reference.ceRunner()
        case "kyo" => reference.kyoRunner()
        case _ => backend.run { 1 }

/** Same arithmetic, separately labelled: a direct loop does not construct an effect chain. */
class SequentialBaseline extends DirectBase:
    @Param(Array("1000", "10000")) var depth: Int = 0
    private val reference = new bench.matched.CoreBench
    @Setup def setup(): Unit = reference.depth = depth
    @Benchmark def increment(): Int = runtime match
        case "ce" => reference.ceDeepBind()
        case "kyo" => reference.kyoDeepBind()
        case _ => backend.run {
            var i = 0
            while i < depth do i += 1
            i
        }
