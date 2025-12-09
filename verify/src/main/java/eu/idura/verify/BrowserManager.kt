package eu.idura.verify

import android.content.Context
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
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationManagementActivity
import net.openid.appauth.AuthorizationManagementRequest
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.browser.AnyBrowserMatcher
import net.openid.appauth.browser.BrowserMatcher
import net.openid.appauth.browser.BrowserSelector
import net.openid.appauth.browser.Browsers
import net.openid.appauth.browser.VersionedBrowserMatcher
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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

public class BrowserManager(
  private val activity: ComponentActivity,
  private val redirectUri: Uri,
) : DefaultLifecycleObserver {
  /**
   * The AppAuth authorization service, which provides helper methods for OIDC operations, and manages the browser.
   * The service needs access to the activity, so it is initialized in `onCreate`.
   */
  internal lateinit var authorizationService: AuthorizationService

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

  internal lateinit var browserDescription: String
  internal var foundASuitableBrowser = false

  init {
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
            context: Context,
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

  override fun onDestroy(owner: LifecycleOwner) {
    authorizationService.dispose()
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
   * The continuation that should be invoked when control returns from the browser to this library.
   */
  private var browserFlowContinuation: Continuation<Uri>? = null

  internal suspend fun launchBrowser(
    request: AuthorizationManagementRequest,
    uri: Uri = request.toUri(),
  ): Uri =
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

  public suspend fun launchBrowser(
    uri: Uri,
    state: String,
  ): Uri {
    val request =
      AuthorizationRequest
        .Builder(
          AuthorizationServiceConfiguration(
            "https://example.com".toUri(),
            "https://example.com".toUri(),
          ),
          "clientId",
          ResponseTypeValues.CODE,
          redirectUri,
        ).setState(state)
        .build()

    return launchBrowser(request, uri)
  }
}
