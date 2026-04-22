package eu.idura.verify

import android.os.Build
import com.fasterxml.uuid.Generators
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.IdGenerator
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import java.util.UUID

private class IduraIdGenerator : IdGenerator {
  private val uuidV7Generator =
    Generators
      .timeBasedEpochGenerator()

  // Use the default OTEL generator for spans
  override fun generateSpanId(): String = IdGenerator.random().generateSpanId()

  // Use a custom generator for traces, to generate UUIDv7s
  override fun generateTraceId(): String =
    uuidV7Generator
      .generate()
      .toString()
      .replace("-", "")
}

internal class Tracing(
  serverAddress: String,
) {
  private val tracerProvider =
    SdkTracerProvider
      .builder()
      .setIdGenerator(
        IduraIdGenerator(),
      ).setResource(
        Resource
          .getDefault()
          .toBuilder()
          // Inspired by https://github.com/open-telemetry/opentelemetry-android/blob/79f7a5280a04bc39696dfdc4cdc9e009eac98257/core/src/main/java/io/opentelemetry/android/AndroidResource.kt
          .put("os.name", "android")
          .put("os.type", "linux")
          .put("os.version", Build.VERSION.RELEASE)
          .put("device.model.name", Build.MODEL)
          .put("device.model.identifier", Build.MODEL)
          .put("device.manufacturer", Build.MANUFACTURER)
          .put("android.os.api_level", Build.VERSION.SDK_INT.toString())
          // Idura specific attributes
          .put("server.address", serverAddress)
          .put("idura.sdk.version", BuildConfig.VERSION)
          // Store a GUID, to help correlate session (such as SDK init, and logins) from the same device.
          // The session ID is intentionally not saved, so it is regenerated when the app restarts. See https://developer.android.com/identity/user-data-ids#instance-ids-guids
          .put("device.session.id", UUID.randomUUID().toString())
          .build(),
      ).addSpanProcessor(
        BatchSpanProcessor
          .builder(
            OtlpHttpSpanExporter
              .builder()
              .setEndpoint(
                "https://telemetry.idura.app/v1/traces",
              ).build(),
          ).build(),
      ).build()

  fun close() = tracerProvider.close()

  fun getTracer(
    instrumentationScopeName: String,
    instrumentationScopeVersion: String,
  ): Tracer = tracerProvider.get(instrumentationScopeName, instrumentationScopeVersion)

  fun propagators(): ContextPropagators =
    ContextPropagators.create(W3CTraceContextPropagator.getInstance())
}

internal object KtorRequestSetter : TextMapSetter<HttpRequestBuilder> {
  override fun set(
    carrier: HttpRequestBuilder?,
    key: String,
    value: String,
  ) {
    carrier?.header(key, value)
  }
}

/**
 * Utility function which wraps a block of code in a span:
 * 1. Start the span
 * 2. Execute the block of code
 *    a. If the block completes, set status to OK
 *    b. Otherwise, set status to ERROR
 * 3. Close the current scope
 * 4. End the span
 *
 * This is very similar to `ExtendedSpanBuilder.startAndRun` https://github.com/open-telemetry/opentelemetry-java/blob/36ca9b85b799939b6cb650c5fe95e90ee2f87059/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/ExtendedSdkSpanBuilder.java#L156
 * from the OTEL SDK, with two notable exceptions:
 * 1. It sets status to OK when the block completes successfully
 * 2. It supports suspend functions
 * 3. It does not update the current OTEL context. Instead, the SDK relies on manually passing spans
 */
internal suspend inline fun <T> SpanBuilder.startAndRun(
  crossinline block: suspend (span: Span) -> T,
): T {
  val span = this.startSpan()

  try {
    val result = block(span)
    span.setStatus(StatusCode.OK)
    return result
  } catch (exception: Throwable) {
    span.setStatus(StatusCode.ERROR, exception.message ?: "")
    span.recordException(exception)
    throw exception
  } finally {
    span.end()
  }
}

internal fun SpanBuilder.withSpanContext(span: Span) =
  this.setParent(span.storeInContext(Context.current()))
