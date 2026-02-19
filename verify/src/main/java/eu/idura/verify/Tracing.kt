package eu.idura.verify

import android.os.Build
import android.util.Log
import com.fasterxml.uuid.Generators
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.IdGenerator
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
private data class IduraSpan(
  val context: Map<String, String>,
  val attributes: Map<String, String>,
  val name: String,
  val startTime: Long,
  val endTime: Long,
  val parentId: String,
  val spanKind: String,
  val status: String,
)

private fun nanosToMs(nanos: Long): Long = nanos / 1_000_000

private fun String.upperFirst() =
  this
    .lowercase()
    .replaceFirstChar { it.uppercase() }

private class HeimdalExporter(
  private val endpoint: String,
  private val client: HttpClient,
) : SpanExporter {
  private val errorMessageAttribute =
    AttributeKey
      .stringKey("error.message")
  private val durationAttribute =
    AttributeKey
      .stringKey("_duration")

  override fun export(spans: Collection<SpanData?>): CompletableResultCode {
    val resultCode = CompletableResultCode()

    CoroutineScope(Dispatchers.Default).launch {
      val response =
        client.post(endpoint) {
          contentType(ContentType.Application.Json)
          setBody(
            spans.filter { it != null }.map { spanData ->
              var attributes =
                spanData!!
                  .attributes
                  .asMap()
                  .plus(
                    mapOf(
                      durationAttribute to
                        nanosToMs(spanData.endEpochNanos - spanData.startEpochNanos),
                    ),
                  )

              if (spanData.status.statusCode === StatusCode.ERROR &&
                !spanData.status.description.isEmpty()
              ) {
                attributes =
                  attributes.plus(
                    mapOf(errorMessageAttribute to spanData.status.description),
                  )
              }

              IduraSpan(
                name = spanData.name,
                startTime = nanosToMs(spanData.startEpochNanos),
                endTime = nanosToMs(spanData.endEpochNanos),
                context = mapOf("spanId" to spanData.spanId, "traceId" to spanData.traceId),
                parentId = spanData.parentSpanId,
                spanKind = spanData.kind.toString().upperFirst(),
                attributes =
                  attributes
                    .mapKeys { it.key.toString() }
                    .mapValues { it.value.toString() },
                status =
                  spanData.status.statusCode
                    .toString()
                    .upperFirst(),
              )
            },
          )
        }

      if (response.status.value in 200..299) {
        resultCode.succeed()
      } else {
        resultCode.fail()
      }
      Log.d(TAG, "Metrics export complete")
    }

    return resultCode
  }

  override fun flush(): CompletableResultCode? = CompletableResultCode.ofSuccess()

  override fun shutdown(): CompletableResultCode? = CompletableResultCode.ofSuccess()
}

private class IduraAttributesProcessor(
  private val serverAddress: String,
) : SpanProcessor {
  // Store a GUID, to help correlate session (such as SDK init, and logins) from the same device.
  // The session ID is intentionally not saved, so it is regenerated when the app restarts. See https://developer.android.com/identity/user-data-ids#instance-ids-guids
  private val sessionId = UUID.randomUUID().toString()
  private val serverAddressAttribute =
    AttributeKey
      .stringKey("server.address")
  private val platformAttribute =
    AttributeKey
      .stringKey("device.platform")
  private val sdkVersionAttribute =
    AttributeKey
      .longKey("device.sdk")
  private val releaseAttribute =
    AttributeKey
      .stringKey("device.release")
  private val brandAttribute =
    AttributeKey
      .stringKey("device.brand")
  private val manufacturerAttribute =
    AttributeKey
      .stringKey("device.manufacturer")
  private val modelAttribute =
    AttributeKey
      .stringKey("device.model")
  private val iduraSdkVersionAttribute =
    AttributeKey
      .stringKey("idura.sdk.version")
  private val sessionIdAttribute =
    AttributeKey
      .stringKey("device.session.id")

  override fun onStart(
    parentContext: Context,
    span: ReadWriteSpan,
  ) {
    span.setAttribute(serverAddressAttribute, serverAddress)
    span.setAttribute(platformAttribute, "android")
    span.setAttribute(sdkVersionAttribute, Build.VERSION.SDK_INT_FULL)
    span.setAttribute(releaseAttribute, Build.VERSION.RELEASE)
    span.setAttribute(brandAttribute, Build.BRAND)
    span.setAttribute(manufacturerAttribute, Build.MANUFACTURER)
    span.setAttribute(modelAttribute, Build.MODEL)
    span.setAttribute(iduraSdkVersionAttribute, BuildConfig.VERSION)
    span.setAttribute(sessionIdAttribute, sessionId)
  }

  override fun isStartRequired(): Boolean = true

  override fun onEnd(span: ReadableSpan) = Unit

  override fun isEndRequired(): Boolean = false
}

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
  client: HttpClient,
) {
  private val tracerProvider =
    SdkTracerProvider
      .builder()
      .setIdGenerator(
        IduraIdGenerator(),
      ).addSpanProcessor(IduraAttributesProcessor(serverAddress))
      .addSpanProcessor(
        BatchSpanProcessor
          .builder(
            HeimdalExporter("https://telemetry.svc.criipto.com/v1/trace", client),
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
