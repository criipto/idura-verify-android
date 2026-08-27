package eu.idura.verifyexample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import eu.idura.verify.IduraVerify
import eu.idura.verify.eid.DanishMitID
import eu.idura.verify.eid.EID
import eu.idura.verify.eid.Mock
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
private data class MockData(
  val name: String,
)

/**
 * A login hosted on a plain [Activity] rather than a `ComponentActivity`, which is what the SDK's
 * plain-activity constructor exists for. Cross-platform frameworks' activity base classes are
 * typically exactly this shape — a plain activity that implements [LifecycleOwner] and nothing
 * more — and they are the hosts that pushed the SDK to support the path at all.
 *
 * Two things a `ComponentActivity` would do for us have to be done by hand here, and they are
 * precisely what a plain-activity host has to get right:
 * - drive a [LifecycleRegistry] from each lifecycle callback, since the SDK follows the host's
 *   lifecycle and a plain activity owns none;
 * - forward [onActivityResult] to [IduraVerify.handleActivityResult], since with no activity result
 *   registry to register against the SDK launches the browser through `startActivityForResult`.
 *
 * Deliberately not a launcher activity and not reachable from [MainActivity]: it exists for
 * `PlainHostLoginTest` to drive, and a second launcher activity would make `startApp` ambiguous for
 * the tests that drive the Compose UI. It lives in `main` rather than `androidTest` so that it ships
 * inside the app APK and CI's minified release build covers it. See the example README for how to
 * run it.
 *
 * The UI is plain Views, and not only because Compose's `setContent` requires a
 * `ComponentActivity`: building it this way is what shows the SDK needs none of that machinery. It
 * renders the same strings as [MainScreen] so both hosts can be asserted on the same way.
 */
class PlainHostActivity :
  Activity(),
  LifecycleOwner {
  private val lifecycleRegistry = LifecycleRegistry(this)

  override val lifecycle: Lifecycle
    get() = lifecycleRegistry

  // In a field initializer, ahead of the ON_CREATE dispatched below: the SDK does its own setup
  // when the lifecycle it observes reaches CREATED, so it has to be observing by then.
  private val iduraVerify =
    IduraVerify(
      BuildConfig.IDURA_CLIENT_ID,
      BuildConfig.IDURA_DOMAIN,
      activity = this,
    )

  private lateinit var status: TextView
  private lateinit var identityScheme: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_plain_host)

    status = findViewById(R.id.status)
    identityScheme = findViewById(R.id.identity_scheme)
    findViewById<TextView>(R.id.tab_type).text =
      getString(R.string.using_tab_type, BuildConfig.TAB_TYPE)

    findViewById<Button>(R.id.login_with_mock).setOnClickListener {
      login(Mock().withMockData(MockData(name = "foobar")))
    }
    findViewById<Button>(R.id.login_with_mitid).setOnClickListener {
      login(DanishMitID.substantial().withMessage("hello!"))
    }

    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
  }

  override fun onStart() {
    super.onStart()
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
  }

  override fun onResume() {
    super.onResume()
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
  }

  override fun onPause() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    super.onPause()
  }

  override fun onStop() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    super.onStop()
  }

  override fun onDestroy() {
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    super.onDestroy()
  }

  /**
   * Without this the browser result has nowhere to go and `login` never returns. Returning early on
   * a result the SDK claimed keeps it out of the rest of the host's own handling.
   */
  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
  ) {
    if (iduraVerify.handleActivityResult(requestCode, resultCode, data)) return
    super.onActivityResult(requestCode, resultCode, data)
  }

  private fun login(eid: EID<*>) {
    status.setText(R.string.logging_in)
    identityScheme.text = ""

    lifecycleScope.launch {
      try {
        val jwt = iduraVerify.login(eid).jwt
        status.setText(R.string.logged_in)
        identityScheme.text = jwt.identityScheme
      } catch (ex: Throwable) {
        status.text = ex.localizedMessage
      }
    }
  }
}
