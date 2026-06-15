package eu.idura.verifyexample

import android.content.Intent
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Instrumentation runner that completes Chrome's one-time first-run experience
 * before the suite starts. A freshly-provisioned device gates the first browser
 * tab behind a short run of full-screen prompts (a terms-of-service page, then a
 * sign-in/sync promo) that would otherwise intercept the OAuth redirect in every
 * login test. Clearing it once here keeps the tests focused on the flow under
 * test; once the FRE has been completed this is a quick no-op.
 *
 * The number of prompts and their wording vary by Chrome version (e.g. API 31
 * shows a "Turn on sync? / No thanks" promo while newer builds show "Use without
 * an account"), so rather than script an exact sequence we dismiss whichever
 * known affordances appear until no further prompt shows up.
 */
class IduraTestRunner : AndroidJUnitRunner() {
  override fun onStart() {
    dismissChromeFirstRun()
    super.onStart()
  }

  private fun dismissChromeFirstRun() {
    val device = UiDevice.getInstance(this)
    val launch =
      context.packageManager
        .getLaunchIntentForPackage(CHROME_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ?: return
    context.startActivity(launch)
    device.wait(Until.hasObject(By.pkg(CHROME_PACKAGE)), 15_000)

    var dismissed = 0
    while (dismissed++ < MAX_FRE_PROMPTS) {
      val button = waitForFreButton(device) ?: break
      button.click()
      device.waitForIdle()
    }
    device.executeShellCommand("am force-stop $CHROME_PACKAGE")
  }

  /** Polls for any known FRE dismiss button, returning null once none appears. */
  private fun waitForFreButton(device: UiDevice): UiObject2? {
    val deadline = System.currentTimeMillis() + FRE_PROMPT_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      for (selector in freDismissSelectors) {
        device.findObject(selector)?.let { return it }
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    return null
  }

  private companion object {
    const val CHROME_PACKAGE = "com.android.chrome"
    const val MAX_FRE_PROMPTS = 4
    const val FRE_PROMPT_TIMEOUT_MS = 6_000L
    const val POLL_INTERVAL_MS = 250L

    // Affordances that advance past or decline an FRE prompt. Only one set is on
    // screen at a time, and after the FRE completes none of them exist, so the
    // generic `negative_button` id is safe to click within this startup window.
    val freDismissSelectors =
      listOf(
        By.res(CHROME_PACKAGE, "terms_accept"), // terms-of-service gate
        By.text("Use without an account"), // sign-in promo (newer Chrome)
        By.res(CHROME_PACKAGE, "negative_button"), // "No thanks" on the sync promo
      )
  }
}
