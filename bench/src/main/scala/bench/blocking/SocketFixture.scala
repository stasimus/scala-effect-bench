package bench.blocking

import java.io.{DataInputStream, DataOutputStream, EOFException}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.util.concurrent.{Callable, Executors, TimeUnit}

/** Separate JVM: server allocation is excluded from the client's JMH GC profile. */
object SocketServer:
    def main(args: Array[String]): Unit =
        val delayMicros = args(0).toLong
        val listener = new ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
        // Exit if the owning benchmark JVM exits, including an interrupted JMH run.
        Thread.ofPlatform().daemon().start(() => {
            while System.in.read() != -1 do ()
            System.exit(0)
        })
        println(s"PORT ${listener.getLocalPort}")
        System.out.flush()
        while true do
            val socket = listener.accept()
            Thread.ofVirtual().start(() => serve(socket, delayMicros))

    private def serve(socket: Socket, delayMicros: Long): Unit =
        try
            socket.setTcpNoDelay(true)
            val in = new DataInputStream(socket.getInputStream)
            val out = new DataOutputStream(socket.getOutputStream)
            while true do
                val value = in.readInt()
                if delayMicros > 0 then Thread.sleep(java.time.Duration.ofNanos(delayMicros * 1000))
                out.writeInt(value + 1)
                out.flush()
        catch
            case _: EOFException => ()
            case _: java.net.SocketException => ()
        finally socket.close()

final class SocketClient(port: Int) extends AutoCloseable:
    private val socket = new Socket()
    try
        socket.connect(new InetSocketAddress("127.0.0.1", port), 10000)
        socket.setSoTimeout(10000)
        socket.setTcpNoDelay(true)
    catch
        case error: Throwable =>
            socket.close()
            throw error
    private val in = new DataInputStream(socket.getInputStream)
    private val out = new DataOutputStream(socket.getOutputStream)

    def exchange(value: Int): Int =
        out.writeInt(value)
        out.flush()
        in.readInt()

    def close(): Unit = socket.close()

final class SocketFixture(parallelism: Int, delayMicros: Int) extends AutoCloseable:
    private val javaCommand = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString
    private val server = new ProcessBuilder(javaCommand, "-Xms64m", "-Xmx256m", "-XX:+UseG1GC",
        "-cp", System.getProperty("java.class.path"), "bench.blocking.SocketServer", delayMicros.toString)
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private var connections = Vector.empty[SocketClient]
    try
        val startup = Executors.newVirtualThreadPerTaskExecutor()
        val line = try
            startup.submit(new Callable[String]:
                def call(): String = server.inputReader().readLine()
            ).get(15, TimeUnit.SECONDS)
        finally startup.shutdownNow()
        require(line != null && line.startsWith("PORT "), s"Server failed to start: $line")
        val port = line.stripPrefix("PORT ").toInt
        for _ <- 0 until parallelism do
            val client = new SocketClient(port)
            connections :+= client
            require(client.exchange(-1) == 0, "Invalid server response")
    catch
        case error: Throwable =>
            close()
            throw error

    def exchange(lane: Int, value: Int): Int = connections(lane).exchange(value)

    def close(): Unit =
        try connections.foreach(_.close())
        finally
            server.destroy()
            if !server.waitFor(5, TimeUnit.SECONDS) then
                server.destroyForcibly()
                require(server.waitFor(5, TimeUnit.SECONDS), "Server did not terminate")
