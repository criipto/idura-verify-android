package eu.idura.verify

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import net.openid.appauth.AuthorizationManagementActivity
import java.util.concurrent.atomic.AtomicReference

/**
 * The task of the activity whose browser flow is in flight, recorded when the browser is launched.
 * Process-wide, because that is all the receiver below can reach: it is started by the system, not
 * by the instance that launched the browser.
 *
 * Only the latest flow is kept. Flows on two instances at once — on two activities, since one
 * activity hosts one task — would see the earlier one sent to the later one's task.
 */
internal class BrowserFlowHostTask private constructor(
  val id: Int,
) {
  /** Forget this record, unless a later flow has replaced it already. */
  fun release() {
    current.compareAndSet(this, null)
  }

  companion object {
    private val current = AtomicReference<BrowserFlowHostTask?>(null)

    fun record(taskId: Int): BrowserFlowHostTask =
      BrowserFlowHostTask(taskId).also { current.set(it) }

    val currentId: Int?
      get() = current.get()?.id
  }
}

// This is heavily inspired by the `net.openid.appauth.RedirectUriReceiverActivity` class, but only starts the
// `AuthorizationManagementActivity` if this is _not_ a DK MitID app switch.
class RedirectUriReceiverActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceBundle: Bundle?) {
    super.onCreate(savedInstanceBundle)

    val isAppswitch =
      intent.data!!
        .queryParameterNames
        .contains(APPSWITCH_QUERY_PARAM)

    // Both the MitID app and the browser open the callback URI with FLAG_ACTIVITY_NEW_TASK, which
    // places us in the task matching our affinity — the package name by default. A host whose task
    // has a different one (Flutter's template sets an empty `taskAffinity`, for one) does not match,
    // so we get a task of our own, and the login never returns: finishing leaves the browser and the
    // host behind in the background, and the response reaches no AuthorizationManagementActivity
    // that is waiting for it. So we return to the host's task explicitly whenever we did not land
    // in it.
    val hostTask = findHostTask()

    if (isAppswitch) {
      // The browser is still on top of the host, carrying on with the login, so bringing that task
      // forward is all there is to do.
      hostTask?.moveToFront()
    } else if (hostTask != null) {
      // AppAuth's AuthorizationManagementActivity is singleTask, which the system refuses to start
      // in a given task, and would otherwise place by affinity, outside the host's task again. So we
      // relaunch ourselves in the host's task and leave the delivery to that copy.
      hostTask.startActivity(
        this,
        Intent(this, RedirectUriReceiverActivity::class.java)
          .setData(intent.data)
          .putExtra(EXTRA_RELAUNCHED_IN_HOST_TASK, true),
        null,
      )
    } else {
      // this is taken from net.openid.appauth.AuthorizationManagementActivity.RedirectUriReceiverActivity
      val responseIntent =
        AuthorizationManagementActivity.createResponseHandlingIntent(
          this,
          intent.data,
        )

      if (intent.getBooleanExtra(EXTRA_RELAUNCHED_IN_HOST_TASK, false)) {
        // A start for result lands in the starter's task, where the waiting instance is, rather
        // than in the one singleTask finds by affinity.
        @Suppress("DEPRECATION")
        startActivityForResult(responseIntent, 0)
      } else {
        startActivity(responseIntent)
      }
    }
    finish()
  }

  /**
   * The host's task, if it is not the one we were placed in. `null` when we are already in it, which
   * is the common case, and also when it cannot be found — then all we can do is carry on as if we
   * were, and log why the login may not come back.
   */
  private fun findHostTask(): ActivityManager.AppTask? {
    val hostTaskId = BrowserFlowHostTask.currentId
    if (hostTaskId == null) {
      Log.w(TAG, "Received a callback with no browser flow in flight")
      return null
    }
    if (hostTaskId == taskId) return null

    val hostTask =
      getSystemService(ActivityManager::class.java).appTasks.find { appTask ->
        // Despite its documentation, getTaskInfo throws rather than returning null for a task that
        // went away since the list was taken.
        val info = runCatching { appTask.taskInfo }.getOrNull() ?: return@find false
        info.runningTaskId() == hostTaskId
      }

    if (hostTask == null) {
      Log.w(TAG, "The task the browser flow was started from (#$hostTaskId) is gone")
    }
    return hostTask
  }

  @Suppress("DEPRECATION")
  private fun ActivityManager.RecentTaskInfo.runningTaskId(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      if (isRunning) taskId else -1
    } else {
      // Before Q this was the only ID on offer, and it is -1 for a task that is not running.
      id
    }

  private companion object {
    const val EXTRA_RELAUNCHED_IN_HOST_TASK = "eu.idura.verify.RELAUNCHED_IN_HOST_TASK"
  }
}
