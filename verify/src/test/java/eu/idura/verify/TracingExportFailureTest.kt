package eu.idura.verify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Verifies that telemetry export failures never surface to the caller.
 *
 * The exporter is pointed at a [StubCollector] instead of the production endpoint, so these tests
 * cover the realistic failure modes: the collector returning an error, and the collector being
 * unreachable or unresponsive (as when the telemetry domain is blocked).
 */
class TracingExportFailureTest {
  /**
   * Records a span and shuts the tracer down, returning how long the shutdown took.
   *
   * Any exception escaping the span lifecycle or the shutdown fails the test, which is the
   * behaviour under test: telemetry failures must not propagate into the login flow.
   */
  private fun recordSpanAndClose(endpoint: String): Long {
    val tracing = Tracing("test.idura.app", telemetryEndpoint = endpoint)
    val tracer = tracing.getTracer("test", "0.0.0")

    tracer.spanBuilder("test span").startSpan().end()

    val startedAt = System.nanoTime()
    tracing.close()
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
  }

  @Test
  fun `a 500 from the collector does not throw`() {
    StubCollector(statusCode = 500).use { collector ->
      val tracing = Tracing("test.idura.app", telemetryEndpoint = collector.endpoint)
      val tracer = tracing.getTracer("test", "0.0.0")

      tracer.spanBuilder("test span").startSpan().end()
      // Flush rather than relying on close(), which intentionally does not wait for the export
      tracing.forceFlush()

      // 500 is not in OTEL's retryable set (429/502/503/504), so the batch is attempted once
      assertTrue("expected the exporter to have attempted an export", collector.requestCount() >= 1)

      tracing.close()
    }
  }

  @Test
  fun `a 503 from the collector does not throw`() {
    StubCollector(statusCode = 503).use { collector ->
      recordSpanAndClose(collector.endpoint)
    }
  }

  @Test
  fun `an unreachable collector does not throw`() {
    // Bind a port then release it, so connections to it are refused. This stands in for the
    // telemetry domain being blocked outright.
    val port =
      ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    recordSpanAndClose("http://localhost:$port/v1/traces")
  }

  @Test
  fun `spans can still be recorded after an export has failed`() {
    StubCollector(statusCode = 500).use { collector ->
      val tracing = Tracing("test.idura.app", telemetryEndpoint = collector.endpoint)
      val tracer = tracing.getTracer("test", "0.0.0")

      // Force a failing export, then confirm the tracer is still usable afterwards
      tracer.spanBuilder("first span").startSpan().end()
      tracing.forceFlush()

      val span = tracer.spanBuilder("second span").startSpan()
      span.end()
      assertTrue("expected a valid span context", span.spanContext.isValid)

      tracing.close()
    }
  }

  @Test
  fun `close shuts the tracer down off the calling thread`() {
    // BatchSpanProcessor can run the exporter's shutdown inline on whichever thread called it,
    // where it closes okhttp's pooled connections. On the main thread, closing a live TLS
    // connection is a fatal NetworkOnMainThreadException.
    StubCollector(statusCode = 200).use { collector ->
      val tracing = Tracing("test.idura.app", telemetryEndpoint = collector.endpoint)
      val tracer = tracing.getTracer("test", "0.0.0")

      tracer.spanBuilder("test span").startSpan().end()

      val shutdown = tracing.close()

      assertNotSame("the shutdown ran on the calling thread", Thread.currentThread(), shutdown)
      shutdown.join(TimeUnit.SECONDS.toMillis(10))
      assertFalse("the shutdown did not finish", shutdown.isAlive)
      // Off the main thread the shutdown can afford to join the flush, so the span still leaves
      assertTrue("expected the trailing span to be exported", collector.requestCount() >= 1)
    }
  }

  @Test
  fun `close does not block the calling thread when the collector never responds`() {
    // A collector which accepts the connection but never answers, so the export sits in okhttp's
    // call timeout. close() is called from onDestroy on the main thread, so blocking here is an
    // ANR risk.
    StubCollector(statusCode = null).use { collector ->
      val elapsedMillis = recordSpanAndClose(collector.endpoint)

      assertTrue(
        "close() blocked for ${elapsedMillis}ms, which risks an ANR when called from onDestroy",
        elapsedMillis < 1_000,
      )
    }
  }
}
