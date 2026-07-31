package eu.idura.verify

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException
import com.nimbusds.jose.Algorithm as JwkAlgorithm

/**
 * Covers JWKS fetching, key parsing and the mapping from a JWK to the algorithm its tokens are
 * verified with.
 *
 * Fixtures are built by generating a real keypair and serialising it with Nimbus rather than by
 * hand-encoding base64url members, so a test can never disagree with the library about what a
 * well-formed JWK looks like.
 */
class JwksUnitTest {
  /**
   * Mirrors the SDK's own client by installing ContentNegotiation, so the tests that feed a
   * non-JSON `Content-Type` prove [fetchJwks] reaches the body regardless of what is negotiated.
   */
  private fun clientReturning(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: String = "application/json",
  ) = HttpClient(
    MockEngine {
      respond(content = body, status = status, headers = headersOf("Content-Type", contentType))
    },
  ) {
    install(ContentNegotiation) { json() }
  }

  private fun jwks(vararg keys: String) = """{"keys":[${keys.joinToString(",")}]}"""

  @Test
  fun `fetches the well-known endpoint of the domain`() =
    runTest {
      val key = RSAKeyGenerator(2048).keyID("rsa-key").generate().toPublicJWK()
      var requestedUrl: String? = null
      val client =
        HttpClient(
          MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
              content = jwks(key.toJSONString()),
              status = HttpStatusCode.OK,
              headers = headersOf("Content-Type", "application/json"),
            )
          },
        ) {
          install(ContentNegotiation) { json() }
        }

      val jwks = fetchJwks(client, "example.idura.broker")

      assertEquals("https://example.idura.broker/.well-known/jwks.json", requestedUrl)
      assertEquals(1, jwks.keys.size)
      assertEquals("rsa-key", jwks.getKeyByKeyId("rsa-key")?.keyID)
    }

  @Test
  fun `parses an RSA key matching the original keypair`() =
    runTest {
      val generated = RSAKeyGenerator(2048).keyID("rsa-key").generate()

      val jwks = fetchJwks(clientReturning(jwks(generated.toPublicJWK().toJSONString())), DOMAIN)

      val parsed = jwks.getKeyByKeyId("rsa-key") as com.nimbusds.jose.jwk.RSAKey
      assertEquals(generated.modulus, parsed.modulus)
      assertEquals(generated.publicExponent, parsed.publicExponent)
    }

  @Test
  fun `ignores unknown members and key types it cannot verify with`() =
    runTest {
      // A JWKS may legitimately carry keys the SDK has no use for. Nimbus parses an Ed25519 key
      // fine, so it must survive the document and only fail if it is the key a token names.
      val ed25519 =
        """
        {"kty":"OKP","kid":"ed-key","crv":"Ed25519",
         "x":"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"}
        """.trimIndent()
      val rsa =
        RSAKeyGenerator(2048)
          .keyID("rsa-key")
          .generate()
          .toPublicJWK()
          .toJSONString()
          .replace("""{"kty"""", """{"unknown_member":"ignored","kty"""")

      val jwks = fetchJwks(clientReturning(jwks(ed25519, rsa)), DOMAIN)

      assertEquals(listOf("ed-key", "rsa-key"), jwks.keys.map { it.keyID })
      val ex =
        runCatching { verificationAlgorithm(jwks.getKeyByKeyId("ed-key")!!) }.exceptionOrNull()
      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("unsupported key type 'OKP'"))
    }

  @Test
  fun `looking up an unknown key id returns null`() =
    runTest {
      val key = RSAKeyGenerator(2048).keyID("rsa-key").generate().toPublicJWK()

      val jwks = fetchJwks(clientReturning(jwks(key.toJSONString())), DOMAIN)

      assertNull(jwks.getKeyByKeyId("some-other-key"))
    }

  // region Algorithm selection

  @Test
  fun `derives RS256 for an RSA key that declares no algorithm`() =
    runTest {
      val key = RSAKeyGenerator(2048).keyID("rsa-key").generate().toPublicJWK()

      val jwks = fetchJwks(clientReturning(jwks(key.toJSONString())), DOMAIN)

      assertNull("fixture should not declare an alg", jwks.keys[0].algorithm)
      assertEquals("RS256", verificationAlgorithm(jwks.keys[0]).name)
    }

  @Test
  fun `honours the algorithm an RSA key declares`() =
    runTest {
      // Idura signs with RS256 today, but a broker rotating to a stronger hash publishes it in
      // the JWK's own `alg`. Pinning RS256 regardless would fail every login with an
      // unactionable error, so the declared algorithm wins — against a fixed allowlist.
      for (algorithm in listOf("RS256", "RS384", "RS512")) {
        val key =
          RSAKeyGenerator(2048)
            .keyID("rsa-key")
            .algorithm(JwkAlgorithm(algorithm))
            .generate()
            .toPublicJWK()

        val jwks = fetchJwks(clientReturning(jwks(key.toJSONString())), DOMAIN)

        assertEquals(algorithm, verificationAlgorithm(jwks.keys[0]).name)
      }
    }

  @Test
  fun `rejects an RSA key declaring an algorithm outside the allowlist`() =
    runTest {
      // `alg` is issuer-controlled, but a symmetric algorithm on an RSA key is nonsense and must
      // not be honoured just because the document said so.
      val key =
        RSAKeyGenerator(2048)
          .keyID("rsa-key")
          .algorithm(JwkAlgorithm("HS256"))
          .generate()
          .toPublicJWK()

      val jwks = fetchJwks(clientReturning(jwks(key.toJSONString())), DOMAIN)

      val ex = runCatching { verificationAlgorithm(jwks.keys[0]) }.exceptionOrNull()
      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("unsupported algorithm 'HS256'"))
    }

  @Test
  fun `derives ES256 from the P-256 curve`() = assertCurveMapsTo(Curve.P_256, "ES256")

  @Test
  fun `derives ES384 from the P-384 curve`() = assertCurveMapsTo(Curve.P_384, "ES384")

  @Test
  fun `derives ES512 from the P-521 curve`() = assertCurveMapsTo(Curve.P_521, "ES512")

  /**
   * Asserts the curve alone selects the ECDSA variant (RFC 7518 §3.4 pins one hash per curve),
   * and that a token signed with the key actually verifies under it. One test per curve, so a
   * regression on P-521 — the curve most likely to break coordinate handling — cannot be hidden
   * by an earlier curve failing first.
   */
  private fun assertCurveMapsTo(
    curve: Curve,
    expected: String,
  ) = runTest {
    val generated = ECKeyGenerator(curve).keyID("ec-key").generate()

    val jwks = fetchJwks(clientReturning(jwks(generated.toPublicJWK().toJSONString())), DOMAIN)
    val algorithm = verificationAlgorithm(jwks.getKeyByKeyId("ec-key")!!)

    assertEquals(expected, algorithm.name)

    val token =
      SignedJWT(
        JWSHeader.Builder(JWSAlgorithm.parse(expected)).keyID("ec-key").build(),
        JWTClaimsSet
          .Builder()
          .subject("user-1")
          .expirationTime(Date(System.currentTimeMillis() + 60_000))
          .build(),
      ).apply { sign(ECDSASigner(generated)) }.serialize()

    // Throws if the algorithm cannot verify the signature it was derived for.
    com.auth0.jwt.JWT
      .require(algorithm)
      .build()
      .verify(token)
  }

  @Test
  fun `rejects an EC key on a curve it cannot verify with`() =
    runTest {
      // secp256k1 is a curve Nimbus models but RFC 7518 gives no ECDSA variant for. Nimbus also
      // validates the point against the named curve, so a relabelled P-256 key is refused while
      // the document is parsed and never reaches the algorithm mapping — the key is unusable
      // either way, which is what matters here.
      val key = ECKeyGenerator(Curve.P_256).keyID("ec-key").generate().toPublicJWK()
      val secp256k1 = key.toJSONString().replace("\"crv\":\"P-256\"", "\"crv\":\"secp256k1\"")

      val ex =
        runCatching { fetchJwks(clientReturning(jwks(secp256k1)), DOMAIN) }.exceptionOrNull()
      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to parse JWKS"))
    }

  @Test
  fun `rejects a well-formed key on a curve outside the allowlist`() =
    runTest {
      // The test above never reaches the curve mapping, because a relabelled key fails validation
      // first. A key that is genuinely valid on an unsupported curve does reach it, which is the
      // case that matters: the curve is refused on its own merits, not as a side effect of bad
      // coordinates. secp256k1 is the realistic instance — widely deployed, modelled by Nimbus,
      // and given no ECDSA variant by RFC 7518.
      //
      // Hand-encoded rather than generated, unlike every other fixture here: SunEC cannot generate
      // on secp256k1, so the coordinates are secp256k1's own generator point G, which is valid on
      // the curve by definition.
      val generatorX = "eb5mfvncu6xVoGKVzocLBwKb_NstzijZWfKBWxb4F5g"
      val generatorY = "SDradyajxGVdpPv8DhEIqP0XtEimhVQZnEfQj_sQ1Lg"
      val key =
        """
        {"kty":"EC","kid":"ec-key","crv":"secp256k1","x":"$generatorX","y":"$generatorY"}
        """.trimIndent()

      // Parsing has to succeed, or the test would prove nothing the one above does not.
      val jwks = fetchJwks(clientReturning(jwks(key)), DOMAIN)

      val ex =
        runCatching { verificationAlgorithm(jwks.getKeyByKeyId("ec-key")!!) }.exceptionOrNull()
      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("unsupported curve 'secp256k1'"))
    }

  // endregion

  // region Malformed key material

  @Test
  fun `rejects an EC key whose coordinates are not on the curve`() =
    runTest {
      // The degenerate point (0,0) is off every curve, but JCA's KeyFactory accepts it and hands
      // back a usable-looking key, so nothing downstream would notice. Rejecting it is the whole
      // reason key parsing is delegated rather than hand-rolled.
      val zeroCoordinate = "A".repeat(43)
      val degenerate =
        """{"kty":"EC","kid":"ec-key","crv":"P-256","x":"$zeroCoordinate","y":"$zeroCoordinate"}"""

      val ex =
        runCatching { fetchJwks(clientReturning(jwks(degenerate)), DOMAIN) }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to parse JWKS"))
      assertTrue(ex.cause!!.message!!.contains("not on the P-256 curve"))
    }

  @Test
  fun `throws when a key is missing its type`() =
    runTest {
      val ex =
        runCatching {
          fetchJwks(clientReturning(jwks("""{"kid":"no-kty"}""")), DOMAIN)
        }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to parse JWKS"))
    }

  @Test
  fun `rejects the whole document when one key is malformed`() =
    runTest {
      // Nimbus validates every key up front, so one malformed entry fails the document even when
      // the key a token names is fine. Stricter than parsing each key on first use, and the
      // trade-off is deliberate: a JWKS the library cannot fully vouch for is not trusted.
      val good =
        RSAKeyGenerator(2048)
          .keyID("rsa-key")
          .generate()
          .toPublicJWK()
          .toJSONString()
      val malformed = """{"kty":"EC","kid":"ec-key","crv":"P-256","x":"abc","y":"def"}"""

      val ex =
        runCatching { fetchJwks(clientReturning(jwks(malformed, good)), DOMAIN) }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to parse JWKS"))
    }

  // endregion

  // region Transport and document level failures

  @Test
  fun `throws when the endpoint returns an error status`() =
    runTest {
      val client =
        HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }) {
          install(ContentNegotiation) { json() }
        }

      val ex = runCatching { fetchJwks(client, DOMAIN) }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to fetch JWKS"))
    }

  @Test
  fun `throws when the document contains no keys`() =
    runTest {
      val ex =
        runCatching { fetchJwks(clientReturning("""{"keys":[]}"""), DOMAIN) }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("No keys found"))
    }

  @Test
  fun `throws when the document has no keys member at all`() =
    runTest {
      val ex = runCatching { fetchJwks(clientReturning("""{}"""), DOMAIN) }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to parse JWKS"))
    }

  @Test
  fun `throws when a 200 response is not JSON at all`() =
    runTest {
      // A captive portal or misconfigured domain answers 200 with HTML.
      val client = clientReturning("<html>captive portal</html>", contentType = "text/html")

      val ex = runCatching { fetchJwks(client, DOMAIN) }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to parse JWKS"))
    }

  @Test
  fun `parses a JWKS served with a non-JSON content type`() =
    runTest {
      // RFC 7517 defines `application/jwk-set+json`, but brokers serve JWKS as `text/plain` or
      // with no Content-Type at all. The body is read as text precisely so a valid document is
      // not rejected over its declared type — negotiating would fail this as unparseable.
      val key = RSAKeyGenerator(2048).keyID("rsa-key").generate().toPublicJWK()

      for (contentType in listOf("text/plain", "application/jwk-set+json")) {
        val jwks =
          fetchJwks(clientReturning(jwks(key.toJSONString()), contentType = contentType), DOMAIN)

        assertEquals("rsa-key", jwks.keys.single().keyID)
      }
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
      val deferred = scope.async { fetchJwks(client, DOMAIN) }
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

      val ex = runCatching { fetchJwks(client, DOMAIN) }.exceptionOrNull()

      assertTrue(ex is IduraVerifyInternalException)
      assertTrue(ex!!.message!!.contains("Failed to fetch JWKS"))
    }

  // endregion

  private companion object {
    const val DOMAIN = "example.idura.broker"
  }
}
