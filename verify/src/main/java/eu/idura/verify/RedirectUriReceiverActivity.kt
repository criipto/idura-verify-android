package eu.idura.verify

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import net.openid.appauth.AuthorizationManagementActivity
import java.util.concurrent.atomic.AtomicReference

/**
 * The task of the activity whose browser flow is in flight, recorded when the browser is launched,
 * along with the span of that launch for the receiver to report on. Process-wide, because that is
 * all the receiver below can reach: it is started by the system, not by the instance that launched
 * the browser.
 *
 * Only the latest flow is kept. Flows on two instances at once — on two activities, since one
 * activity hosts one task — would see the earlier one sent to the later one's task.
 */
internal class BrowserFlowHostTask private constructor(
  val id: Int,
  val span: Span,
) {
  /** Forget this record, unless a later flow has replaced it already. */
  fun release() {
    currentRecord.compareAndSet(this, null)
  }

  companion object {
    private val currentRecord = AtomicReference<BrowserFlowHostTask?>(null)

    fun record(
      taskId: Int,
      span: Span,
    ): BrowserFlowHostTask = BrowserFlowHostTask(taskId, span).also { currentRecord.set(it) }

    val current: BrowserFlowHostTask?
      get() = currentRecord.get()
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

    if (!isAppswitch) {
      // this is taken from net.openid.appauth.AuthorizationManagementActivity.RedirectUriReceiverActivity
      startActivity(
        AuthorizationManagementActivity.createResponseHandlingIntent(
          this,
          intent.data,
        ),
      )
    } else {
      // The MitID app opens the callback URI with FLAG_ACTIVITY_NEW_TASK, which places us in the task
      // matching our affinity — the package name by default. A host whose task has a different one
      // (Flutter's template sets an empty `taskAffinity`, for one) does not match, so we get a task
      // of our own, and finishing would leave the host behind in the background, with the browser
      // still on top of it carrying on with the login. So we bring the host's task forward whenever
      // we did not land in it. The browser's own callback above has the same problem, which this
      // does not address.
      findHostTask()?.moveToFront()
    }
    finish()
  }

  /**
   * The host's task, if it is not the one we were placed in. `null` when we are already in it, which
   * is the common case, and also when it cannot be found — then all we can do is carry on as if we
   * were, and log why the login may not come back.
   */
  private fun findHostTask(): ActivityManager.AppTask? {
    val record = BrowserFlowHostTask.current
    if (record == null) {
      Log.w(TAG, "Received a callback with no browser flow in flight")
      recordCallbackWithNoFlow()
      return null
    }
    val hostTaskId = record.id
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
      record.span.addEvent(
        "host task gone",
        Attributes.of(
          AttributeKey.longKey("host_task_id"),
          hostTaskId.toLong(),
          AttributeKey.longKey("receiver_task_id"),
          taskId.toLong(),
        ),
      )
    }
    return hostTask
  }

  /**
   * Most likely the MitID app returning to a process that died while the user was in it, which
   * leaves no instance, and so no tracer, to report through. So a tracer of our own, for this one
   * span. Like the SDK's dropped browser results, it is there to learn whether surviving process
   * death is worth building. It takes nothing from the callback URI but its host, because the rest
   * carries the authorization code.
   */
  private fun recordCallbackWithNoFlow() {
    val tracing = Tracing(intent.data!!.host!!)
    tracing
      .getTracer(BuildConfig.LIBRARY_PACKAGE_NAME, BuildConfig.VERSION)
      .spanBuilder("callback with no browser flow")
      .setNoParent()
      .setAttribute("receiver_task_id", taskId.toLong())
      .startSpan()
      .end()
    tracing.close()
  }

  @Suppress("DEPRECATION")
  private fun ActivityManager.RecentTaskInfo.runningTaskId(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      if (isRunning) taskId else -1
    } else {
      // Before Q this was the only ID on offer, and it is -1 for a task that is not running.
      id
    }
}
