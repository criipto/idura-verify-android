package eu.idura.verify

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.IntentCompat
import net.openid.appauth.AuthorizationException

/**
 * Opens a Custom Tab and returns the callback URI that [RedirectUriReceiverActivity] hands it, as
 * the result's data. Without a URI it returns `RESULT_CANCELED`, with an [AuthorizationException]
 * in the intent when there was no browser to open the tab in.
 *
 * Like AppAuth's `AuthorizationManagementActivity`, which it was ported from, it keeps the back
 * stack below the browser tab: it opens the tab when it first resumes, and decides when it resumes
 * again. With a callback URI the login completed, and without one the user closed the tab.
 */
internal class CustomTabActivity : Activity() {
  private var tabStarted = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    tabStarted = savedInstanceState?.getBoolean(KEY_TAB_STARTED) ?: false
  }

  override fun onResume() {
    super.onResume()

    val tabIntent =
      IntentCompat.getParcelableExtra(intent, EXTRA_TAB_INTENT, Intent::class.java)
    if (!tabStarted && tabIntent != null) {
      try {
        startActivity(tabIntent)
        tabStarted = true
      } catch (ex: ActivityNotFoundException) {
        Log.w(TAG, "No browser to open the Custom Tab in", ex)
        setResult(
          RESULT_CANCELED,
          AuthorizationException
            .fromTemplate(AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW, ex)
            .toIntent(),
        )
        finish()
      }
      return
    }

    val uri = intent.data
    if (uri != null) {
      setResult(RESULT_OK, Intent().setData(uri))
    } else {
      setResult(RESULT_CANCELED)
    }
    finish()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(KEY_TAB_STARTED, tabStarted)
  }

  companion object {
    private const val EXTRA_TAB_INTENT = "eu.idura.verify.TAB_INTENT"
    private const val KEY_TAB_STARTED = "eu.idura.verify.TAB_STARTED"

    fun createIntent(
      context: Context,
      tabIntent: Intent,
    ): Intent = Intent(context, CustomTabActivity::class.java).putExtra(EXTRA_TAB_INTENT, tabIntent)

    fun createResponseHandlingIntent(
      context: Context,
      responseUri: Uri?,
    ): Intent =
      Intent(context, CustomTabActivity::class.java)
        .setData(responseUri)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
  }
}
