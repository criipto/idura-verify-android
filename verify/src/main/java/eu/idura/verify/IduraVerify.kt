package eu.idura.verify

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.algorithms.Algorithm
import eu.idura.verify.eid.DanishMitID
import eu.idura.verify.eid.EID
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parametersOf
import io.ktor.serialization.kotlinx.json.json
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.openid.appauth.AuthorizationManagementRequest
import net.openid.appauth.AuthorizationManagementResponse
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.EndSessionResponse
import net.openid.appauth.ResponseTypeValues
import java.security.interfaces.RSAPublicKey
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.minutes
import com.auth0.jwt.JWT as Auth0JWT
import io.opentelemetry.context.Context as OtelContext

internal const val TAG = "IduraVerify"

enum class Prompt(
  internal val str: String,
) {
  Login("login"),
  None("none"),
  Consent("consent"),
  ConsentRevoke("consent_revoke"),
}

enum class Action {
  Login,
  Confirm,
  Accept,
  Approve,
  Sign,
}

class NoSuitableBrowserException : Exception("No suitable browsers found")

class IduraVerify(
  private val clientID: String,
  private val domain: String,
  private val redirectUri: Uri = "https://$domain/android/callback".toUri(),
  private val appSwitchUri: Uri? = "https://$domain/android/callback/appswitch".toUri(),
  private val activity: ComponentActivity,
) : DefaultLifecycleObserver {
  private val httpClient =
    HttpClient(Android) {
      install(ContentNegotiation) {
        json()
      }
    }

  private val tracing = Tracing(domain, httpClient)
  private val tracer =
    tracing.getTracer(BuildConfig.LIBRARY_PACKAGE_NAME, BuildConfig.VERSION)

  private val getIduraJWKS = cacheResult(activity.lifecycleScope, this::loadIduraJWKS)
  private val getIduraOIDCConfiguration =
    cacheResult(activity.lifecycleScope, this::loadIduraOIDCConfiguration)

  private val browserManager = BrowserManager(activity, redirectUri)

  init {
    for (uri in listOf(redirectUri, appSwitchUri)) {
      if (uri != null && uri.scheme != "https") {
        throw Exception("redirectUri and appSwitchUri must be HTTPS URIs")
      }
    }

    activity.lifecycle.addObserver(this)

    // Load the OIDC config and JWKS configuration, so it is ready when the user initiates a login
    activity.lifecycleScope.launch {
      async { runCatching { getIduraOIDCConfiguration() } }
      async { runCatching { getIduraJWKS() } }
    }
  }

  override fun onCreate(owner: LifecycleOwner) {
    verifyAppLink(redirectUri)
    if (appSwitchUri != null) {
      verifyAppLink(appSwitchUri)
    }
  }

  /**
   * Verify that app links are correctly configured to open in the consuming application.
   */
  private fun verifyAppLink(uri: Uri) {
    val intent =
      Intent().apply {
        data = uri
        action = Intent.ACTION_VIEW
        addCategory(Intent.CATEGORY_DEFAULT)
        addCategory(Intent.CATEGORY_BROWSABLE)
        `package` = activity.packageName
      }

    if (intent.resolveActivity(activity.packageManager) == null) {
      Log.w(TAG, "App link is not correctly configured for $uri")
    }
  }

  override fun onDestroy(owner: LifecycleOwner) {
    tracing.close()
    httpClient.close()
  }

  /**
   * Start a login, returning the JWT as a string once the flow is complete.
   *
   * The SDK provides builder classes for some of the eIDs supported by Idura Verify. You should use these when possible, since they provide helper methods for the scopes and login hints supported by the specific eID provider. For example, Danish MitID supports SSN prefilling, which you can access using the `prefillSsn` method.
   *
   * @param eid The eID to login with.
   * @param prompt The OIDC prompt, see https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest
   *
   * @return The JWT as a string.
   *
   * @sample eu.idura.verify.samples.loginSample1
   */
  suspend fun login(
    eid: EID<*>,
    prompt: Prompt? = null,
  ): JWT =
    tracer
      .spanBuilder(
        "android sdk login",
      ).setAttribute("acr_value", eid.acrValue)
      .startAndRun {
        Log.i(
          TAG,
          "Starting login with ${eid.acrValue}, traceId ${Span.current().spanContext.traceId}",
        )

        if (!browserManager.foundASuitableBrowser) {
          throw NoSuitableBrowserException()
        }

        val loginHints =
          (
            mutableSetOf(
              "mobile:continue_button:never",
            ) + eid.loginHints
          ) as MutableSet<String>

        if (eid is DanishMitID && appSwitchUri != null) {
          loginHints.add("appswitch:android")
          loginHints.add("appswitch:resumeUrl:$appSwitchUri")
        }

        val scopes = eid.scopes + listOf("openid")

        if (eid.action != null) {
          loginHints.add("action:${eid.action!!.name.lowercase()}")
        }

        val authorizationRequest =
          AuthorizationRequest
            .Builder(
              getIduraOIDCConfiguration(),
              clientID,
              ResponseTypeValues.CODE,
              redirectUri,
            ).setScope(scopes.joinToString(" "))
            .setAdditionalParameters(mapOf("acr_values" to eid.acrValue))
            .setLoginHint(loginHints.joinToString(" "))
            .setPrompt(prompt?.str)
            .build()

        val parRequestUri = pushAuthorizationRequest(authorizationRequest)

        val callbackUri = launchBrowser(authorizationRequest, parRequestUri)

        exchangeCode(authorizationRequest, callbackUri)
      }

  private suspend fun exchangeCode(
    request: AuthorizationRequest,
    callbackUri: Uri,
  ): JWT {
    val tokenResponse =
      tracer.spanBuilder("code exchange").startAndRun {
        val response =
          AuthorizationResponse
            .Builder(request)
            .fromUri(callbackUri)
            .build()

        if (!validateState(request, response)) {
          throw Exception("State mismatch")
        }

        suspendCoroutine { continuation ->
          browserManager.authorizationService.performTokenRequest(
            response.createTokenExchangeRequest(),
          ) { tokenResponse, ex ->
            if (ex != null) {
              continuation.resumeWithException(ex)
            } else {
              // From TokenResponseCallback - Exactly one of `response` or `ex` will be non-null. So
              // when we reach this line, we know that response is not null.
              continuation.resume(tokenResponse!!)
            }
          }
        }
      }

    return tracer.spanBuilder("JWT verification").startAndRun {
      val idToken = tokenResponse.idToken!!
      val decodedJWT = Auth0JWT.decode(idToken)

      val keyId = decodedJWT.getHeaderClaim("kid").asString()
      val key = getIduraJWKS().find { it.id == keyId }

      if (key == null) {
        throw Exception("Unknown key $keyId")
      }

      val algorithm = Algorithm.RSA256(key.publicKey as RSAPublicKey)
      val verifier =
        Auth0JWT
          .require(algorithm)
          .withIssuer("https://$domain")
          // Add five minutes of leeway when validating nbf and iat.
          .acceptLeeway(5.minutes.inWholeSeconds)
          .build()

      verifier.verify(idToken)
      return@startAndRun JWT(decodedJWT)
    }
  }

  private fun validateState(
    request: AuthorizationManagementRequest,
    response: AuthorizationManagementResponse,
  ): Boolean {
    if (request.state != response.state) {
      Log.w(
        TAG,
        "State returned in authorization response (${response.state}) does not match state from request (${request.state}) - discarding response",
      )
      return false
    }
    return true
  }

  suspend fun logout(idToken: String?) {
    val endSessionRequest =
      EndSessionRequest
        .Builder(
          getIduraOIDCConfiguration(),
        ).setIdTokenHint(idToken)
        .setPostLogoutRedirectUri(redirectUri)
        .build()

    val callbackUri = launchBrowser(endSessionRequest)

    val response =
      EndSessionResponse
        .Builder(endSessionRequest)
        .setState(
          callbackUri.getQueryParameter(
            "state",
          ),
        ).build()

    validateState(endSessionRequest, response)
  }

  /**
   * Starts the PAR flow, as described in https://datatracker.ietf.org/doc/html/rfc9126
   */
  private suspend fun pushAuthorizationRequest(authorizationRequest: AuthorizationRequest): Uri {
    val authorizationRequestUri = authorizationRequest.toUri()
    val response =
      httpClient.submitForm(
        url =
          getIduraOIDCConfiguration()
            .discoveryDoc!!
            .docJson
            .get(
              "pushed_authorization_request_endpoint",
            ).toString(),
        formParameters =
          parametersOf(
            authorizationRequestUri.queryParameterNames.associateWith { key ->
              authorizationRequestUri.getQueryParameters(key)
            },
          ),
      ) {
        tracing.propagators().textMapPropagator.inject(
          OtelContext.current(),
          this,
          KtorRequestSetter,
        )
      }

    if (response.status.value != 201) {
      throw Error(
        "Error during PAR request ${response.status.value} ${response.status.description}",
      )
    }

    @Serializable()
    data class ParResponse(
      val request_uri: String,
      val expires_in: Int,
    )
    val parsedResponse = response.body<ParResponse>()

    return getIduraOIDCConfiguration()
      .authorizationEndpoint
      .buildUpon()
      .appendQueryParameter("client_id", clientID)
      .appendQueryParameter(
        "request_uri",
        parsedResponse.request_uri,
      ).build()
  }

  private suspend fun launchBrowser(
    request: AuthorizationManagementRequest,
    uri: Uri = request.toUri(),
  ): Uri =
    tracer
      .spanBuilder("launch browser")
      .setAttribute("browser", browserManager.browserDescription)
      .startAndRun {
        browserManager.launchBrowser(request, uri)
      }

  private suspend fun loadIduraJWKS() =
    withContext(Dispatchers.IO) {
      UrlJwkProvider(domain).all
    }

  private suspend fun loadIduraOIDCConfiguration(): AuthorizationServiceConfiguration =
    suspendCoroutine { continuation ->
      AuthorizationServiceConfiguration.fetchFromIssuer(
        "https://$domain".toUri(),
      ) { serviceConfiguration, ex ->
        if (ex != null) {
          Log.e(TAG, "Failed to fetch OIDC configuration", ex)
          continuation.resumeWithException(ex)
        } else {
          Log.d(TAG, "Fetched OIDC configuration")
          continuation.resume(serviceConfiguration!!)
        }
      }
    }
}

internal fun <T> cacheResult(
  scope: CoroutineScope,
  load: suspend () -> T,
): suspend () -> T {
  var cachedDeferred: Deferred<T>? = null
  return {
    // If there is currently no cached deferred, or if the current cached deferred has failed, create a new one
    if (cachedDeferred == null || cachedDeferred?.isCancelled == true) {
      cachedDeferred =
        scope.async {
          load()
        }
    }

    cachedDeferred.await()
  }
}
