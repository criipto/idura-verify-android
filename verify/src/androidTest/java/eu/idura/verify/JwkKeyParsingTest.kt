package eu.idura.verify

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Turns JWK members into public keys on a real device, which the JVM unit tests cannot stand in
 * for: Android ships Conscrypt/AndroidOpenSSL rather than the JVM's SunEC, so a provider gap would
 * leave key parsing broken on device while every JVM test passed.
 *
 * Only the key material and the signature verification it feeds are exercised here; JWKS fetching
 * and document level parsing are covered by the unit tests, which are not provider-dependent.
 */
@RunWith(AndroidJUnit4::class)
class JwkKeyParsingTest {
  @Test
  fun parsesEcKeysAndVerifiesSignaturesOnP256() = assertEcCurveRoundTrips(Curve.P_256)

  @Test
  fun parsesEcKeysAndVerifiesSignaturesOnP384() = assertEcCurveRoundTrips(Curve.P_384)

  @Test
  fun parsesEcKeysAndVerifiesSignaturesOnP521() = assertEcCurveRoundTrips(Curve.P_521)

  /**
   * Round-trips a generated keypair through its JWKS representation and verifies a token signed
   * with the private half, so a provider that cannot rebuild the public point on [curve] fails
   * here rather than at a consumer's first login. One test per curve keeps a P-521 regression from
   * being masked by an earlier curve failing first.
   */
  private fun assertEcCurveRoundTrips(curve: Curve) {
    val generated = ECKeyGenerator(curve).keyID(KEY_ID).generate()

    val parsed = JWKSet.parse(jwks(generated.toPublicJWK().toJSONString())).getKeyByKeyId(KEY_ID)!!

    assertEquals(
      "$curve public point",
      generated.toECPublicKey().w,
      (parsed as com.nimbusds.jose.jwk.ECKey).toECPublicKey().w,
    )

    val signing =
      when (curve) {
        Curve.P_256 -> Algorithm.ECDSA256(generated.toECPublicKey(), generated.toECPrivateKey())
        Curve.P_384 -> Algorithm.ECDSA384(generated.toECPublicKey(), generated.toECPrivateKey())
        else -> Algorithm.ECDSA512(generated.toECPublicKey(), generated.toECPrivateKey())
      }

    assertVerifies(signing, verificationAlgorithm(parsed), "$curve")
  }

  @Test
  fun parsesRsaKeysAndVerifiesSignatures() {
    val generated = RSAKeyGenerator(2048).keyID(KEY_ID).generate()

    val parsed = JWKSet.parse(jwks(generated.toPublicJWK().toJSONString())).getKeyByKeyId(KEY_ID)!!

    assertEquals(
      generated.toRSAPublicKey().modulus,
      (parsed as com.nimbusds.jose.jwk.RSAKey).toRSAPublicKey().modulus,
    )

    val signing = Algorithm.RSA256(generated.toRSAPublicKey(), generated.toRSAPrivateKey())
    assertVerifies(signing, verificationAlgorithm(parsed), "RSA")
  }

  /** Signs a token with [signing] and verifies it with [verifying], failing if either rejects. */
  private fun assertVerifies(
    signing: Algorithm,
    verifying: Algorithm,
    what: String,
  ) {
    val token = JWT.create().withIssuer(ISSUER).sign(signing)
    val verified = JWT.require(verifying).build().verify(token)

    assertEquals(what, ISSUER, verified.issuer)
  }

  private fun jwks(key: String) = """{"keys":[$key]}"""

  private companion object {
    const val KEY_ID = "test-key"
    const val ISSUER = "https://example.idura.broker"
  }
}
