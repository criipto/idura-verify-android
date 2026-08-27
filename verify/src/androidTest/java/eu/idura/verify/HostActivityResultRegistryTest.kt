package eu.idura.verify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers [HostActivityResultRegistry] on its own, without the SDK around it: that a launch reaches
 * the host activity under the request code derived from the launcher key, and that handing that
 * same code back delivers the result. Driving this through [IduraVerify] instead would mean
 * completing a login, so the two are tested apart.
 */
@RunWith(AndroidJUnit4::class)
class HostActivityResultRegistryTest {
  private val key = "eu.idura.verify.test:launcher"

  /** Hands the input string through as an extra, and reads it back out of the result. */
  private val contract =
    object : ActivityResultContract<String, String?>() {
      override fun createIntent(
        context: Context,
        input: String,
      ): Intent = Intent("eu.idura.verify.test.ACTION").putExtra("input", input)

      override fun parseResult(
        resultCode: Int,
        intent: Intent?,
      ): String? = intent?.getStringExtra("output")
    }

  @Test
  fun aLaunchReachesTheActivityUnderTheDerivedRequestCode() {
    withRegistry { host, registry ->
      registry.register(key, contract) {}.launch("hello")

      val (requestCode, intent) = host.launched.single()
      assertEquals(stableRequestCode(key), requestCode)
      assertEquals("hello", intent.getStringExtra("input"))
    }
  }

  /**
   * Deriving the code from the key is the whole point: it comes out the same in the next process,
   * with nothing persisted in between.
   */
  @Test
  fun theDerivedRequestCodeDependsOnlyOnTheKey() {
    assertEquals(stableRequestCode(key), stableRequestCode(key))
    assertNotEquals(stableRequestCode(key), stableRequestCode("$key:other"))

    // startActivityForResult rejects a negative request code.
    assertTrue(stableRequestCode(key) >= 0)
  }

  /** What the constructor's collision check protects, for the two keys that actually matter. */
  @Test
  fun theSdksOwnLauncherKeysDeriveDistinctRequestCodes() {
    val domain = "verify-android-tests.invalid"
    val clientID = "urn:idura:verify:android:tests"

    assertNotEquals(
      stableRequestCode(authTabLauncherKeyFor(domain, clientID)),
      stableRequestCode(customTabLauncherKeyFor(domain, clientID)),
    )
  }

  @Test
  fun aResultDispatchedUnderThatCodeReachesTheCallback() {
    withRegistry { _, registry ->
      var received: String? = null
      var callbackRan = false
      registry.register(key, contract) {
        callbackRan = true
        received = it
      }

      assertTrue(
        registry.dispatchResult(
          stableRequestCode(key),
          Activity.RESULT_OK,
          Intent().putExtra("output", "world"),
        ),
      )

      assertTrue(callbackRan)
      assertEquals("world", received)
    }
  }

  /**
   * A result for a launch this process has no memory of — what process death during a login leaves
   * behind — still reaches the callback, because the keys are seeded as launched. It arrives with
   * nothing awaiting it, which is the SDK's dropped-result case.
   */
  @Test
  fun aResultThatSurvivedProcessDeathReachesTheCallback() {
    withRegistry { _, registry ->
      var callbackRan = false
      registry.register(key, contract) { callbackRan = true }

      // No launch in this process, and the worst shape a replayed result comes in: a bare
      // cancellation with no intent.
      assertTrue(registry.dispatchResult(stableRequestCode(key), Activity.RESULT_CANCELED, null))

      assertTrue(callbackRan)
    }
  }

  @Test
  fun anUnknownRequestCodeIsNotClaimed() {
    withRegistry { _, registry ->
      registry.register(key, contract) {}

      assertFalse(registry.dispatchResult(1234, Activity.RESULT_OK, null))
    }
  }

  private fun withRegistry(body: (TestPlainHostActivity, HostActivityResultRegistry) -> Unit) {
    ActivityScenario.launch(TestPlainHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        body(activity, HostActivityResultRegistry(activity, listOf(key)))
      }
    }
  }
}

/**
 * A plain [Activity] that is also a [LifecycleOwner], which is the shape the hosts this exists for
 * have — typically a cross-platform framework's activity base class. Captures what it is asked to
 * launch rather than starting it,
 * so a test can see the request code the registry chose, and so an SDK login goes nowhere.
 */
open class TestPlainHostActivity :
  Activity(),
  LifecycleOwner {
  private val lifecycleRegistry = LifecycleRegistry(this)

  override val lifecycle: Lifecycle
    get() = lifecycleRegistry

  val launched = mutableListOf<Pair<Int, Intent>>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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

  override fun startActivityForResult(
    intent: Intent,
    requestCode: Int,
    options: Bundle?,
  ) {
    launched.add(requestCode to intent)
  }
}

/** A host with no lifecycle at all, which the SDK cannot follow. */
class TestNonLifecycleHostActivity : Activity()
