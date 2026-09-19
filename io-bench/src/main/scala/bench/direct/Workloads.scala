package bench.direct

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.util.Try

object Workloads:
    def workers(b: Backend)(values: Vector[Int], limit: Int)(f: Int => Int)(using b.Context): Vector[Int] =
        require(limit > 0)
        val index = new AtomicInteger(0)
        val output = new Array[Int](values.size)
        val tasks = Vector.fill(math.min(values.size, limit)) {
            b.fork {
                var i = index.getAndIncrement()
                while i < values.size do
                    output(i) = f(values(i))
                    i = index.getAndIncrement()
            }
        }
        tasks.foreach(b.join(_))
        output.toVector

    def successes(b: Backend)(values: Vector[Int])(f: Int => Int)(using b.Context): Vector[Int] =
        val tasks = values.map(i => b.fork(Try(f(i)).toOption))
        tasks.map(b.join(_)).flatten

    def ref(ops: Int): Int =
        val ref = new AtomicReference[Int](0)
        var i = 0
        while i < ops do
            ref.updateAndGet(_ + 1)
            i += 1
        ref.get()

    def promises(b: Backend)(ops: Int)(using b.Context): Long =
        var sum = 0L
        var i = 0
        while i < ops do
            val p = b.promise[Int]()
            b.complete(p, i)
            sum += b.await(p)
            i += 1
        sum

    def queue(b: Backend)(ops: Int, capacity: Int)(using b.Context): Long =
        val q = b.queue[Int](capacity)
        val producer = b.fork {
            var i = 0
            while i < ops do
                b.put(q, i)
                i += 1
        }
        var sum = 0L
        var i = 0
        while i < ops do
            sum += b.take(q)
            i += 1
        b.join(producer)
        sum

    def permits(b: Backend)(ops: Int)(using b.Context): Int =
        val permit = b.permit()
        var i = 0
        while i < ops do
            b.withPermit(permit)(())
            i += 1
        b.available(permit)

    def spawnJoin(b: Backend)(ops: Int)(using b.Context): Long =
        var sum = 0L
        var i = 0
        while i < ops do
            val value = i
            sum += b.join(b.fork(value))
            i += 1
        sum

    /** Consume each entire transformed batch before starting the next one. */
    def chunks(b: Backend)(batches: Vector[Vector[Int]], parallelism: Int)(f: Int => Int)
        (emit: Vector[Int] => Unit)(using b.Context): Unit =
        def transform(batch: Vector[Int]): Vector[Int] =
            if parallelism == 0 then batch.map(f) else workers(b)(batch, parallelism)(f)
        if b.isInstanceOf[Backend.Ox] then
            ox.flow.Flow.fromIterable(batches).map(transform).runForeach(emit)
        else batches.foreach(batch => emit(transform(batch)))

    /** Capacity is measured in prebuilt Vector chunks; there is no sentinel or batch draining. */
    def queueChunks(b: Backend)(batches: Vector[Vector[Int]], capacity: Int)(emit: Vector[Int] => Unit)
        (using b.Context): Unit =
        val q = b.queue[Vector[Int]](capacity)
        val producer = b.fork {
            var i = 0
            while i < batches.size do
                b.put(q, batches(i))
                i += 1
        }
        if b.isInstanceOf[Backend.Ox] then
            ox.flow.Flow.unfold(0)(i => if i == batches.size then None else Some((b.take(q), i + 1)))
                .runForeach(emit)
        else
            var i = 0
            while i < batches.size do
                emit(b.take(q))
                i += 1
        b.join(producer)
