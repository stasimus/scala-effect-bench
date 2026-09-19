package bench.direct

import java.util.concurrent.{ArrayBlockingQueue, Callable, CompletableFuture, ExecutionException, ExecutorService, Executors, Future, Semaphore, TimeUnit}
import gears.async.{Async, BufferedChannel, Future as GFuture, *}
import gears.async.default.given
import scala.util.Success

/** The context is supplied again inside each child, including Gears' child completion group. */
trait Backend extends AutoCloseable:
    type Context
    type Task[A]
    type Queue[A]
    type Promise[A]
    type Permit
    def run[A](body: Context ?=> A): A
    def fork[A](body: Context ?=> A)(using Context): Task[A]
    def join[A](task: Task[A])(using Context): A
    def queue[A](capacity: Int): Queue[A]
    def put[A](queue: Queue[A], value: A)(using Context): Unit
    def take[A](queue: Queue[A])(using Context): A
    def promise[A](): Promise[A]
    def complete[A](promise: Promise[A], value: A): Unit
    def await[A](promise: Promise[A])(using Context): A
    def permit(): Permit
    def withPermit[A](permit: Permit)(body: => A)(using Context): A
    def available(permit: Permit): Int
    def close(): Unit = ()

object Backend:
    def apply(name: String): Backend = name match
        case "loom" => new Loom
        case "ox" => new Ox
        case "gears" => new Gears
        case other => throw new IllegalArgumentException(other)

    def get[A](future: Future[A]): A =
        try future.get()
        catch case e: ExecutionException => throw e.getCause

    /** Java's primitives are also the explicit promise/permit baseline used by Ox. */
    trait JdkPrimitives extends Backend:
        type Promise[A] = CompletableFuture[A]
        type Permit = Semaphore
        def promise[A](): Promise[A] = new CompletableFuture[A]()
        def complete[A](promise: Promise[A], value: A): Unit = { promise.complete(value); () }
        def await[A](promise: Promise[A])(using Context): A = get(promise)
        def permit(): Permit = new Semaphore(1, false)
        def withPermit[A](permit: Permit)(body: => A)(using Context): A =
            permit.acquire()
            try body finally permit.release()
        def available(permit: Permit): Int = permit.availablePermits()

    final class Loom extends JdkPrimitives:
        type Context = ExecutorService
        type Task[A] = Future[A]
        type Queue[A] = ArrayBlockingQueue[A]
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        def run[A](body: Context ?=> A): A = get(executor.submit(new Callable[A]:
            def call(): A = body(using executor)))
        def fork[A](body: Context ?=> A)(using executor: Context): Task[A] =
            executor.submit(new Callable[A]:
                def call(): A = body(using executor))
        def join[A](task: Task[A])(using Context): A = get(task)
        def queue[A](capacity: Int): Queue[A] = new ArrayBlockingQueue[A](capacity)
        def put[A](queue: Queue[A], value: A)(using Context): Unit = queue.put(value)
        def take[A](queue: Queue[A])(using Context): A = queue.take()
        override def close(): Unit =
            executor.shutdownNow()
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "Loom tasks did not terminate")

    final class Ox extends JdkPrimitives:
        type Context = ox.OxUnsupervised
        type Task[A] = ox.Fork[A]
        type Queue[A] = ox.channels.Channel[A]
        def run[A](body: Context ?=> A): A = ox.unsupervised {
            ox.forkUnsupervised(body).join()
        }
        def fork[A](body: Context ?=> A)(using Context): Task[A] = ox.forkUnsupervised(body)
        def join[A](task: Task[A])(using Context): A = task.join()
        def queue[A](capacity: Int): Queue[A] = ox.channels.Channel.buffered[A](capacity)
        def put[A](queue: Queue[A], value: A)(using Context): Unit = queue.send(value)
        def take[A](queue: Queue[A])(using Context): A = queue.receive()

    final class Gears extends Backend:
        type Context = Async.Spawn
        type Task[A] = GFuture[A]
        type Queue[A] = BufferedChannel[A]
        type Promise[A] = GFuture.Promise[A]
        type Permit = gears.async.Semaphore
        def run[A](body: Context ?=> A): A = Async.blocking { GFuture(body).await }
        def fork[A](body: Context ?=> A)(using Context): Task[A] = GFuture(body)
        def join[A](task: Task[A])(using Context): A = task.await
        def queue[A](capacity: Int): Queue[A] = BufferedChannel[A](capacity)
        def put[A](queue: Queue[A], value: A)(using Context): Unit = queue.send(value)
        def take[A](queue: Queue[A])(using Context): A = queue.read().fold(_ => throw new IllegalStateException("Closed channel"), identity)
        def promise[A](): Promise[A] = GFuture.Promise[A]()
        def complete[A](promise: Promise[A], value: A): Unit = promise.complete(Success(value))
        def await[A](promise: Promise[A])(using Context): A = promise.await
        def permit(): Permit = new gears.async.Semaphore(1)
        def withPermit[A](permit: Permit)(body: => A)(using Context): A =
            val guard = permit.acquire()
            try body finally guard.release()
        // Gears exposes poll rather than a count. Called once after the measured loop.
        def available(permit: Permit): Int = permit.poll() match
            case Some(guard) => guard.release(); 1
            case None => 0
