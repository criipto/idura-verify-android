package eu.idura.verify

import com.auth0.jwt.interfaces.DecodedJWT
import java.time.Instant
import java.util.Date

class JWT(
  val decodedJWT: DecodedJWT,
) {
  val token: String = decodedJWT.token

  val subject: String = decodedJWT.subject

  val audience: String? = decodedJWT.audience.firstOrNull()

  val issuer: String = decodedJWT.issuer

  val issuedAt: Date = decodedJWT.issuedAt

  val expireAt: Date = decodedJWT.expiresAt

  val notBefore: Date = decodedJWT.notBefore

  // Finding the identity scheme claim requires parsing the entire JWT, so do it lazily
  val identityScheme: String by lazy { decodedJWT.getClaim("identityscheme").asString()!! }

  fun getClaimAsString(claim: String): String? = decodedJWT.getClaim(claim).asString()

  fun getClaimAsBoolean(claim: String): Boolean? = decodedJWT.getClaim(claim).asBoolean()

  fun getClaimAsLong(claim: String): Long? = decodedJWT.getClaim(claim).asLong()

  fun getClaimAsDouble(claim: String): Double? = decodedJWT.getClaim(claim).asDouble()

  fun getClaimAsInt(claim: String): Int? = decodedJWT.getClaim(claim).asInt()

  fun getClaimAsDate(claim: String): Date? = decodedJWT.getClaim(claim).asDate()

  fun getClaimAsInstant(claim: String): Instant? = decodedJWT.getClaim(claim).asInstant()

  fun getClaimAsMap(claim: String): Map<String, Any>? = decodedJWT.getClaim(claim).asMap()
}
