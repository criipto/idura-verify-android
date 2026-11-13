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
import java.lang.ref.WeakReference

/**
 * Opens a Custom Tab and returns the callback URI that [RedirectUriReceiverActivity] hands it, as
 * the result's data. Without a URI it returns `RESULT_CANCELED`, with an [AuthorizationException]
 * in the intent when there was no browser to open the tab in.
 *
 * It was ported from AppAuth's `AuthorizationManagementActivity`, which is `singleTask`. The system
 * finds a singleTask instance by affinity only, so for a host whose task affinity differs from the
 * package name (Flutter's template sets an empty one) the instance opened in a task of its own,
 * away from the host waiting for its result. This one has the standard launch mode, so it opens in
 * the host's task, and [deliver] restarts it from within that task, whatever the affinities.
 *
 * Like `AuthorizationManagementActivity`, it keeps the back stack below the browser tab: it opens
 * the tab when it first resumes, and decides when it resumes again. With a callback URI the login
 * completed, and without one the user closed the tab.
 */
internal class CustomTabActivity : Activity() {
  private var tabStarted = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    waiting = WeakReference(this)
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

  override fun onDestroy() {
    if (waiting?.get() === this) waiting = null
    super.onDestroy()
  }

  companion object {
    private const val EXTRA_TAB_INTENT = "eu.idura.verify.TAB_INTENT"
    private const val KEY_TAB_STARTED = "eu.idura.verify.TAB_STARTED"

    // Main thread only. One flow at a time, like BrowserFlowHostTask.
    private var waiting: WeakReference<CustomTabActivity>? = null

    fun createIntent(
      context: Context,
      tabIntent: Intent,
    ): Intent = Intent(context, CustomTabActivity::class.java).putExtra(EXTRA_TAB_INTENT, tabIntent)

    /**
     * Restarts the waiting instance with [uri], from the instance itself, so in its own task. The
     * receiver may be in another task, where a restart of its own would start a second instance.
     * When the system destroyed the instance, [caller] restarts it instead, like AppAuth's receiver.
     */
    fun deliver(
      caller: Activity,
      uri: Uri,
    ) {
      // Started without FLAG_ACTIVITY_NEW_TASK, so it lands in the starting activity's task.
      val starter = waiting?.get() ?: caller
      starter.startActivity(
        Intent(starter, CustomTabActivity::class.java)
          .setData(uri)
          .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      )
    }
  }
}
