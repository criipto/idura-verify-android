package eu.idura.verify

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.browser.auth.AuthTabIntent
import androidx.browser.auth.AuthTabIntent.AuthResult
import androidx.browser.customtabs.CustomTabsClient
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
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
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationManagementActivity
import net.openid.appauth.AuthorizationManagementRequest
import net.openid.appauth.AuthorizationManagementResponse
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.EndSessionResponse
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.browser.AnyBrowserMatcher
import net.openid.appauth.browser.BrowserMatcher
import net.openid.appauth.browser.BrowserSelector
import net.openid.appauth.browser.Browsers
import net.openid.appauth.browser.VersionedBrowserMatcher
import java.security.interfaces.RSAPublicKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.minutes
import android.content.Context as AndroidContext
import com.auth0.jwt.JWT as Auth0JWT
import io.opentelemetry.context.Context as OtelContext

internal const val TAG = "IduraVerify"
internal const val APPSWITCH_QUERY_PARAM = "idura_android_sdk_appswitch"

private const val BRAVE = "com.brave.browser"
private const val EDGE = "com.microsoft.emmx"

private enum class TabType {
  CustomTab(),
  AuthTab(),
}

private sealed class CustomTabResult {
  class CustomTabSuccess(
    val resultUri: Uri,
  ) : CustomTabResult()

  class CustomTabFailure(
    val ex: AuthorizationException,
  ) : CustomTabResult()
}

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
  private val activity: ComponentActivity,
) : DefaultLifecycleObserver {
  private val httpClient =
    HttpClient(Android) {
      install(ContentNegotiation) {
        json()
      }
    }

  /**
   * The AppAuth authorization service, which provides helper methods for OIDC operations, and manages the browser.
   * The service needs access to the activity, so it is initialized in `onCreate`.
   */
  private lateinit var authorizationService: AuthorizationService

  /**
   * The type of browser tab to user, either custom tab or auth tab.
   * Determining the supported browser requires an activity, so this is set in `onCreate`.
   */
  private lateinit var tabType: TabType

  /**
   * An activity result launcher, used to a launch an auth tab intent and listen for the result.
   * See https://developer.android.com/training/basics/intents/result
   */
  private var authTabIntentLauncher: ActivityResultLauncher<Intent?>

  /**
   * An activity result launcher, used to a launch a custom tab intent and listen for the result.
   * See https://developer.android.com/training/basics/intents/result
   */
  private var customTabIntentLauncher:
    ActivityResultLauncher<Pair<AuthorizationManagementRequest, Uri>>

  private val tracing = Tracing(domain, httpClient)
  private val tracer =
    tracing.getTracer(BuildConfig.LIBRARY_PACKAGE_NAME, BuildConfig.VERSION)

  private lateinit var browserDescription: String
  private val getIduraJWKS = cacheResult(activity.lifecycleScope, this::loadIduraJWKS)
  private val getIduraOIDCConfiguration =
    cacheResult(activity.lifecycleScope, this::loadIduraOIDCConfiguration)

  private var foundASuitableBrowser = false

  init {
    if (redirectUri.scheme != "https") {
      throw Exception("redirectUri must be HTTPS URIs")
    }

    if (activity.lifecycle.currentState != Lifecycle.State.INITIALIZED) {
      // We cannot register activity result handlers once the activity has been created, so better to fail early and explicitly
      throw IllegalStateException(
        "Activity must be in ${Lifecycle.State.INITIALIZED.name} state, was ${activity.lifecycle.currentState.name}",
      )
    }

    activity.lifecycle.addObserver(this)

    authTabIntentLauncher =
      AuthTabIntent.registerActivityResultLauncher(activity, this::handleAuthTabResult)

    customTabIntentLauncher =
      activity.registerForActivityResult(
        object :
          ActivityResultContract<Pair<AuthorizationManagementRequest, Uri>, CustomTabResult>() {
          override fun createIntent(
            context: AndroidContext,
            input: Pair<AuthorizationManagementRequest, Uri>,
          ): Intent {
            Log.d(TAG, "Creating custom tab intent")

            var (request, uri) = input

            val customTabIntent =
              authorizationService
                .createCustomTabsIntentBuilder(uri)
                .setSendToExternalDefaultHandlerEnabled(true)
                .build()

            return when (request) {
              is AuthorizationRequest ->
                AuthorizationManagementActivity.createStartForResultIntent(
                  activity,
                  request,
                  customTabIntent.intent
                    .setData(uri),
                )

              is EndSessionRequest ->
                authorizationService.getEndSessionRequestIntent(
                  request,
                  customTabIntent,
                )

              else -> throw Exception("Unsupported request type $input")
            }
          }

          override fun parseResult(
            resultCode: Int,
            intent: Intent?,
          ): CustomTabResult {
            Log.d(TAG, "Parsing result from custom tab intent")
            val ex = AuthorizationException.fromIntent(intent)

            return if (ex != null) {
              CustomTabResult.CustomTabFailure(ex)
            } else {
              CustomTabResult.CustomTabSuccess(intent!!.data!!)
            }
          }
        },
        this::handleCustomTabResult,
      )

    // Load the OIDC config and JWKS configuration, so it is ready when the user initiates a login
    activity.lifecycleScope.launch {
      async { runCatching { getIduraOIDCConfiguration() } }
      async { runCatching { getIduraJWKS() } }
    }
  }

  override fun onCreate(owner: LifecycleOwner) {
    tabType =
      if (CustomTabsClient.isAuthTabSupported(
          activity,
          Browsers.Chrome.PACKAGE_NAME,
        )
      ) {
        TabType.AuthTab
      } else {
        TabType.CustomTab
      }

    verifyAppLink(redirectUri)

    val (browserName, browserMatcher) = findSuitableBrowser()

    authorizationService =
      AuthorizationService(
        activity,
        AppAuthConfiguration
          .Builder()
          .setBrowserMatcher(
            browserMatcher,
          ).build(),
      )

    // Yes, users could have no browsers installed, and any preinstalled browsers disabled.
    if (browserName != null) {
      foundASuitableBrowser = true

      browserDescription =
        "$browserName ${
          activity.packageManager.getPackageInfo(
            browserName,
            0,
          ).versionName
        }, $tabType"
      Log.i(TAG, "Using $browserDescription")
    }
  }

  private fun findSuitableBrowser(): Pair<String?, BrowserMatcher> =
    when (tabType) {
      // When using an auth tab, we do not need the internal browser matching logic from appauth
      TabType.AuthTab -> {
        Pair(Browsers.Chrome.PACKAGE_NAME, BrowserMatcher { false })
      }
      TabType.CustomTab -> {
        val preferredBrowser =
          listOf(
            Pair(Browsers.Chrome.PACKAGE_NAME, VersionedBrowserMatcher.CHROME_CUSTOM_TAB),
            Pair(Browsers.SBrowser.PACKAGE_NAME, VersionedBrowserMatcher.SAMSUNG_CUSTOM_TAB),
            Pair(BRAVE, BrowserMatcher { it.packageName === BRAVE }),
            Pair(EDGE, BrowserMatcher { it.packageName === EDGE }),
          ).find {
            // Find the first of our preferred browsers, which is able to open a custom tab.
            CustomTabsClient.getPackageName(
              activity,
              listOf(it.first),
              true,
            ) != null
          }

        // If we found any of our preferred browsers above, use that.
        preferredBrowser
          // Otherwise, let appauth find the default browser
          ?: Pair(
            BrowserSelector
              .select(
                activity,
                AnyBrowserMatcher.INSTANCE,
              )?.packageName,
            AnyBrowserMatcher.INSTANCE,
          )
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
    authorizationService.dispose()
    tracing.close()
    httpClient.close()
  }

  private fun handleResultUri(uri: Uri) = browserFlowContinuation?.resume(uri)

  private fun handleException(ex: Exception) = browserFlowContinuation?.resumeWithException(ex)

  private fun handleCustomTabResult(result: CustomTabResult) {
    Log.i(TAG, "Handling custom tab result $result")

    when (result) {
      is CustomTabResult.CustomTabFailure -> handleException(result.ex)
      is CustomTabResult.CustomTabSuccess -> handleResultUri(result.resultUri)
    }
  }

  private fun handleAuthTabResult(result: AuthResult) {
    Log.i(TAG, "Handling auth tab result. Code: ${result.resultCode}")

    when (result.resultCode) {
      AuthTabIntent.RESULT_OK -> handleResultUri(result.resultUri!!)
      AuthTabIntent.RESULT_CANCELED -> handleException(Exception("RESULT_CANCELED"))
      AuthTabIntent.RESULT_UNKNOWN_CODE -> handleException(Exception("RESULT_UNKNOWN_CODE"))
      AuthTabIntent.RESULT_VERIFICATION_FAILED ->
        handleException(
          Exception("RESULT_VERIFICATION_FAILED"),
        )
      AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT ->
        handleException(
          Exception("RESULT_VERIFICATION_TIMED_OUT"),
        )
    }
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

        if (!foundASuitableBrowser) {
          throw NoSuitableBrowserException()
        }

        val loginHints =
          (
            mutableSetOf(
              "mobile:continue_button:never",
            ) + eid.loginHints
          ) as MutableSet<String>

        if (eid is DanishMitID) {
          loginHints.add("appswitch:android")
          loginHints.add(
            "appswitch:resumeUrl:${redirectUri.buildUpon().appendQueryParameter(
              APPSWITCH_QUERY_PARAM,
              null,
            ).build()}",
          )
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
          authorizationService.performTokenRequest(
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

  /**
   * The continuation that should be invoked when control returns from the browser to this library.
   */
  private var browserFlowContinuation: Continuation<Uri>? = null

  private suspend fun launchBrowser(
    request: AuthorizationManagementRequest,
    uri: Uri = request.toUri(),
  ): Uri =
    tracer
      .spanBuilder("launch browser")
      .setAttribute("browser", browserDescription)
      .startAndRun {
        suspendCoroutine { continuation ->
          browserFlowContinuation = continuation

          if (tabType == TabType.AuthTab) {
            // Open the Authorization URI in an Auth Tab if supported by chrome
            val authTabIntent = AuthTabIntent.Builder().build()

            // Auth tab will use the default browser, but we force it to use chrome.
            // In the future, other browser _could_ support the auth tab API (like they support custom tabs). But at the time of writing, only chrome supports it.
            authTabIntent.intent.`package` = Browsers.Chrome.PACKAGE_NAME
            authTabIntent.launch(
              authTabIntentLauncher,
              uri,
              redirectUri.host!!,
              redirectUri.path!!,
            )
          } else {
            // Fall back to a Custom Tab.
            customTabIntentLauncher.launch(Pair(request, uri))
          }
        }
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
