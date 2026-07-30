package eu.idura.verify

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * A single signing key from a JWKS document (RFC 7517).
 *
 * Only the members the SDK needs to verify an ID token signature are modelled; everything else
 * in the document (`alg`, `use`, `x5c`, ...) is ignored. Idura signs ID tokens with RS256, so
 * [publicKey] only supports `RSA` keys — a JWKS may still contain keys of other types, which is
 * why an unsupported [keyType] fails when that specific key is used rather than at parse time.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
internal class Jwk(
  @SerialName("kid")
  val id: String? = null,
  @SerialName("kty")
  private val keyType: String,
  @SerialName("n")
  private val modulus: String? = null,
  @SerialName("e")
  private val exponent: String? = null,
) {
  /**
   * The RSA public key this JWK describes, built from the base64url-encoded modulus (`n`) and
   * exponent (`e`).
   */
  val publicKey: RSAPublicKey
    get() {
      if (keyType != "RSA") {
        throw IduraVerifyInternalException(
          "JWT signing key $id has unsupported key type '$keyType', expected 'RSA'",
        )
      }

      val n = modulus ?: throw IduraVerifyInternalException("RSA key $id is missing 'n'")
      val e = exponent ?: throw IduraVerifyInternalException("RSA key $id is missing 'e'")

      return try {
        val decoder = Base64.getUrlDecoder()
        KeyFactory
          .getInstance("RSA")
          .generatePublic(
            // Sign bit 1, since JWK modulus/exponent are unsigned big-endian integers.
            RSAPublicKeySpec(
              BigInteger(1, decoder.decode(n)),
              BigInteger(1, decoder.decode(e)),
            ),
          ) as RSAPublicKey
      } catch (ex: GeneralSecurityException) {
        throw IduraVerifyInternalException("Invalid RSA public key in JWK $id", cause = ex)
      } catch (ex: IllegalArgumentException) {
        // Base64 decoding of a malformed 'n' / 'e'.
        throw IduraVerifyInternalException("Invalid RSA public key in JWK $id", cause = ex)
      }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonIgnoreUnknownKeys
internal class Jwks(
  val keys: List<Jwk> = emptyList(),
)

/**
 * Fetches the signing keys published at the well-known JWKS endpoint of [domain], using
 * [httpClient] for the request.
 */
internal suspend fun fetchJwks(
  httpClient: HttpClient,
  domain: String,
): List<Jwk> {
  val url = "https://$domain/.well-known/jwks.json"

  // Ktor surfaces failures as a wide range of unrelated types — IOException from the engine,
  // ContentConvertException from deserialization, NoTransformationFoundException for a 200 with
  // a non-JSON body — so catch broadly to keep the promise that every SDK failure arrives as an
  // IduraVerifyException. CancellationException is itself an Exception, and swallowing it would
  // turn Activity teardown mid-login into a bogus "Failed to fetch JWKS", so it is let through.
  val response =
    try {
      httpClient.get(url)
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      throw IduraVerifyInternalException("Failed to fetch JWKS from $url", cause = ex)
    }

  if (!response.status.isSuccess()) {
    throw IduraVerifyInternalException("Failed to fetch JWKS from $url: ${response.status}")
  }

  val keys =
    try {
      response.body<Jwks>().keys
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      throw IduraVerifyInternalException("Failed to parse JWKS from $url", cause = ex)
    }

  if (keys.isEmpty()) {
    throw IduraVerifyInternalException("No keys found in JWKS at $url")
  }

  return keys
}
