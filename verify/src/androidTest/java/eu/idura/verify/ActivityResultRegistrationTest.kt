package eu.idura.verify

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A bare host, so the only activity result launchers in play are the ones a test asks for. */
class TestHostActivity : ComponentActivity()

/**
 * Covers how the SDK claims its activity result launchers. This is the mechanism a browser result
 * comes back through, and the guarantees it needs cannot be seen from the JVM: the constructor
 * has to work against an already started activity, and the keys it files the launchers under have
 * to be stable, because those keys are what a result arriving after process death is matched on.
 */
@RunWith(AndroidJUnit4::class)
class ActivityResultRegistrationTest {
  // Private in ActivityResultRegistry, but part of its saved state format.
  private val registeredKeysBundleKey = "KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS"
  private val registeredRcsBundleKey = "KEY_COMPONENT_ACTIVITY_REGISTERED_RCS"
  private val launchedKeysBundleKey = "KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS"

  // Not a typo: the constant's value really is singular while its name is plural.
  private val pendingResultsBundleKey = "KEY_COMPONENT_ACTIVITY_PENDING_RESULT"

  /**
   * Hosts that embed us, Flutter and React Native among them, only get to run their code once the
   * activity already exists, so the constructor has to tolerate a started activity.
   * `ComponentActivity.registerForActivityResult` does not, which is why we register against the
   * registry ourselves.
   */
  @Test
  fun canBeConstructedAfterTheActivityHasStarted() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        newIduraVerify(activity)

        assertEquals(2, activity.activityResultRegistry.iduraKeys().size)
      }
    }
  }

  /**
   * Registering with our own key installs no lifecycle observer, so the registry holds the
   * callbacks until we release them. Leaving them behind would keep the instance reachable for the
   * rest of the activity's life.
   */
  @Test
  fun launchersAreReleasedWhenTheActivityIsDestroyed() {
    lateinit var registry: ActivityResultRegistry

    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        registry = activity.activityResultRegistry
        newIduraVerify(activity)
        assertEquals(2, registry.iduraKeys().size)
      }
      scenario.moveToState(Lifecycle.State.DESTROYED)
    }

    assertEquals(emptyList<String>(), registry.iduraKeys())
  }

  /** Two instances configured differently share an activity without taking each other's keys. */
  @Test
  fun differentlyConfiguredInstancesGetDistinctKeys() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        newIduraVerify(activity, clientID = "urn:idura:verify:android:tests:first")
        newIduraVerify(activity, clientID = "urn:idura:verify:android:tests:second")

        assertEquals(4, activity.activityResultRegistry.iduraKeys().size)
      }
    }
  }

  /**
   * Two instances configured identically would use the same keys, and the registry silently hands
   * the keys to the later registration — leaving a login in flight on the first instance suspended
   * forever. The constructor rejects the duplicate instead.
   */
  @Test
  fun identicallyConfiguredInstancesAreRejected() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        newIduraVerify(activity)

        assertThrows(IllegalStateException::class.java) { newIduraVerify(activity) }

        // The rejected instance claimed nothing: the first one's launchers are intact.
        assertEquals(2, activity.activityResultRegistry.iduraKeys().size)
      }
    }
  }

  /** The claim on a configuration dies with the activity holding it. */
  @Test
  fun configCanBeReusedOnceTheActivityIsDestroyed() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity -> newIduraVerify(activity) }
      scenario.moveToState(Lifecycle.State.DESTROYED)
    }

    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        newIduraVerify(activity)

        assertEquals(2, activity.activityResultRegistry.iduraKeys().size)
      }
    }
  }

  /**
   * A login interrupted by process death comes back as a pending result the registry replays the
   * moment the constructor registers — synchronously, before anything is awaiting it. The worst
   * shape it arrives in is a bare RESULT_CANCELED with no intent, which the custom tab contract
   * must parse as a cancellation rather than crash on the missing callback URI.
   */
  @Test
  fun pendingResultFromBeforeProcessDeathIsSurvivedAtConstruction() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        val customTabKey =
          "eu.idura.verify.customTab:verify-android-tests.invalid:urn:idura:verify:android:tests"

        // What the registry saves when the process dies mid-login: our stable key, its request
        // code, the launch in flight, and the cancellation that arrived for it.
        activity.activityResultRegistry.onRestoreInstanceState(
          Bundle().apply {
            putIntegerArrayList(registeredRcsBundleKey, arrayListOf(0x10000))
            putStringArrayList(registeredKeysBundleKey, arrayListOf(customTabKey))
            putStringArrayList(launchedKeysBundleKey, arrayListOf(customTabKey))
            putBundle(
              pendingResultsBundleKey,
              Bundle().apply {
                putParcelable(customTabKey, ActivityResult(Activity.RESULT_CANCELED, null))
              },
            )
          },
        )

        // Replays the pending cancellation inside the constructor. Nothing is awaiting it, so
        // surviving construction is the whole assertion.
        newIduraVerify(activity)
      }
    }
  }

  /**
   * The domain is never reached: the constructor only kicks off the OIDC and JWKS loads, and both
   * swallow their failures. What is under test is the registration it does synchronously.
   */
  private fun newIduraVerify(
    activity: ComponentActivity,
    clientID: String = "urn:idura:verify:android:tests",
  ) = IduraVerify(
    clientID = clientID,
    domain = "verify-android-tests.invalid",
    activity = activity,
  )

  /** The keys this registry currently holds on the SDK's behalf, read out of its saved state. */
  private fun ActivityResultRegistry.iduraKeys(): List<String> {
    val saved = Bundle()
    onSaveInstanceState(saved)
    return saved
      .getStringArrayList(registeredKeysBundleKey)
      .orEmpty()
      .filter { it.startsWith("eu.idura.verify.") }
  }
}
