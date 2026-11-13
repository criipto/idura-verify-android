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
 * It stands in for AppAuth's `AuthorizationManagementActivity`, which is `singleTask`. The system
 * finds a singleTask instance by affinity only, so for a host whose task affinity differs from the
 * package name (Flutter's template sets an empty one) the receiver's response started a second
 * instance, and the waiting one reported a cancel. This one has the standard launch mode, and gets
 * the URI in process instead: [deliver] leaves it on the flow's [BrowserFlowHostTask], where the
 * instance looks when it resumes, and restarts the instance from within its own task, whatever the
 * affinities.
 *
 * The callback is an app link, so the browser could load it. We ask it to hand redirects to their
 * default handler, which is our receiver, instead (by calling
 * setSendToExternalDefaultHandlerEnabled(true)). Whether the browser closes its tab after handing one
 * off is up to the browser: Chrome and Samsung Internet close theirs, while Edge and Firefox leave
 * theirs open. Like AppAuth's response handling, the restart clears the top, and with it any tab
 * still open, so the host isn't left stuck behind it.
 *
 * Like `AuthorizationManagementActivity`, it decides when it resumes after opening the tab: with a
 * callback URI the login completed, and without one the user closed the tab. The tab has to be gone
 * for us to resume, and in every redirect we observed the browser started our receiver before it
 * closed its tab, if it closed it at all.
 *
 * The system can destroy the instance while the tab is open, and recreates it from its back-stack
 * record once it is on top again. The URI waits for it on the record, which lasts as long as the
 * login waiting for the result. Without a live instance to restart from, the receiver restarts it
 * when it is in the host's task itself. When it is not, nothing can: `AppTask.startActivity` starts a
 * new instance rather than reaching the record. Moving the host's task to the front then recreates
 * the instance, unless a browser left its tab open, which the user has to close first. The receiver
 * records that on the flow's span.
 *
 * A restart that finds no instance to reach creates a new one, without a tab to open. That one
 * decides straight away, and its result goes nowhere, since nothing started it for one.
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

    val uri = BrowserFlowHostTask.current?.callbackUri
    if (uri != null) {
      setResult(RESULT_OK, Intent().setData(uri))
    } else {
      setResult(RESULT_CANCELED)
    }
    finish()
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
     * Leaves [uri] on [record] for the waiting instance, and restarts that instance in its own task:
     * from the instance when it is alive, and from [caller] when the system destroyed it and
     * [caller] is in the host's task. Returns false when neither can, leaving the instance to take
     * the URI once it is on top again.
     */
    fun deliver(
      caller: Activity,
      record: BrowserFlowHostTask,
      uri: Uri,
    ): Boolean {
      record.callbackUri = uri
      // Started without FLAG_ACTIVITY_NEW_TASK, so it lands in the starting activity's task.
      val starter =
        waiting?.get() ?: caller.takeIf { it.taskId == record.id } ?: return false
      starter.startActivity(
        Intent(starter, CustomTabActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
      )
      return true
    }
  }
}
