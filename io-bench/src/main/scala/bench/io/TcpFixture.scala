package bench.io

import bench.blocking.SocketClient
import java.io.EOFException
import java.net.{InetSocketAddress, StandardSocketOptions}
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler}
import java.util.concurrent.{Callable, CompletableFuture, Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

trait Exchange extends AutoCloseable:
    def blocking(lane: Int, value: Int): Int
    def async(lane: Int, value: Int)(complete: Either[Throwable, Int] => Unit): () => Unit

/** Same server and protocol for both transports; only the client I/O mechanism differs. */
final class TcpFixture(val transport: String, parallelism: Int, delayMicros: Int) extends Exchange:
    require(Set("blocking", "nonblocking").contains(transport))
    private val javaCommand = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString
    private val server = new ProcessBuilder(javaCommand, "-Xms64m", "-Xmx256m", "-XX:+UseG1GC",
        "-cp", System.getProperty("java.class.path"), "bench.blocking.SocketServer", delayMicros.toString)
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private var group: AsynchronousChannelGroup = null
    private var blockingClients = Vector.empty[SocketClient]
    private var asyncClients = Vector.empty[AsyncClient]
    try
        val startup = Executors.newVirtualThreadPerTaskExecutor()
        val line = try
            startup.submit(new Callable[String]:
                def call(): String = server.inputReader().readLine()
            ).get(15, TimeUnit.SECONDS)
        finally startup.shutdownNow()
        require(line != null && line.startsWith("PORT "), s"Server failed: $line")
        val port = line.stripPrefix("PORT ").toInt
        if transport == "nonblocking" then
            val factory: ThreadFactory = runnable => Thread.ofPlatform().daemon().name("io-bench-nio").unstarted(runnable)
            group = AsynchronousChannelGroup.withFixedThreadPool(2, factory)
        for lane <- 0 until parallelism do
            if transport == "blocking" then
                blockingClients :+= new SocketClient(port)
                require(blocking(lane, -1) == 0)
            else
                asyncClients :+= new AsyncClient(port, group)
                val ready = new CompletableFuture[Either[Throwable, Int]]()
                async(lane, -1)(value => { ready.complete(value); () })
                require(ready.get(10, TimeUnit.SECONDS) == Right(0))
    catch
        case error: Throwable =>
            close()
            throw error

    def blocking(lane: Int, value: Int): Int = blockingClients(lane).exchange(value)
    def async(lane: Int, value: Int)(complete: Either[Throwable, Int] => Unit): () => Unit =
        asyncClients(lane).exchange(value)(complete)

    def close(): Unit =
        try
            blockingClients.foreach(_.close())
            asyncClients.foreach(_.close())
            if group != null then
                group.shutdownNow()
                require(group.awaitTermination(10, TimeUnit.SECONDS), "NIO group did not terminate")
        finally
            server.destroy()
            if !server.waitFor(5, TimeUnit.SECONDS) then
                server.destroyForcibly()
                require(server.waitFor(5, TimeUnit.SECONDS), "Server did not terminate")

private final class AsyncClient(port: Int, group: AsynchronousChannelGroup) extends AutoCloseable:
    private val socket = AsynchronousSocketChannel.open(group)
    private val buffer = ByteBuffer.allocate(4)
    private val busy = new AtomicBoolean()
    try
        socket.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
        socket.connect(new InetSocketAddress("127.0.0.1", port)).get(10, TimeUnit.SECONDS)
    catch
        case error: Throwable =>
            close()
            throw error

    def exchange(value: Int)(complete: Either[Throwable, Int] => Unit): () => Unit =
        require(busy.compareAndSet(false, true), "Concurrent requests on one connection")
        val settled = new AtomicBoolean()
        def finish(result: Either[Throwable, Int]): Unit =
            if settled.compareAndSet(false, true) then
                busy.set(false)
                complete(result)
        val handler = new CompletionHandler[Integer, java.lang.Boolean]:
            def failed(error: Throwable, writing: java.lang.Boolean): Unit = finish(Left(error))
            def completed(count: Integer, writing: java.lang.Boolean): Unit =
                if count.intValue() < 0 then finish(Left(new EOFException("Peer closed connection")))
                else
                    try
                        if buffer.hasRemaining then submit(writing)
                        else if writing.booleanValue() then
                            buffer.clear()
                            submit(java.lang.Boolean.FALSE)
                        else
                            buffer.flip()
                            finish(Right(buffer.getInt()))
                    catch case NonFatal(error) => finish(Left(error))
            def submit(writing: java.lang.Boolean): Unit =
                if writing.booleanValue() then socket.write(buffer, 10L, TimeUnit.SECONDS, writing, this)
                else socket.read(buffer, 10L, TimeUnit.SECONDS, writing, this)
        try
            buffer.clear()
            buffer.putInt(value)
            buffer.flip()
            socket.write(buffer, 10L, TimeUnit.SECONDS, java.lang.Boolean.TRUE, handler)
        catch case NonFatal(error) => finish(Left(error))
        () => if !settled.get() then close()

    def close(): Unit = socket.close()
