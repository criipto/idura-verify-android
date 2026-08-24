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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.auth0.jwt.exceptions.JWTVerificationException
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationManagementActivity
import net.openid.appauth.AuthorizationManagementRequest
import net.openid.appauth.AuthorizationManagementResponse
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationRequest.ResponseMode
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
import java.util.WeakHashMap
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
  private lateinit var authTabIntentLauncher: ActivityResultLauncher<Intent>

  /**
   * An activity result launcher, used to a launch a custom tab intent and listen for the result.
   * See https://developer.android.com/training/basics/intents/result
   */
  private lateinit var customTabIntentLauncher:
    ActivityResultLauncher<Pair<AuthorizationManagementRequest, Uri>>

  /**
   * The keys the activity result registry files our launchers under. They have to be stable across
   * process death, because that is what a result arriving while our process was dead is delivered
   * against. The client ID and domain are part of the key so that two differently configured
   * instances can share one activity; two instances configured identically would collide, so the
   * constructor rejects the second one, see [liveLauncherKeys].
   */
  private val authTabLauncherKey = "eu.idura.verify.authTab:$domain:$clientID"
  private val customTabLauncherKey = "eu.idura.verify.customTab:$domain:$clientID"

  private val tracing = Tracing(domain)
  private val tracer =
    tracing.getTracer(BuildConfig.LIBRARY_PACKAGE_NAME, BuildConfig.VERSION)

  private var browserDescription: String? = null
  private val getIduraJWKS = cacheResult(activity.lifecycleScope, this::loadIduraJWKS)
  private val getIduraOIDCConfiguration =
    cacheResult(activity.lifecycleScope, this::loadIduraOIDCConfiguration)

  private var foundASuitableBrowser = false

  // Must be initialized before the init block: register() below replays a result queued while the
  // process was dead synchronously, and the result handlers resume this slot. With no login in
  // flight the slot holds no continuation, so such a replay is dropped, which is what we want —
  // the coroutine that was waiting for it died with the process.
  private val browserFlowSlot = BrowserFlowSlot<Uri>()

  init {
    require(redirectUri.scheme == "https") {
      "redirectUri must use https scheme"
    }

    // ActivityResultRegistry.register silently hands an existing key's callbacks to the new
    // registration, which would leave a displaced instance's in-flight login suspended forever.
    // Claim our keys up front so the collision fails at construction instead. Before the
    // lifecycle observer below, so a rejected instance leaves nothing behind.
    check(liveLauncherKeys.getOrPut(activity) { mutableSetOf() }.add(authTabLauncherKey)) {
      "An IduraVerify instance for clientID \"$clientID\" and domain \"$domain\" is already " +
        "active on this activity. Reuse that instance instead of constructing another."
    }

    activity.lifecycle.addObserver(this)

    // Register against the registry directly rather than via
    // `ComponentActivity.registerForActivityResult`, which refuses to register once the activity
    // is STARTED. That check exists to keep the request codes it generates from `activity_rq#<n>`
    // reproducible across process death, which only holds if every launcher in the activity is
    // registered unconditionally and in the same order every time. Supplying our own keys gives us
    // that stability without the constructor having to run before the activity starts, which
    // consumers embedding us in Flutter or React Native cannot arrange. In exchange we own the
    // unregistering, see `onDestroy`.
    authTabIntentLauncher =
      activity.activityResultRegistry.register(
        authTabLauncherKey,
        AuthTabIntent.AuthenticateUserResultContract(),
        this::handleAuthTabResult,
      )

    customTabIntentLauncher =
      activity.activityResultRegistry.register(
        customTabLauncherKey,
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
              is AuthorizationRequest -> {
                AuthorizationManagementActivity.createStartForResultIntent(
                  activity,
                  request,
                  customTabIntent.intent
                    .setData(uri),
                )
              }

              is EndSessionRequest -> {
                authorizationService.getEndSessionRequestIntent(
                  request,
                  customTabIntent,
                )
              }

              else -> {
                throw IduraVerifyInternalException(
                  "Unsupported request type: ${request::class.simpleName}",
                )
              }
            }
          }

          override fun parseResult(
            resultCode: Int,
            intent: Intent?,
          ): CustomTabResult {
            Log.d(TAG, "Parsing result from custom tab intent")
            // fromIntent refuses a null intent outright, so only ask it about a real one.
            val ex = intent?.let { AuthorizationException.fromIntent(it) }
            val resultUri = intent?.data

            return if (ex != null) {
              CustomTabResult.CustomTabFailure(ex)
            } else if (resultUri == null) {
              // Neither an exception nor a callback URI: a login interrupted by process death
              // is replayed at construction as a bare RESULT_CANCELED with no intent. Treat it
              // as the cancellation it is.
              CustomTabResult.CustomTabFailure(
                AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW,
              )
            } else {
              CustomTabResult.CustomTabSuccess(resultUri)
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
      // The customTab/authTab flavors pin the tab type so tests can exercise each
      // path deterministically. The published artifact is the automaticTabSelection
      // flavor (TAB_TYPE == "AUTO"), so consumers always fall through to auto-select;
      // the override only fires for the flavors the example app builds. Not gated on
      // BuildConfig.DEBUG so the instrumented tests can force a tab type against the
      // minified release build.
      if (BuildConfig.TAB_TYPE != "AUTO") {
        when (BuildConfig.TAB_TYPE) {
          "CUSTOM_TAB" -> TabType.CustomTab
          "AUTH_TAB" -> TabType.AuthTab
          else -> throw Error("Unsupported tab type override, ${BuildConfig.TAB_TYPE}")
        }
      } else if (CustomTabsClient.isAuthTabSupported(
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
            Pair(BRAVE, BrowserMatcher { it.packageName == BRAVE }),
            Pair(EDGE, BrowserMatcher { it.packageName == EDGE }),
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
    // Registering with our own key installs no lifecycle observer, so nothing releases the
    // callbacks for us. Left registered they would keep this instance, and everything it holds,
    // reachable from the registry for as long as the activity lives.
    //
    // Guarded because we observe the lifecycle from part way through the constructor: anything
    // that throws after `addObserver` leaves a half built instance still registered as an
    // observer, and this then runs against fields that were never assigned. Releasing what does
    // exist beats masking the original failure with an NPE from the cleanup path.
    if (::authTabIntentLauncher.isInitialized) authTabIntentLauncher.unregister()
    if (::customTabIntentLauncher.isInitialized) customTabIntentLauncher.unregister()
    liveLauncherKeys[activity]?.remove(authTabLauncherKey)
    if (::authorizationService.isInitialized) authorizationService.dispose()
    tracing.close()
    httpClient.close()
  }

  private fun handleResultUri(uri: Uri) {
    if (!browserFlowSlot.resume(uri)) {
      recordDroppedBrowserResult("uri", exceptionName = null)
    }
  }

  private fun handleException(ex: Exception) {
    if (!browserFlowSlot.fail(ex)) {
      recordDroppedBrowserResult("exception", exceptionName = ex::class.simpleName)
    }
  }

  /**
   * A browser result arriving with no login in flight is most likely a login interrupted by
   * process death, replayed by the activity result registry when the constructor re-registers.
   * We drop it — the coroutine that wanted it died with the process — but record that it
   * happened, to learn whether surviving process death is worth building. Deliberately coarse:
   * the callback URI carries the authorization code, so neither it nor anything derived from it
   * may end up in telemetry or the log.
   */
  private fun recordDroppedBrowserResult(
    result: String,
    exceptionName: String?,
  ) {
    Log.w(
      TAG,
      "Dropped a browser result ($result) because no login was in flight — " +
        "most likely a login interrupted by process death",
    )

    tracer
      .spanBuilder("dropped browser result")
      .setNoParent()
      .setAttribute("result", result)
      .apply { if (exceptionName != null) setAttribute("exception", exceptionName) }
      .startSpan()
      .end()
  }

  private fun handleCustomTabResult(result: CustomTabResult) {
    Log.i(TAG, "Handling custom tab result $result")

    when (result) {
      is CustomTabResult.CustomTabFailure -> handleException(result.ex.toIduraVerifyException())
      is CustomTabResult.CustomTabSuccess -> handleResultUri(result.resultUri)
    }
  }

  private fun handleAuthTabResult(result: AuthResult) {
    Log.i(TAG, "Handling auth tab result. Code: ${result.resultCode}")

    when (result.resultCode) {
      AuthTabIntent.RESULT_OK -> {
        handleResultUri(result.resultUri!!)
      }

      AuthTabIntent.RESULT_CANCELED -> {
        handleException(UserCancelledException())
      }

      AuthTabIntent.RESULT_UNKNOWN_CODE -> {
        handleException(IduraVerifyInternalException("Auth Tab returned unknown code"))
      }

      AuthTabIntent.RESULT_VERIFICATION_FAILED -> {
        handleException(IduraVerifyInternalException("Auth Tab verification failed"))
      }

      AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT -> {
        handleException(IduraVerifyInternalException("Auth Tab verification timed out"))
      }
    }
  }

  /**
   * Start a login, returning the verified ID token and the trace ID for the login flow.
   *
   * The SDK provides builder classes for some of the eIDs supported by Idura Verify. You should use these when possible, since they provide helper methods for the scopes and login hints supported by the specific eID provider. For example, Danish MitID supports SSN prefilling, which you can access using the `prefillSsn` method.
   *
   * @param eid The eID to login with.
   * @param prompt The OIDC prompt, see https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest
   *
   * @return A [LoginResult] containing the verified JWT and the OpenTelemetry trace ID for the
   *   login flow. On failure, an [IduraVerifyException] is thrown with the trace ID available
   *   on [IduraVerifyException.traceId].
   *
   * @sample eu.idura.verify.samples.loginSample1
   */
  suspend fun login(
    eid: EID<*>,
    prompt: Prompt? = null,
  ): LoginResult =
    tracer
      .spanBuilder(
        "android sdk login",
      ).setAttribute("acr_value", eid.acrValue)
      .setNoParent()
      .startAndRun { span ->
        val traceId = span.spanContext.traceId

        try {
          if (!foundASuitableBrowser) {
            throw NoSuitableBrowserException()
          }

          val loginHints =
            mutableSetOf("mobile:continue_button:never").apply { addAll(eid.loginHints) }

          if (eid.supportsAppSwitch) {
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
              .setResponseMode(ResponseMode.QUERY)
              .setLoginHint(loginHints.joinToString(" "))
              .setPrompt(prompt?.str)
              .build()

          val parRequestUri = pushAuthorizationRequest(authorizationRequest, span)

          val callbackUri = launchBrowser(authorizationRequest, parRequestUri, span)

          val jwt =
            if (callbackUri.getQueryParameter("code") != null) {
              exchangeCode(authorizationRequest, callbackUri, span)
            } else {
              val error = callbackUri.getQueryParameter("error") ?: "unknown_error"
              val errorDescription = callbackUri.getQueryParameter("error_description")
              // OAuth 2.0's `access_denied` is the spec-standard signal that the user
              // declined the request at the IdP. Surface it as cancellation so consumers
              // only have to handle one cancellation type across the various paths.
              throw if (error == "access_denied") {
                UserCancelledException()
              } else {
                OAuthException(error = error, errorDescription = errorDescription)
              }
            }

          LoginResult(jwt = jwt, traceId = traceId)
        } catch (ex: IduraVerifyException) {
          ex.traceId = traceId
          throw ex
        }
      }

  private suspend fun exchangeCode(
    request: AuthorizationRequest,
    callbackUri: Uri,
    span: Span,
  ): JWT {
    val tokenResponse =
      tracer
        .spanBuilder(
          "code exchange",
        ).withSpanContext(span)
        .startAndRun {
          val response =
            AuthorizationResponse
              .Builder(request)
              .fromUri(callbackUri)
              .build()

          if (!validateState(request, response)) {
            throw IduraVerifyInternalException("State mismatch in OIDC response")
          }

          suspendCoroutine { continuation ->
            authorizationService.performTokenRequest(
              response.createTokenExchangeRequest(),
            ) { tokenResponse, ex ->
              if (ex != null) {
                continuation.resumeWithException(ex.toIduraVerifyException())
              } else {
                // From TokenResponseCallback - Exactly one of `response` or `ex` will be non-null. So
                // when we reach this line, we know that response is not null.
                continuation.resume(tokenResponse!!)
              }
            }
          }
        }

    return tracer.spanBuilder("JWT verification").withSpanContext(span).startAndRun {
      val idToken = tokenResponse.idToken!!
      try {
        val decodedJWT = Auth0JWT.decode(idToken)

        val keyId = decodedJWT.getHeaderClaim("kid").asString()
        val key =
          getIduraJWKS().getKeyByKeyId(keyId)
            ?: throw IduraVerifyInternalException("Unknown JWT signing key: $keyId")

        Auth0JWT
          .require(verificationAlgorithm(key))
          .withIssuer("https://$domain")
          // Require `aud` to contain our client_id (OIDC Core 1.0 §3.1.3.7). This also
          // guarantees `decodedJWT.audience` is non-empty when constructing `JWT` below.
          .withAudience(clientID)
          // Add five minutes of leeway when validating nbf and iat.
          .acceptLeeway(5.minutes.inWholeSeconds)
          .build()
          .verify(idToken)
        return@startAndRun JWT(decodedJWT)
      } catch (e: JWTVerificationException) {
        throw IduraVerifyInternalException("ID token verification failed", cause = e)
      }
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

  suspend fun logout(idToken: String?) =
    tracer
      .spanBuilder(
        "android sdk logout",
      ).setNoParent()
      .startAndRun { span ->
        try {
          val endSessionRequest =
            EndSessionRequest
              .Builder(
                getIduraOIDCConfiguration(),
              ).setIdTokenHint(idToken)
              .setPostLogoutRedirectUri(redirectUri)
              .build()

          val callbackUri = launchBrowser(endSessionRequest, endSessionRequest.toUri(), span)

          val response =
            EndSessionResponse
              .Builder(endSessionRequest)
              .setState(
                callbackUri.getQueryParameter(
                  "state",
                ),
              ).build()

          validateState(endSessionRequest, response)
        } catch (ex: IduraVerifyException) {
          ex.traceId = span.spanContext.traceId
          throw ex
        }
      }

  /**
   * Starts the PAR flow, as described in https://datatracker.ietf.org/doc/html/rfc9126
   */
  @OptIn(ExperimentalSerializationApi::class)
  private suspend fun pushAuthorizationRequest(
    authorizationRequest: AuthorizationRequest,
    span: Span,
  ): Uri =
    tracer
      .spanBuilder("push authorize request")
      .withSpanContext(span)
      .startAndRun { _ ->
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
            // Propagate the login span, not the PAR span, so the server-side spans stay
            // siblings of ours rather than nesting under the client-side timing span.
            tracing.propagators().textMapPropagator.inject(
              span.storeInContext(OtelContext.current()),
              this,
              KtorRequestSetter,
            )
          }

        if (response.status.value != 201) {
          // Per RFC 9126 §2.3, PAR error responses use the OAuth 2.0 JSON error format.
          // Surface those to the consumer as OAuthException so e.g. a misconfigured
          // redirect_uri produces an actionable message rather than an opaque internal error.
          @Serializable()
          @JsonIgnoreUnknownKeys
          data class ParErrorResponse(
            val error: String,
            @SerialName("error_description")
            val errorDescription: String? = null,
          )
          val parsedError =
            runCatching { response.body<ParErrorResponse>() }.getOrNull()

          throw if (parsedError != null) {
            OAuthException(
              error = parsedError.error,
              errorDescription = parsedError.errorDescription,
            )
          } else {
            IduraVerifyInternalException(
              "PAR request failed: ${response.status.value} ${response.status.description}",
            )
          }
        }

        @Serializable()
        data class ParResponse(
          val request_uri: String,
          val expires_in: Int,
        )
        val parsedResponse = response.body<ParResponse>()

        getIduraOIDCConfiguration()
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
    span: Span,
  ): Uri =
    tracer
      .spanBuilder("launch browser")
      .setAttribute("browser", browserDescription ?: "unknown")
      .withSpanContext(span)
      .startAndRun {
        browserFlowSlot.run {
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

  private suspend fun loadIduraJWKS() = fetchJwks(httpClient, domain)

  private suspend fun loadIduraOIDCConfiguration(): AuthorizationServiceConfiguration =
    suspendCoroutine { continuation ->
      AuthorizationServiceConfiguration.fetchFromIssuer(
        "https://$domain".toUri(),
      ) { serviceConfiguration, ex ->
        if (ex != null) {
          Log.e(TAG, "Failed to fetch OIDC configuration", ex)
          continuation.resumeWithException(
            IduraVerifyInternalException(
              "Failed to fetch OIDC configuration from https://$domain: " +
                (ex.errorDescription ?: ex.error ?: "type=${ex.type} code=${ex.code}"),
              cause = ex,
            ),
          )
        } else {
          Log.d(TAG, "Fetched OIDC configuration")
          continuation.resume(serviceConfiguration!!)
        }
      }
    }

  private companion object {
    /**
     * The launcher keys live instances hold, per activity. Lets the constructor reject a second
     * identically configured instance, whose registration would otherwise silently displace the
     * first one's result callbacks. Claimed keys are released in [onDestroy]; the weak keys cover
     * activities that are destroyed without it running.
     */
    val liveLauncherKeys = WeakHashMap<ComponentActivity, MutableSet<String>>()
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
