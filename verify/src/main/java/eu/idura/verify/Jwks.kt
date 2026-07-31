package eu.idura.verify

import com.auth0.jwt.algorithms.Algorithm
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * The algorithm an ID token signed with [key] has to be verified with.
 *
 * It is derived from the key material and from the JWK's own `alg` member, never from the `alg`
 * header of the token, so that a token cannot nominate the algorithm it is checked against. The
 * JWK is issuer-controlled metadata fetched over TLS, unlike the token header, so honouring its
 * `alg` is safe — but it is still only honoured against a fixed allowlist.
 *
 * A JWKS may legitimately carry keys the SDK cannot verify with, so an unsupported key type,
 * curve or algorithm fails when that specific key is used rather than when the document is
 * parsed.
 */
internal fun verificationAlgorithm(key: JWK): Algorithm =
  when (key) {
    is RSAKey -> {
      // RSA key material carries no hint of its hash, so the JWK's `alg` decides. It is an
      // optional member, and Idura signs ID tokens with RS256, which is what its absence means.
      val publicKey = key.jcaKey { key.toRSAPublicKey() }
      when (val algorithm = key.algorithm?.name) {
        null, "RS256" -> {
          Algorithm.RSA256(publicKey)
        }

        "RS384" -> {
          Algorithm.RSA384(publicKey)
        }

        "RS512" -> {
          Algorithm.RSA512(publicKey)
        }

        else -> {
          throw IduraVerifyInternalException(
            "RSA key ${key.keyID} has unsupported algorithm '$algorithm', " +
              "expected one of [RS256, RS384, RS512]",
          )
        }
      }
    }

    is ECKey -> {
      // RFC 7518 §3.4 pins one hash per curve, so the curve alone determines the variant.
      val publicKey = key.jcaKey { key.toECPublicKey() }
      when (key.curve) {
        Curve.P_256 -> {
          Algorithm.ECDSA256(publicKey)
        }

        Curve.P_384 -> {
          Algorithm.ECDSA384(publicKey)
        }

        Curve.P_521 -> {
          Algorithm.ECDSA512(publicKey)
        }

        else -> {
          throw IduraVerifyInternalException(
            "EC key ${key.keyID} has unsupported curve '${key.curve}', " +
              "expected one of [P-256, P-384, P-521]",
          )
        }
      }
    }

    else -> {
      throw IduraVerifyInternalException(
        "JWT signing key ${key.keyID} has unsupported key type '${key.keyType}', " +
          "expected 'RSA' or 'EC'",
      )
    }
  }

/**
 * Converts the JWK's key material into a JCA key, mapping Nimbus's checked failure onto the SDK's
 * own exception type. [JWKSet.parse] already validates the members, so this only fires if the
 * platform's JCA provider rejects a key Nimbus itself accepted.
 */
private fun <T> JWK.jcaKey(convert: () -> T): T =
  try {
    convert()
  } catch (ex: JOSEException) {
    throw IduraVerifyInternalException("Invalid public key in JWK $keyID", cause = ex)
  }

/**
 * Fetches the signing keys published at the well-known JWKS endpoint of [domain], using
 * [httpClient] for the request.
 */
internal suspend fun fetchJwks(
  httpClient: HttpClient,
  domain: String,
): JWKSet {
  val url = "https://$domain/.well-known/jwks.json"

  // Ktor surfaces failures as a wide range of unrelated types — IOException from the engine, a
  // timeout, an engine specific wrapper — so catch broadly to keep the promise that every SDK
  // failure arrives as an IduraVerifyException. CancellationException is itself an Exception, and
  // swallowing it would turn Activity teardown mid-login into a bogus "Failed to fetch JWKS", so
  // it is let through.
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

  val jwks =
    try {
      // Read the body as text instead of going through ContentNegotiation: a JWKS served as
      // `text/plain`, or with no Content-Type at all, is still a JWKS, and negotiating on the
      // declared type would reject it before it was ever parsed.
      JWKSet.parse(response.bodyAsText())
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      throw IduraVerifyInternalException("Failed to parse JWKS from $url", cause = ex)
    }

  if (jwks.keys.isEmpty()) {
    throw IduraVerifyInternalException("No keys found in JWKS at $url")
  }

  return jwks
}
