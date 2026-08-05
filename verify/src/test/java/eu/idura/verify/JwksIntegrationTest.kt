package eu.idura.verify

import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.RSAKey
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fetches the JWKS Idura actually publishes, over the network, using the same Ktor client
 * configuration the SDK ships.
 *
 * [JwksUnitTest] pins the contract against fixtures this repository generates itself, which cannot
 * catch the failure mode that matters most here: Idura changing what it publishes — a rotated key
 * type, a curve, an `alg` member, an unexpected `Content-Type` — in a way the SDK cannot parse.
 * Every consumer's login breaks the moment that happens, so it is worth one test that talks to the
 * real endpoint.
 *
 * Assertions are deliberately about what the SDK requires rather than about the keys currently
 * published, so rotating a key does not fail the build.
 */
class JwksIntegrationTest {
  private fun client() =
    HttpClient(Android) {
      install(ContentNegotiation) { json() }
    }

  @Test
  fun `fetches and parses the JWKS Idura publishes`() =
    runTest {
      val jwks = fetchJwks(client(), DOMAIN)

      assertTrue("expected at least one key from $DOMAIN", jwks.keys.isNotEmpty())

      // Deriving an algorithm for every published key is the assertion that matters: it fails if a
      // rotated key uses a type, curve or `alg` this SDK cannot verify with, which would otherwise
      // only surface as a failed login in production.
      for (key in jwks.keys) {
        val algorithm = verificationAlgorithm(key)

        assertTrue(
          "key ${key.keyID} mapped to unexpected algorithm ${algorithm.name}",
          algorithm.name in SUPPORTED_ALGORITHMS,
        )

        // Nimbus parses the JWK members lazily, so force the conversion to a JCA key to prove the
        // platform can actually rebuild the key rather than just that the document parsed.
        when (key) {
          is RSAKey -> key.toRSAPublicKey()
          is ECKey -> key.toECPublicKey()
          else -> error("unreachable: verificationAlgorithm would have thrown")
        }
      }
    }

  private companion object {
    /**
     * The tenant the build targets, injected from the `iduraDomain` Gradle property so this test
     * follows the same domain as the example app.
     */
    val DOMAIN: String = System.getProperty("IDURA_DOMAIN") ?: error("IDURA_DOMAIN is not set")

    val SUPPORTED_ALGORITHMS =
      setOf("RS256", "RS384", "RS512", "ES256", "ES384", "ES512")
  }
}
