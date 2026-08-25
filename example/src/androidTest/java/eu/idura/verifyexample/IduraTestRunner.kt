package eu.idura.verifyexample

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice

/**
 * Instrumentation runner that completes Chrome's one-time first run before the suite
 * starts. Until it is done, Chrome shows a full-screen first-run prompt in place of any
 * tab it is asked to open — including the Custom/Auth Tab a login test drives — so the
 * prompt swallows the OAuth redirect and the test times out waiting for a logged-in
 * screen it will never see.
 *
 * The awkward part is knowing when the first run is over. Its prompts render lazily, so
 * a gap with nothing recognisable on screen does not mean they are finished, and that is
 * how this used to fail on a slow CI emulator: a fixed quiet window elapsed before the
 * sign-in prompt had rendered, Chrome was force-stopped mid-first-run, and the prompt
 * then resurfaced on top of the login tab. Their number and wording also vary by Chrome
 * version, so the sequence cannot simply be scripted.
 *
 * So rather than dismiss a fixed number of prompts and treat a quiet spell as proof they
 * are done, wait for a positive signal that Chrome has moved on to the browser proper,
 * dismissing first-run affordances as they appear. Chrome's later one-off prompts
 * (Privacy Sandbox, notification opt-in) are deliberately left alone: they only ever
 * show up in the browser UI and not in a tab, so they do not affect a login, and they
 * double as that "past the first run" signal. Once the first run is behind us this is a
 * quick no-op.
 *
 * Everything here is best effort. Chrome swapping a prompt out from under a click used to
 * throw straight out of [onStart], which killed the instrumentation before a single test
 * ran, so failures are logged and left for the tests to surface on their own terms.
 */
class IduraTestRunner : AndroidJUnitRunner() {
  override fun onStart() {
    runCatching { completeChromeFirstRun() }
      .onFailure { Log.w(TAG, "Could not prepare Chrome; logins may fail", it) }
    super.onStart()
  }

  private fun completeChromeFirstRun() {
    val device = UiDevice.getInstance(this)
    val launch =
      context.packageManager
        .getLaunchIntentForPackage(CHROME_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ?: return
    context.startActivity(launch)

    val deadline = SystemClock.uptimeMillis() + TOTAL_TIMEOUT_MS
    while (SystemClock.uptimeMillis() < deadline) {
      if (pastFirstRunSelectors.any { device.hasObject(it) }) {
        device.executeShellCommand("am force-stop $CHROME_PACKAGE")
        return
      }
      val button = firstRunDismissSelectors.firstNotNullOfOrNull { device.findObject(it) }
      if (button == null) {
        // Nothing to act on yet. The next prompt may still be rendering, so keep
        // watching rather than concluding the first run is over.
        Thread.sleep(POLL_INTERVAL_MS)
        continue
      }
      // Chrome can swap the prompt out between finding the button and clicking it, which
      // makes the node stale. Retry on the next pass instead of giving up on the loop.
      runCatching { button.click() }
        .onFailure { Log.d(TAG, "Prompt changed under a click; retrying", it) }
      device.waitForIdle()
    }
    Log.w(TAG, "Chrome did not get past its first run in ${TOTAL_TIMEOUT_MS}ms; logins may fail")
    device.executeShellCommand("am force-stop $CHROME_PACKAGE")
  }

  private companion object {
    const val TAG = "IduraTestRunner"
    const val CHROME_PACKAGE = "com.android.chrome"
    const val POLL_INTERVAL_MS = 250L

    /**
     * Generous on purpose: it is only reached when something is actually wrong, and
     * overshooting costs wall-clock on a run that was going to fail anyway.
     */
    const val TOTAL_TIMEOUT_MS = 60_000L

    /**
     * Evidence that Chrome is running the browser rather than the first-run flow:
     * somewhere to type a URL (the New Tab Page search box, or the omnibox when a tab
     * was restored), or one of the one-off dialogs Chrome only raises over the browser.
     */
    val pastFirstRunSelectors =
      listOf(
        By.res(CHROME_PACKAGE, "search_box_text"),
        By.res(CHROME_PACKAGE, "url_bar"),
        By.res(CHROME_PACKAGE, "modal_dialog_view"),
        By.res(CHROME_PACKAGE, "privacy_sandbox_dialog_scroll_view"),
      )

    /**
     * Affordances that advance past or decline a first-run prompt. Matching by id keeps
     * this working on a non-English device; the text is kept as a fallback for builds
     * that render the prompt without the id.
     */
    val firstRunDismissSelectors =
      listOf(
        By.res(CHROME_PACKAGE, "terms_accept"), // terms-of-service gate
        By.res(CHROME_PACKAGE, "signin_fre_dismiss_button"), // "Use without an account"
        By.text("Use without an account"),
        By.res(CHROME_PACKAGE, "negative_button"), // "No thanks" on the sync promo
      )
  }
}
