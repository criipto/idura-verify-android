package eu.idura.verifyexample

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiAutomatorTestScope
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

internal const val EXAMPLE_PACKAGE = "eu.idura.verifyexample"

/** Runs a shell command (as the `shell` user) and waits for it to finish. */
internal fun shell(command: String) {
  val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
  ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
    it.readBytes()
  }
}

/**
 * Brings [PlainHostActivity] up. It has no launcher intent filter, so `startApp` cannot reach it;
 * the instrumentation runs in the app's own process, so it can start the activity directly despite
 * it not being exported — which `am start` cannot, the shell user having no START_ANY_ACTIVITY.
 *
 * CLEAR_TASK rather than a force-stop of the app: since the instrumentation shares the app's
 * process, force-stopping it would kill the test along with it. Clearing the task is enough anyway,
 * as all it has to do is get a `MainActivity` left over from an earlier test out of the way.
 */
internal fun UiAutomatorTestScope.startPlainHostActivity() {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  context.startActivity(
    Intent(context, PlainHostActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
  )
  check(waitForAppToBeVisible(EXAMPLE_PACKAGE)) { "PlainHostActivity did not become visible" }

  // Both hosts render the same labels, so the package being visible could still be a MainActivity
  // that survived the CLEAR_TASK. A view ID tells them apart: the plain host's Views expose theirs
  // to accessibility, while the Compose screen emits none. Throws if it never shows up.
  onElement { viewIdResourceName == "$EXAMPLE_PACKAGE:id/login_with_mock" }
}

/** Closes the Chrome Custom Tab via its "X"; the SDK treats this as cancellation. */
internal fun UiDevice.closeBrowserTab() {
  val close =
    wait(Until.findObject(By.desc("Close tab")), 5_000)
      ?: error("Chrome 'Close tab' button not found")
  close.click()
}

/**
 * Drives the Android biometric prompt that hands off to the Vipps app. The prompt's
 * device-credential option ("Enter your code") launches Vipps, but it can swallow a
 * too-early tap, and the tapped node can go stale mid-click — so we retry, tolerating
 * a stale click, until the Vipps app is actually in the foreground.
 */
internal fun UiDevice.openVippsApp() {
  var tries = 0
  while (!hasObject(By.pkg("no.dnb.vipps.mt")) && tries++ < 4) {
    runCatching { wait(Until.findObject(By.text("Enter your code")), 8_000)?.click() }
    wait(Until.hasObject(By.pkg("no.dnb.vipps.mt")), 5_000)
  }
}

/** Taps a numeric code on the Vipps app's in-app keypad (it accepts injected taps). */
internal fun UiDevice.enterVippsCode(code: String) {
  for (digit in code) {
    val key =
      wait(Until.findObject(By.text(digit.toString())), 5_000)
        ?: error("Vipps keypad digit '$digit' not found")
    key.click()
    Thread.sleep(250)
  }
}
