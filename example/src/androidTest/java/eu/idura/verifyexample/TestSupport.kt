package eu.idura.verifyexample

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

/** Runs a shell command (as the `shell` user) and waits for it to finish. */
internal fun shell(command: String) {
  val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
  ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
    it.readBytes()
  }
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
