package bench.direct

import bench.matched.Work
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** Observes the actual direct-style workload helpers. No instrumentation enters measured code. */
private final class ObservedBackend(val delegate: Backend) extends Backend:
    type Context = delegate.Context
    type Task[A] = delegate.Task[A]
    type Queue[A] = delegate.Queue[A]
    type Promise[A] = delegate.Promise[A]
    type Permit = delegate.Permit
    val roots = new AtomicInteger()
    val forks = new AtomicInteger()
    val joins = new AtomicInteger()
    val active = new AtomicInteger()
    val peak = new AtomicInteger()
    val capacities = new ConcurrentLinkedQueue[Int]()
    val sent = new ConcurrentLinkedQueue[Any]()
    val received = new ConcurrentLinkedQueue[Any]()
    val promises = new AtomicInteger()
    val completed = new AtomicInteger()
    val awaited = new AtomicInteger()
    val permits = new AtomicInteger()
    val entered = new AtomicInteger()
    val availableChecks = new AtomicInteger()
    def run[A](body: Context ?=> A): A = delegate.run {
        roots.incrementAndGet()
        body
    }
    def fork[A](body: Context ?=> A)(using Context): Task[A] =
        forks.incrementAndGet()
        delegate.fork {
            val n = active.incrementAndGet()
            peak.accumulateAndGet(n, math.max)
            try body finally active.decrementAndGet()
        }
    def join[A](task: Task[A])(using Context): A =
        val result = delegate.join(task)
        joins.incrementAndGet()
        result
    def queue[A](capacity: Int): Queue[A] =
        capacities.add(capacity)
        delegate.queue[A](capacity)
    def put[A](queue: Queue[A], value: A)(using Context): Unit =
        delegate.put(queue, value)
        sent.add(value)
        ()
    def take[A](queue: Queue[A])(using Context): A =
        val result = delegate.take(queue)
        received.add(result)
        result
    def promise[A](): Promise[A] =
        promises.incrementAndGet()
        delegate.promise[A]()
    def complete[A](promise: Promise[A], value: A): Unit =
        delegate.complete(promise, value)
        completed.incrementAndGet()
        ()
    def await[A](promise: Promise[A])(using Context): A =
        assert(completed.get() > awaited.get(), "This workload completes each promise before awaiting it")
        val result = delegate.await(promise)
        awaited.incrementAndGet()
        result
    def permit(): Permit =
        permits.incrementAndGet()
        delegate.permit()
    def withPermit[A](permit: Permit)(body: => A)(using Context): A = delegate.withPermit(permit) {
        entered.incrementAndGet()
        body
    }
    def available(permit: Permit): Int =
        availableChecks.incrementAndGet()
        delegate.available(permit)

object FairnessValidation:
    private var checks = 0
    private def equal[A](actual: A, expected: A): Unit =
        assert(actual == expected, s"expected $expected, got $actual")
        checks += 1
    private def topology(b: ObservedBackend, children: Int): Unit =
        equal(b.roots.get(), 1)
        equal(b.forks.get(), children)
        equal(b.joins.get(), children)
        equal(b.active.get(), 0)

    def main(args: Array[String]): Unit =
        for name <- Vector("loom", "ox", "gears") do
            val native = Backend(name)
            try
                for size <- Vector(0, 1, 17, 4096); limit <- Vector(1, 8) do
                    val b = new ObservedBackend(native)
                    val visits = new AtomicInteger()
                    equal(b.run {
                        Workloads.workers(b)(Vector.range(0, size), limit) { i =>
                            visits.incrementAndGet()
                            Work(i, 64)
                        }
                    }, Vector.tabulate(size)(Work(_, 64)))
                    equal(visits.get(), size)
                    topology(b, math.min(size, limit))

                for input <- Vector(Vector.empty, Vector(0), Vector(0, 1024, 2048), Vector.range(0, 4096)) do
                    val b = new ObservedBackend(native)
                    val visits = new AtomicInteger()
                    equal(b.run {
                        Workloads.successes(b)(input) { i =>
                            visits.incrementAndGet()
                            if (i & 1023) == 0 then throw new IllegalStateException("expected")
                            Work(i, 64)
                        }
                    }, input.filter(i => (i & 1023) != 0).map(Work(_, 64)))
                    equal(visits.get(), input.size)
                    topology(b, input.size)

                for ops <- Vector(0, 1, 17, 1000); capacity <- Vector(1, 64) do
                    val q = new ObservedBackend(native)
                    equal(q.run { Workloads.queue(q)(ops, capacity) }, ops.toLong * (ops - 1) / 2)
                    equal(q.sent.asScala.toVector, Vector.range(0, ops))
                    equal(q.received.asScala.toVector, Vector.range(0, ops))
                    equal(q.capacities.asScala.toVector, Vector(capacity))
                    topology(q, 1)

                for ops <- Vector(0, 1, 17, 1000) do
                    val spawn = new ObservedBackend(native)
                    equal(spawn.run { Workloads.spawnJoin(spawn)(ops) }, ops.toLong * (ops - 1) / 2)
                    topology(spawn, ops)
                    equal(spawn.peak.get(), if ops == 0 then 0 else 1)
                    val promises = new ObservedBackend(native)
                    equal(promises.run { Workloads.promises(promises)(ops) }, ops.toLong * (ops - 1) / 2)
                    equal(promises.promises.get(), ops)
                    equal(promises.completed.get(), ops)
                    equal(promises.awaited.get(), ops)
                    topology(promises, 0)
                    val permits = new ObservedBackend(native)
                    equal(permits.run { Workloads.permits(permits)(ops) }, 1)
                    equal(permits.permits.get(), 1)
                    equal(permits.entered.get(), ops)
                    equal(permits.availableChecks.get(), 1)
                    topology(permits, 0)

                for size <- Vector(0, 1, 129, 10000); chunk <- Vector(1, 64); capacity <- Vector(1, 64) do
                    val batches = Vector.range(0, size).grouped(chunk).toVector
                    val q = new ObservedBackend(native)
                    val output = Vector.newBuilder[Int]
                    q.run { Workloads.queueChunks(q)(batches, capacity) { batch => output ++= batch; () } }
                    equal(output.result(), Vector.range(0, size))
                    equal(q.sent.asScala.toVector, batches)
                    equal(q.received.asScala.toVector, batches)
                    equal(q.capacities.asScala.toVector, Vector(capacity))
                    topology(q, 1)
            finally native.close()
            println(s"PASS $name task counts, ordered queues, primitive operation counts and measured capacities")

        // Exercise each actual benchmark wrapper at both stress and measured queue capacities.
        for runtime <- Vector("ce", "kyo", "loom", "ox", "gears"); capacity <- Vector(1, 64) do
            val pipeline = new PipelineBench
            pipeline.runtime = runtime
            pipeline.size = 10000
            pipeline.chunkSize = 64
            pipeline.parallelism = 4
            pipeline.capacity = capacity
            pipeline.setupBackend()
            try
                for work <- Vector(0, 64) do
                    pipeline.work = work
                    pipeline.setup()
                    val sum = Vector.tabulate(10000)(i => Work(i, work).toLong).sum
                    equal(pipeline.sequentialChunks(), sum)
                    equal(pipeline.parallelChunks(), sum)
                    equal(pipeline.queueChunks(), sum)
            finally pipeline.closeBackend()
        println(s"PASS $checks five-way fairness checks")
