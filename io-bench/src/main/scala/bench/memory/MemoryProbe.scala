package bench.memory

import cats.effect.{Deferred, Fiber, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.lang.management.ManagementFactory
import java.lang.ref.Reference
import java.util.concurrent.{Callable, CountDownLatch, ExecutorService, Executors, Future, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.jdk.CollectionConverters.*

/** Application state is held across a wait, then read and checked after release. */
final class WaitingTasks(runtime: String, count: Int, payloadBytes: Int, executor: ExecutorService):
    require(Set("ce", "loom")(runtime) && count >= 0 && payloadBytes >= 0)
    private val arrived = new CountDownLatch(count)
    private val done = new AtomicInteger()
    private val virtual = new AtomicInteger()
    private val releaseLoom = new CountDownLatch(1)
    private var releaseCe: Deferred[IO, Unit] = null
    private var fibers = Vector.empty[Fiber[IO, Throwable, Long]]
    private var futures = Vector.empty[Future[Long]]
    private var released = false

    private def payload(i: Int): Array[Byte] =
        val bytes = new Array[Byte](payloadBytes)
        java.util.Arrays.fill(bytes, (i & 127).toByte)
        bytes

    private def consume(i: Int, bytes: Array[Byte]): Long =
        var checksum = i.toLong
        var j = 0
        while j < bytes.length do
            require(bytes(j) == (i & 127).toByte, s"Payload changed for task $i")
            checksum += bytes(j).toLong
            j += 1
        Reference.reachabilityFence(bytes)
        done.incrementAndGet()
        checksum

    if runtime == "ce" then
        val program = for
            gate <- Deferred[IO, Unit]
            _ <- IO { releaseCe = gate }
            tasks <- Vector.range(0, count).traverse { i =>
                (IO(payload(i)).flatMap { bytes =>
                    IO(arrived.countDown()) *> gate.get *> IO(consume(i, bytes))
                }).start
            }
        yield tasks
        fibers = program.unsafeRunSync()
    else
        futures = Vector.range(0, count).map { i =>
            executor.submit(new Callable[Long]:
                def call(): Long =
                    require(Thread.currentThread().isVirtual, "Expected a virtual task")
                    virtual.incrementAndGet()
                    val bytes = payload(i)
                    arrived.countDown()
                    releaseLoom.await()
                    consume(i, bytes)
            )
        }

    def awaitReady(): Unit =
        require(arrived.await(60, TimeUnit.SECONDS), "Not all tasks reached their wait")
        require(done.get() == 0, "A task completed before release")
        if runtime == "loom" then require(virtual.get() == count)

    def completed: Int = done.get()

    def releaseAndCheck(): Unit =
        require(!released, "Already released")
        released = true
        val sum = if runtime == "ce" then
            releaseCe.complete(()).unsafeRunSync()
            val result = fibers.foldLeftM(0L)((sum, fiber) => fiber.joinWithNever.map(sum + _)).unsafeRunSync()
            fibers = Vector.empty
            result
        else
            releaseLoom.countDown()
            val result = futures.foldLeft(0L)((sum, future) => sum + future.get(60, TimeUnit.SECONDS))
            futures = Vector.empty
            result
        val expected = (0 until count).foldLeft(0L)((sum, i) => sum + i.toLong + payloadBytes.toLong * (i & 127))
        require(sum == expected && done.get() == count, s"Incomplete work: $sum / $expected, ${done.get()} / $count")

/** Fresh-process memory probe. No throughput or latency score is produced. */
object MemoryProbe:
    private val memory = ManagementFactory.getMemoryMXBean
    private val collectors = ManagementFactory.getGarbageCollectorMXBeans.asScala.toVector
    private val threads = ManagementFactory.getThreadMXBean

    private def gcCount: Long = collectors.map(_.getCollectionCount).filter(_ >= 0).sum

    private def collect(): Unit =
        val before = gcCount
        for _ <- 0 until 3 do
            System.gc()
            Thread.sleep(150)
        require(gcCount >= before + 3, "Explicit garbage collections were not observed")

    private def emit(stage: String, extra: String = ""): Unit =
        val heap = memory.getHeapMemoryUsage
        val nonheap = memory.getNonHeapMemoryUsage
        println(s"""MEMORY {"stage":"$stage","heap_used":${heap.getUsed},"heap_committed":${heap.getCommitted},"nonheap_used":${nonheap.getUsed},"nonheap_committed":${nonheap.getCommitted},"gc_count":$gcCount,"platform_threads":${threads.getThreadCount}$extra}""")
        System.out.flush()

    private def command(expected: String): Unit =
        require(scala.io.StdIn.readLine() == expected, s"Expected command $expected")

    private def baseline(): Unit =
        collect()
        emit("baseline")
        command("START")

    private def waiting(runtime: String, count: Int, payloadBytes: Int): Unit =
        val executor = if runtime == "loom" then Executors.newVirtualThreadPerTaskExecutor() else null
        try
            // Warm the exact wait/release path, with the same count on both runtimes.
            for _ <- 0 until 3 do
                val warm = new WaitingTasks(runtime, 1000, payloadBytes, executor)
                warm.awaitReady()
                warm.releaseAndCheck()
            baseline()
            val cohort = new WaitingTasks(runtime, count, payloadBytes, executor)
            cohort.awaitReady()
            emit("held", s""", "waiting":$count,"completed":${cohort.completed}""")
            command("GC")
            collect()
            require(cohort.completed == 0)
            emit("live", s""", "waiting":$count,"completed":${cohort.completed}""")
            command("RELEASE")
            cohort.releaseAndCheck()
            collect()
            emit("released", s""", "completed":${cohort.completed}""")
            command("EXIT")
        finally if executor != null then
            executor.shutdownNow()
            require(executor.awaitTermination(10, TimeUnit.SECONDS))

    private def checkBatch(values: Vector[Int]): Unit =
        require(values.size == 256)
        var i = 0
        while i < values.size do
            require(values(i) == i + 1, s"Incorrect response at $i")
            i += 1

    private def tcp(runtime: String, connections: Int, transport: String): Unit =
        val bench = new _root_.bench.io.IoBench
        bench.runtime = runtime
        bench.transport = transport
        bench.parallelism = connections
        bench.size = 256
        bench.delayMicros = 1000
        bench.setup()
        try
            for _ <- 0 until 32 do checkBatch(bench.requests())
            baseline()
            val stop = new AtomicBoolean()
            val failure = new AtomicReference[Throwable]()
            val started = new CountDownLatch(1)
            val batches = new AtomicInteger()
            val driver = Thread.ofPlatform().name("memory-tcp-driver").start(() =>
                try
                    started.countDown()
                    while !stop.get() do
                        checkBatch(bench.requests())
                        batches.incrementAndGet()
                catch case error: Throwable => failure.set(error)
            )
            try
                require(started.await(10, TimeUnit.SECONDS))
                emit("active")
                command("STOP")
            finally
                stop.set(true)
                driver.join(30000)
                require(!driver.isAlive, "TCP driver did not stop")
            if failure.get() != null then throw failure.get()
            require(batches.get() > 0)
            collect()
            // Persistent sockets remain open: this is post-work heap, not suspended-task heap.
            emit("idle", s""", "checked_batches":${batches.get()}""")
            command("EXIT")
        finally bench.teardown()

    def main(args: Array[String]): Unit =
        require(args.length == 4, "runtime parked taskCount payloadBytes | runtime tcp connections transport")
        val Array(runtime, mode, count, setting) = args
        require(Set("ce", "loom")(runtime))
        def quoted(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        val inputArgs = ManagementFactory.getRuntimeMXBean.getInputArguments.asScala.map(quoted).mkString("[", ",", "]")
        println(s"""MEMORY {"stage":"hello","pid":${ProcessHandle.current().pid()},"runtime":"$runtime","mode":"$mode","count":${count.toInt},"setting":"$setting","jdk":"${System.getProperty("java.version")}","input_args":$inputArgs}""")
        System.out.flush()
        mode match
            case "parked" => waiting(runtime, count.toInt, setting.toInt)
            case "tcp" => tcp(runtime, count.toInt, setting)
            case _ => throw new IllegalArgumentException(mode)
        emit("complete")
