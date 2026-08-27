package eu.idura.verify

import android.app.Activity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat

/**
 * An [ActivityResultRegistry] that launches through a plain [Activity], for hosts that offer no
 * registry of their own — an activity that is neither a `ComponentActivity` nor any other
 * `ActivityResultRegistryOwner`, typically a cross-platform framework's activity base class. The
 * host routes results back by calling [IduraVerify.handleActivityResult] from its own
 * `onActivityResult`.
 *
 * The request codes are the registry's own generated ones. It relies on its owner persisting the
 * key to code mapping, which is how a `ComponentActivity` matches a result that arrived after
 * process death back to the launcher that asked for it, and a host that only gets to see
 * `onActivityResult` has nowhere to keep that. Such a result therefore goes unrecognised here and
 * is handed back to the host. Nothing is lost by that: the login it belonged to cannot be completed
 * in a new process anyway, its PKCE verifier having died with the old one.
 */
internal class HostActivityResultRegistry(
  private val activity: Activity,
) : ActivityResultRegistry() {
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
}
