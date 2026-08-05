package eu.idura.verify

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal stub OTLP collector, used to exercise telemetry export failures without reaching the
 * production endpoint.
 *
 * Implemented over a raw [ServerSocket] rather than a HTTP server library, because
 * `com.sun.net.httpserver` is not on the Android unit test classpath and this needs to model
 * responses a real server would not send (namely, never responding at all).
 */
internal class StubCollector(
  /** The status to answer requests with, or null to accept the request and never respond. */
  private val statusCode: Int?,
) : Closeable {
  private val serverSocket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
  private val requestCounter = AtomicInteger()

  /** Held by the handler when [statusCode] is null, so hung requests are released on [close]. */
  private val release = CountDownLatch(1)

  private val acceptThread =
    Thread {
      while (!serverSocket.isClosed) {
        try {
          serverSocket.accept().use(::handle)
        } catch (_: Exception) {
          // The socket was closed, or the client hung up. Either way there is nothing to do.
        }
      }
    }.apply {
      isDaemon = true
      start()
    }

  /** The OTLP traces endpoint this collector is listening on. */
  val endpoint: String get() = "http://localhost:${serverSocket.localPort}/v1/traces"

  /** How many export requests have been received so far. */
  fun requestCount(): Int = requestCounter.get()

  private fun handle(socket: Socket) {
    drainRequest(socket)
    requestCounter.incrementAndGet()

    if (statusCode == null) {
      // Model a blackholed endpoint: the connection is accepted, but no response ever arrives.
      release.await(60, TimeUnit.SECONDS)
      return
    }

    socket.getOutputStream().apply {
      write("HTTP/1.1 $statusCode Stubbed\r\nContent-Length: 0\r\n\r\n".toByteArray())
      flush()
    }
  }

  /**
   * Reads the request headers and body, so the client is never blocked writing into a full buffer.
   */
  private fun drainRequest(socket: Socket) {
    val input = socket.getInputStream()
    val headers = StringBuilder()

    while (!headers.endsWith("\r\n\r\n")) {
      val byte = input.read()
      if (byte == -1) return
      headers.append(byte.toChar())
    }

    val contentLength =
      headers
        .lines()
        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.toIntOrNull()
        ?: 0

    repeat(contentLength) {
      if (input.read() == -1) return
    }
  }

  override fun close() {
    release.countDown()
    serverSocket.close()
    acceptThread.join(TimeUnit.SECONDS.toMillis(5))
  }
}
