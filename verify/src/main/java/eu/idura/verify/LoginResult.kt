package eu.idura.verify

/**
 * Result of a successful [IduraVerify.login] call.
 *
 * The [traceId] uniquely identifies the OpenTelemetry trace covering the login flow and can
 * be passed back to Idura support to correlate the call with server-side logs. It is also
 * available on [IduraVerifyException.traceId] for failed logins.
 */
class LoginResult(
  val jwt: JWT,
  val traceId: String,
)
