package eu.idura.verify

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.idura.verify.eid.Mock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DOMAIN = "verify-android-tests.invalid"
private const val TEST_CLIENT_ID = "urn:idura:verify:android:tests"

/** A bare host, so the only activity result launchers in play are the ones a test asks for. */
class TestHostActivity : ComponentActivity()

/**
 * A host that offers an activity result registry without being a `ComponentActivity`. Nothing in
 * androidx has this shape, but nothing stops a host from taking it — a framework wanting one registry
 * shared across its plugins has reason to — and the SDK has to use the registry it is offered rather
 * than bypass it. Launches go through the plain host's captured `startActivityForResult`, as they
 * would for a real such host, so an SDK login still goes nowhere.
 */
class TestRegistryOwnerHostActivity :
  TestPlainHostActivity(),
  ActivityResultRegistryOwner {
  override val activityResultRegistry: ActivityResultRegistry =
    object : ActivityResultRegistry() {
      override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
      ) = startActivityForResult(
        contract.createIntent(this@TestRegistryOwnerHostActivity, input),
        requestCode,
      )
    }
}

/**
 * A lifecycle owner whose registry is left at INITIALIZED, standing in for a consumer who builds one
 * by hand but never drives it from the activity's lifecycle callbacks.
 */
private class NeverDrivenLifecycleOwner : LifecycleOwner {
  override val lifecycle = LifecycleRegistry(this)
}

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
   * Hosts that embed us in a cross-platform framework only get to run their code once the
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
        newIduraVerify(activity, clientID = "$TEST_CLIENT_ID:first")
        newIduraVerify(activity, clientID = "$TEST_CLIENT_ID:second")

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
        val customTabKey = customTabLauncherKeyFor(TEST_DOMAIN, TEST_CLIENT_ID)

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
   * A host that is not a `ComponentActivity` has no registry to register against, so the SDK brings
   * its own and the host routes results to it. Nothing about construction should otherwise differ,
   * including that it tolerates an already started activity.
   */
  @Test
  fun canBeConstructedAgainstAPlainActivity() {
    ActivityScenario.launch(TestPlainHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertTrue(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        newIduraVerify(activity)
      }
    }
  }

  /**
   * The instance follows the host's lifecycle, so a host that has none has to say which one to
   * follow. Rejected at construction rather than on the first `login()`.
   */
  @Test
  fun aPlainActivityWithoutALifecycleIsRejected() {
    ActivityScenario.launch(TestNonLifecycleHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertThrows(IllegalArgumentException::class.java) {
          IduraVerify(
            clientID = TEST_CLIENT_ID,
            domain = TEST_DOMAIN,
            activity = activity,
          )
        }
      }
    }
  }

  /**
   * A hand-driven lifecycle that is never advanced past INITIALIZED means the SDK never sees
   * ON_CREATE, and so never picks a browser. Left to fail on its own the login blames the device's
   * browsers, which is the wrong thing entirely to go debugging.
   */
  @Test
  fun aLifecycleThatIsNeverDrivenIsNamedAsTheProblem() {
    ActivityScenario.launch(TestPlainHostActivity::class.java).use { scenario ->
      lateinit var iduraVerify: IduraVerify

      scenario.onActivity { activity ->
        iduraVerify =
          IduraVerify(
            clientID = TEST_CLIENT_ID,
            domain = TEST_DOMAIN,
            activity = activity,
            lifecycleOwner = NeverDrivenLifecycleOwner(),
          )
      }

      val ex =
        assertThrows(IllegalStateException::class.java) {
          runBlocking { iduraVerify.login(Mock()) }
        }

      assertTrue(
        "unexpected message: ${ex.message}",
        ex.message!!.contains("never reached CREATED"),
      )
    }
  }

  /**
   * The request codes the SDK's own registry launches under are derived from the launcher keys, so
   * a host can match a result back to us with nothing persisted in between — which is what a result
   * arriving after process death needs. Handing back a code that is not ours must not claim it.
   */
  @Test
  fun aPlainActivityHostRoutesResultsByTheDerivedRequestCode() {
    ActivityScenario.launch(TestPlainHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        val iduraVerify = newIduraVerify(activity)

        assertFalse(iduraVerify.handleActivityResult(1234, Activity.RESULT_CANCELED, null))

        // The worst shape a result interrupted by process death comes back in. Nothing is awaiting
        // it, so the SDK drops it — that it is claimed and survived is the assertion.
        assertTrue(
          iduraVerify.handleActivityResult(
            stableRequestCode(customTabLauncherKeyFor(TEST_DOMAIN, TEST_CLIENT_ID)),
            Activity.RESULT_CANCELED,
            null,
          ),
        )
      }
    }
  }

  /**
   * A `ComponentActivity` delivers results through its own registry, so forwarding
   * `onActivityResult` as well would hand the SDK the same result twice.
   */
  @Test
  fun aComponentActivityHostDoesNotRouteResultsItself() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        val iduraVerify = newIduraVerify(activity)

        assertFalse(
          iduraVerify.handleActivityResult(
            stableRequestCode(customTabLauncherKeyFor(TEST_DOMAIN, TEST_CLIENT_ID)),
            Activity.RESULT_CANCELED,
            null,
          ),
        )
      }
    }
  }

  /**
   * A host that is not a `ComponentActivity` but does offer a registry has that registry used.
   * Bringing our own instead would bypass a working one, and then depend on the `onActivityResult`
   * forwarding such a host has no reason to be doing — a login that never returns.
   */
  @Test
  fun aHostOfferingItsOwnRegistryHasItUsed() {
    ActivityScenario.launch(TestRegistryOwnerHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        val iduraVerify =
          IduraVerify(
            clientID = TEST_CLIENT_ID,
            domain = TEST_DOMAIN,
            activity = activity,
          )

        assertEquals(2, activity.activityResultRegistry.iduraKeys().size)

        // Nothing to forward, the host's own registry does the routing.
        assertFalse(
          iduraVerify.handleActivityResult(
            stableRequestCode(customTabLauncherKeyFor(TEST_DOMAIN, TEST_CLIENT_ID)),
            Activity.RESULT_CANCELED,
            null,
          ),
        )
      }
    }
  }

  /**
   * Which machinery a host gets follows the activity's runtime type, not the type the call site
   * declared. A `ComponentActivity` handed over as a plain `Activity` — all an embedding plugin
   * ever has to work with — still delivers through the activity's own registry, so both constructors
   * behave alike and a host's base class cannot quietly decide whether forwarding is load-bearing.
   */
  @Test
  fun aComponentActivityReachingThePlainConstructorUsesItsOwnRegistry() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        // Declared as `Activity` so overload resolution picks the plain-`Activity` constructor.
        val host: Activity = activity

        val iduraVerify =
          IduraVerify(
            clientID = TEST_CLIENT_ID,
            domain = TEST_DOMAIN,
            activity = host,
          )

        assertEquals(2, activity.activityResultRegistry.iduraKeys().size)

        // Nothing to forward, the host's registry does the routing.
        assertFalse(
          iduraVerify.handleActivityResult(
            stableRequestCode(customTabLauncherKeyFor(TEST_DOMAIN, TEST_CLIENT_ID)),
            Activity.RESULT_CANCELED,
            null,
          ),
        )
      }
    }
  }

  /** The duplicate-instance claim is per activity, whichever kind of host it is. */
  @Test
  fun identicallyConfiguredInstancesOnAPlainActivityAreRejected() {
    ActivityScenario.launch(TestPlainHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        newIduraVerify(activity)

        assertThrows(IllegalStateException::class.java) { newIduraVerify(activity) }
      }
    }
  }

  /**
   * The domain is never reached: the constructor only kicks off the OIDC and JWKS loads, and both
   * swallow their failures. What is under test is the registration it does synchronously.
   */
  private fun newIduraVerify(
    activity: ComponentActivity,
    clientID: String = TEST_CLIENT_ID,
  ) = IduraVerify(
    clientID = clientID,
    domain = TEST_DOMAIN,
    activity = activity,
  )

  /** See [newIduraVerify]. Resolves to the plain-`Activity` constructor, not the one above. */
  private fun newIduraVerify(
    activity: TestPlainHostActivity,
    clientID: String = TEST_CLIENT_ID,
  ) = IduraVerify(
    clientID = clientID,
    domain = TEST_DOMAIN,
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
