package eu.idura.verify

import net.openid.appauth.AuthorizationException

/**
 * Base type for all runtime errors thrown by [IduraVerify]. Catch this to handle any SDK
 * failure uniformly, or catch a more specific subclass to distinguish e.g. user cancellation
 * from a real error.
 *
 * Programming errors detected during construction (`IllegalArgumentException`,
 * `IllegalStateException`) are not wrapped — they continue to use Java's standard exception
 * types because they indicate caller bugs, not runtime conditions.
 */
abstract class IduraVerifyException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The user dismissed the authentication flow before it completed. This is usually a normal
 * action (back button / cancel tap, or `access_denied` returned by the IdP) rather than an
 * error condition; consumers will typically just return the user to whatever screen they were
 * on, without surfacing the exception as an error.
 */
class UserCancelledException : IduraVerifyException("User cancelled login")

/**
 * No browser on the device is capable of running the authentication flow.
 */
class NoSuitableBrowserException : IduraVerifyException("No suitable browser found")

/**
 * The authorization server returned an OAuth/OIDC error response. The [error] code corresponds
 * to the OAuth 2.0 standard error codes (e.g. `invalid_request`, `temporarily_unavailable`),
 * with a human-readable [errorDescription] when provided.
 *
 * Note that the spec-standard `access_denied` error — typically signalling user cancellation
 * at the IdP level — is translated into [UserCancelledException] before reaching this
 * exception, so consumers only need to handle one cancellation type.
 */
class OAuthException(
  val error: String,
  val errorDescription: String? = null,
) : IduraVerifyException(
    buildString {
      append(error)
      if (errorDescription != null) append(" ($errorDescription)")
    },
  )

/**
 * The SDK's underlying machinery failed in a way the consumer can't reasonably recover from
 * — PAR endpoint error, JWT signing-key mismatch, state-parameter mismatch, browser plumbing
 * failure. Treat as an unrecoverable error; surface a generic message to the user and log
 * the cause for investigation.
 */
class IduraVerifyInternalException(
  message: String,
  cause: Throwable? = null,
) : IduraVerifyException(message, cause)

/**
 * Translates AppAuth's [AuthorizationException] from the Custom Tab path into the SDK's own
 * typed hierarchy. User cancellation gets its own type; everything else is opaque to the
 * consumer and surfaces as an internal error with the AppAuth exception preserved as cause.
 */
internal fun AuthorizationException.toIduraVerifyException(): IduraVerifyException =
  if (type == AuthorizationException.TYPE_GENERAL_ERROR &&
    code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code
  ) {
    UserCancelledException()
  } else {
    IduraVerifyInternalException(
      "Browser flow failed: ${errorDescription ?: error ?: "type=$type code=$code"}",
      cause = this,
    )
  }
