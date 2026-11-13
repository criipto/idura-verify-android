package eu.idura.verify

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationManagementRequest
import net.openid.appauth.AuthorizationManagementResponse
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.EndSessionResponse
import org.json.JSONException
import org.json.JSONObject

/**
 * A Kotlin port of AppAuth's `AuthorizationManagementActivity` (0.11.1), so that the commits after
 * it can change it in steps. It keeps the back stack below the browser tab: it opens the tab when
 * it first resumes, and on its next resume reports the response URI the receiver handed it, or a
 * cancel when there is none.
 *
 * AppAuth's `AuthorizationManagementUtil` is package-private, so its helpers are inlined below.
 */
internal class CustomTabActivity : AppCompatActivity() {
  private var authorizationStarted = false
  private var authIntent: Intent? = null
  private var authRequest: AuthorizationManagementRequest? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    extractState(savedInstanceState ?: intent.extras)
  }

  override fun onResume() {
    super.onResume()

    if (!authorizationStarted) {
      try {
        startActivity(authIntent)
        authorizationStarted = true
      } catch (e: ActivityNotFoundException) {
        handleBrowserNotFound()
        finish()
      }
      return
    }

    if (intent.data != null) {
      handleAuthorizationComplete()
    } else {
      handleAuthorizationCanceled()
    }
    finish()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(KEY_AUTHORIZATION_STARTED, authorizationStarted)
    outState.putParcelable(KEY_AUTH_INTENT, authIntent)
    outState.putString(KEY_AUTH_REQUEST, authRequest?.jsonSerializeString())
    outState.putString(KEY_AUTH_REQUEST_TYPE, authRequest?.let(::requestTypeFor))
  }

  private fun handleAuthorizationComplete() {
    val responseUri = intent.data!!
    val responseData = extractResponseData(responseUri)
    responseData.setData(responseUri)
    setResult(RESULT_OK, responseData)
  }

  private fun handleAuthorizationCanceled() {
    Log.d(TAG, "Authorization flow canceled by user")
    val cancelData =
      AuthorizationException
        .fromTemplate(AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW, null)
        .toIntent()
    setResult(RESULT_CANCELED, cancelData)
  }

  private fun handleBrowserNotFound() {
    Log.d(TAG, "Authorization flow canceled due to missing browser")
    val cancelData =
      AuthorizationException
        .fromTemplate(AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW, null)
        .toIntent()
    setResult(RESULT_CANCELED, cancelData)
  }

  private fun extractState(state: Bundle?) {
    if (state == null) {
      Log.w(TAG, "No stored state - unable to handle response")
      finish()
      return
    }

    authIntent = BundleCompat.getParcelable(state, KEY_AUTH_INTENT, Intent::class.java)
    authorizationStarted = state.getBoolean(KEY_AUTHORIZATION_STARTED, false)
    try {
      val authRequestJson = state.getString(KEY_AUTH_REQUEST, null)
      val authRequestType = state.getString(KEY_AUTH_REQUEST_TYPE, null)
      authRequest = authRequestJson?.let { requestFrom(it, authRequestType) }
    } catch (ex: JSONException) {
      setResult(
        RESULT_CANCELED,
        AuthorizationException.AuthorizationRequestErrors.INVALID_REQUEST.toIntent(),
      )
    }
  }

  private fun extractResponseData(responseUri: Uri): Intent {
    if (responseUri.queryParameterNames.contains(AuthorizationException.PARAM_ERROR)) {
      return AuthorizationException.fromOAuthRedirect(responseUri).toIntent()
    }

    val request = authRequest!!
    val response = responseWith(request, responseUri)

    if (request.state != response.state) {
      Log.w(
        TAG,
        "State returned in authorization response (${response.state}) does not match state " +
          "from request (${request.state}) - discarding response",
      )
      return AuthorizationException.AuthorizationRequestErrors.STATE_MISMATCH.toIntent()
    }

    return response.toIntent()
  }

  companion object {
    private const val KEY_AUTH_INTENT = "authIntent"
    private const val KEY_AUTH_REQUEST = "authRequest"
    private const val KEY_AUTH_REQUEST_TYPE = "authRequestType"
    private const val KEY_AUTHORIZATION_STARTED = "authStarted"

    private const val REQUEST_TYPE_AUTHORIZATION = "authorization"
    private const val REQUEST_TYPE_END_SESSION = "end_session"

    fun createStartForResultIntent(
      context: Context,
      request: AuthorizationManagementRequest,
      authIntent: Intent,
    ): Intent =
      createBaseIntent(context)
        .putExtra(KEY_AUTH_INTENT, authIntent)
        .putExtra(KEY_AUTH_REQUEST, request.jsonSerializeString())
        .putExtra(KEY_AUTH_REQUEST_TYPE, requestTypeFor(request))

    fun createResponseHandlingIntent(
      context: Context,
      responseUri: Uri?,
    ): Intent =
      createBaseIntent(context)
        .setData(responseUri)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun createBaseIntent(context: Context) = Intent(context, CustomTabActivity::class.java)

    private fun requestTypeFor(request: AuthorizationManagementRequest): String? =
      when (request) {
        is AuthorizationRequest -> REQUEST_TYPE_AUTHORIZATION
        is EndSessionRequest -> REQUEST_TYPE_END_SESSION
        else -> null
      }

    private fun requestFrom(
      jsonStr: String,
      type: String?,
    ): AuthorizationManagementRequest {
      val json = JSONObject(jsonStr)
      return when (type) {
        REQUEST_TYPE_AUTHORIZATION -> AuthorizationRequest.jsonDeserialize(json)
        REQUEST_TYPE_END_SESSION -> EndSessionRequest.jsonDeserialize(json)
        else -> throw IllegalArgumentException(
          "No AuthorizationManagementRequest found matching to this json schema",
        )
      }
    }

    private fun responseWith(
      request: AuthorizationManagementRequest,
      uri: Uri,
    ): AuthorizationManagementResponse =
      when (request) {
        is AuthorizationRequest -> AuthorizationResponse.Builder(request).fromUri(uri).build()
        is EndSessionRequest ->
          EndSessionResponse
            .Builder(request)
            .setState(uri.getQueryParameter("state"))
            .build()
        else -> throw IllegalArgumentException("Malformed request or uri")
      }
  }
}
