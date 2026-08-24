package eu.idura.verify

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Single-slot holder for a [CancellableContinuation]: only one flow can be in flight at a time,
 * because the activity result handlers driving [resume] / [fail] have no way to tell which of
 * several pending continuations a given callback belongs to. [run] rejects overlapping calls and
 * clears the slot in `finally` on resume, exception, and cancellation so it is reusable.
 *
 * [resume] and [fail] report whether a flow was actually waiting, so callers can tell a delivered
 * result apart from a dropped one.
 */
internal class BrowserFlowSlot<T> {
  private var continuation: CancellableContinuation<T>? = null

  suspend fun run(launch: () -> Unit): T {
    check(continuation == null) { "Another browser flow is already in progress" }
    try {
      return suspendCancellableCoroutine { cont ->
        continuation = cont
        launch()
      }
    } finally {
      continuation = null
    }
  }

  fun resume(value: T): Boolean {
    val cont = continuation ?: return false
    cont.resume(value)
    return true
  }

  fun fail(ex: Throwable): Boolean {
    val cont = continuation ?: return false
    cont.resumeWithException(ex)
    return true
  }
}
