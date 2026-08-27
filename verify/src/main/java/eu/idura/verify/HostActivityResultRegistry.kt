package eu.idura.verify

import android.app.Activity
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat

/**
 * The value `ActivityResultRegistry` starts its own generated request codes at. Ours sit in the
 * same range, which is well clear of the small codes a hand written `onActivityResult` uses.
 */
private const val INITIAL_REQUEST_CODE_VALUE = 0x00010000

/**
 * A request code derived from the launcher key, so that it comes out the same in every process.
 * See [HostActivityResultRegistry]'s init block for why that matters.
 */
internal fun stableRequestCode(key: String): Int =
  INITIAL_REQUEST_CODE_VALUE +
    (key.hashCode() and Int.MAX_VALUE) % (Int.MAX_VALUE - INITIAL_REQUEST_CODE_VALUE)

/**
 * An [ActivityResultRegistry] that launches through a plain [Activity], for hosts that offer no
 * registry of their own — an activity that is neither a `ComponentActivity` nor any other
 * `ActivityResultRegistryOwner`, typically a cross-platform framework's activity base class. The
 * host routes results back by calling [IduraVerify.handleActivityResult] from its own
 * `onActivityResult`.
 *
 * @param stableKeys the launcher keys that will be registered, needed up front to bind their
 *   request codes before anything registers.
 */
internal class HostActivityResultRegistry(
  private val activity: Activity,
  stableKeys: List<String>,
) : ActivityResultRegistry() {
  init {
    val requestCodes = stableKeys.associateWith(::stableRequestCode)

    check(requestCodes.values.distinct().size == requestCodes.size) {
      "Launcher keys $stableKeys derive colliding request codes ${requestCodes.values}"
    }

    // The registry generates its request codes randomly, and relies on its owner persisting the
    // key to code mapping for it: that is how a result arriving after process death is matched
    // back to the launcher that asked for it. A host that only gets to see `onActivityResult` has
    // nowhere to persist that, so derive the codes from the keys instead — the mapping then
    // rebuilds itself identically in the next process, with no help from the host.
    //
    // `onRestoreInstanceState` is the only way to bind a code we chose, the allocation itself
    // being private, and it has to run before `register`, which would otherwise allocate a random
    // code for the key and keep it.
    //
    // The keys are seeded as launched as well. A result is only dispatched to its callback while
    // the registry believes a launch for that key to be outstanding, and after process death it
    // cannot know whether one was. Claiming there always is one means a result that survived
    // process death reaches us; nobody is waiting for it by then, which is a case we already
    // handle, see `IduraVerify.recordDroppedBrowserResult`.
    onRestoreInstanceState(
      Bundle().apply {
        putStringArrayList(REGISTERED_KEYS, ArrayList(requestCodes.keys))
        putIntegerArrayList(REGISTERED_RCS, ArrayList(requestCodes.values))
        putStringArrayList(LAUNCHED_KEYS, ArrayList(requestCodes.keys))
      },
    )
  }

  override fun <I, O> onLaunch(
    requestCode: Int,
    contract: ActivityResultContract<I, O>,
    input: I,
    options: ActivityOptionsCompat?,
  ) {
    // A subset of what ComponentActivity's registry does. The contracts the SDK registers produce
    // no synchronous result, and are neither a permission request nor an intent sender, so the
    // paths handling those are left out — which is also why this class stays internal.
    val intent = contract.createIntent(activity, input)

    // If there are any extras, defensively set the class loader, as ComponentActivity does.
    if (intent.extras != null && intent.extras!!.classLoader == null) {
      intent.setExtrasClassLoader(activity.classLoader)
    }

    activity.startActivityForResult(intent, requestCode, options?.toBundle())
  }

  private companion object {
    // Private in ActivityResultRegistry, but part of its saved state format, which
    // `onRestoreInstanceState` is the public entry point to.
    const val REGISTERED_KEYS = "KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS"
    const val REGISTERED_RCS = "KEY_COMPONENT_ACTIVITY_REGISTERED_RCS"
    const val LAUNCHED_KEYS = "KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS"
  }
}
