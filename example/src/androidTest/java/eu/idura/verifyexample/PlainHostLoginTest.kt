package eu.idura.verifyexample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.textAsString
import androidx.test.uiautomator.uiAutomator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The same flows as [LoginTest] and [CancellationTest], but hosted on [PlainHostActivity] — a plain
 * `android.app.Activity` rather than a `ComponentActivity`. Such a host has no activity result
 * registry, so the SDK brings its own and launches the browser through `startActivityForResult`,
 * with the host forwarding the result back from `onActivityResult`. Nothing else about a login
 * differs, and neither the SDK's own instrumented tests nor a JVM test can cover that seam against
 * a real browser round trip, which is what these two do.
 *
 * Two flows rather than the whole of both suites again: between them they cover the result shapes
 * that reach the SDK's registry — RESULT_OK carrying the callback URI, and the bare RESULT_CANCELED
 * a closed tab produces. Every other case in those suites comes back through the same
 * `dispatchResult` call, so running them all twice would double the emulator time for no new
 * coverage.
 */
@RunWith(AndroidJUnit4::class)
class PlainHostLoginTest {
  @get:Rule
  val captureOnFailure = CaptureOnFailure()

  @Test
  fun runMockLoginOnAPlainActivity() =
    uiAutomator {
      startPlainHostActivity()
      onElement { textAsString() == "Login with Mock" }.click()
      // Same generous wait as LoginTest#runMockLogin: a cold emulator has been seen to take past
      // onElement's 10s default to finish the whole OIDC round trip.
      onElement(timeoutMs = LOGIN_TIMEOUT_MS) { textAsString() == "Logged in!" }
      onElement { textAsString() == "mock" }
    }

  /**
   * Cancels by closing the tab, which returns RESULT_CANCELED with no intent — the one result shape
   * a successful login never produces. MitID rather than the mock provider, because a mock login
   * completes without waiting for the user, leaving nothing to cancel.
   */
  @Test
  fun cancelByClosingBrowserOnAPlainActivity() {
    shell("am force-stop com.android.chrome")
    uiAutomator {
      startPlainHostActivity()
      onElement { textAsString() == "Login with MitID" }.click()
      onElement { textAsString() == "CONTINUE" } // wait for the MitID page to load
      device.closeBrowserTab()
      waitForAppToBeVisible(EXAMPLE_PACKAGE, 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }

  private companion object {
    /** See [LoginTest]'s namesake. */
    const val LOGIN_TIMEOUT_MS = 30_000L
  }
}
