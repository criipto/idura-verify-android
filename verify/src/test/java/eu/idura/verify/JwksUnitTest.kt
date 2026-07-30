package eu.idura.verify

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Covers JWKS fetching and key parsing. The happy-path test round-trips a real generated RSA
 * keypair so a regression in the base64url/BigInteger handling produces a wrong key rather than
 * silently passing.
 */
class JwksUnitTest {
  private val base64Url = Base64.getUrlEncoder().withoutPadding()

  /**
   * Encodes a positive [BigInteger] the way a JWKS does: base64url over the *unsigned*
   * big-endian bytes. `BigInteger.toByteArray()` prepends a zero byte whenever the high bit is
   * set (which it always is for an RSA modulus), so that byte has to be stripped — otherwise the
   * fixture would not exercise the unsigned decoding real JWKS require.
   */
  private fun BigInteger.toJwkValue(): String {
    val bytes = toByteArray()
    val unsigned =
      if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    return base64Url.encodeToString(unsigned)
  }

  private fun clientReturning(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
  ) = HttpClient(
    MockEngine {
      respond(
        content = body,
        status = status,
        headers = headersOf("Content-Type", "application/json"),
      )
    },
  ) {
    install(ContentNegotiation) { json() }
  }

  @Test
  fun `parses an RSA key matching the original keypair`() =
    runTest {
      val generated =
        KeyPairGenerator
          .getInstance("RSA")
          .apply { initialize(2048) }
          .generateKeyPair()
          .public as RSAPublicKey

      var requestedUrl: String? = null
      val client =
        HttpClient(
          MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
              content =
                """
                {"keys":[{
                  "kty":"RSA",
                  "use":"sig",
                  "alg":"RS256",
                  "kid":"test-key",
                  "n":"${generated.modulus.toJwkValue()}",
                  "e":"${generated.publicExponent.toJwkValue()}"
                }]}
                """.trimIndent(),
              status = HttpStatusCode.OK,
              headers = headersOf("Content-Type", "application/json"),
            )
          },
        ) {
          install(ContentNegotiation) { json() }
        }

      val keys = fetchJwks(client, "example.idura.broker")

      assertEquals("https://example.idura.broker/.well-known/jwks.json", requestedUrl)
      assertEquals(1, keys.size)
      assertEquals("test-key", keys[0].id)
      assertEquals(generated.modulus, keys[0].publicKey.modulus)
      assertEquals(generated.publicExponent, keys[0].publicKey.publicExponent)
    }

  @Test
  fun `ignores unknown members and non-RSA keys until they are used`() =
    runTest {
      val client =
        clientReturning(
          """
          {"keys":[
            {"kty":"EC","kid":"ec-key","crv":"P-256","x":"abc","y":"def"},
            {"kty":"RSA","kid":"rsa-key","n":"AQAB","e":"AQAB","x5c":["ignored"]}
          ],"unknown_top_level":true}
          """.trimIndent(),
        )

      val keys = fetchJwks(client, "example.idura.broker")

      // The EC key parses fine — it only fails if something tries to use it as a signing key,
      // matching how a JWKS may legitimately carry keys the SDK cannot verify with.
      assertEquals(listOf("ec-key", "rsa-key"), keys.map { it.id })

      val ex = runCatching { keys[0].publicKey }.exceptionOrNull()
      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("unsupported key type 'EC'"))
    }

  @Test
  fun `throws when the endpoint returns an error status`() =
    runTest {
      val client =
        HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }) {
          install(ContentNegotiation) { json() }
        }

      val ex =
        runCatching { fetchJwks(client, "example.idura.broker") }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to fetch JWKS"))
    }

  @Test
  fun `throws when the document contains no keys`() =
    runTest {
      val ex =
        runCatching {
          fetchJwks(clientReturning("""{"keys":[]}"""), "example.idura.broker")
        }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("No keys found"))
    }

  @Test
  fun `throws when the document is not valid JWKS`() =
    runTest {
      val ex =
        runCatching {
          fetchJwks(clientReturning("""{"keys":[{"no_kty":true}]}"""), "example.idura.broker")
        }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to parse JWKS"))
    }

  @Test
  fun `throws when a 200 response is not JSON at all`() =
    runTest {
      // A captive portal or misconfigured domain answers 200 with HTML. Ktor raises
      // NoTransformationFoundException here rather than a serialization error, so this guards
      // that the wrapping is broad enough to catch it.
      val client =
        HttpClient(
          MockEngine {
            respond(
              content = "<html>captive portal</html>",
              status = HttpStatusCode.OK,
              headers = headersOf("Content-Type", "text/html"),
            )
          },
        ) {
          install(ContentNegotiation) { json() }
        }

      val ex = runCatching { fetchJwks(client, "example.idura.broker") }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to parse JWKS"))
    }

  @Test
  fun `propagates cancellation instead of wrapping it`() =
    runTest {
      // Activity teardown mid-login cancels the scope. Wrapping that in an
      // IduraVerifyInternalException would report a bogus network failure and break
      // structured concurrency, so cancellation has to pass through untouched.
      val started = CompletableDeferred<Unit>()
      val client =
        HttpClient(
          MockEngine {
            started.complete(Unit)
            awaitCancellation()
          },
        ) {
          install(ContentNegotiation) { json() }
        }

      val scope = CoroutineScope(Dispatchers.Default)
      val deferred = scope.async { fetchJwks(client, "example.idura.broker") }
      started.await()
      scope.cancel()

      val ex = runCatching { deferred.await() }.exceptionOrNull()
      assertTrue("expected cancellation, got $ex", ex is CancellationException)
    }

  @Test
  fun `wraps transport failures`() =
    runTest {
      val client =
        HttpClient(MockEngine { throw IOException("connection reset") }) {
          install(ContentNegotiation) { json() }
        }

      val ex =
        runCatching { fetchJwks(client, "example.idura.broker") }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to fetch JWKS"))
    }
}
