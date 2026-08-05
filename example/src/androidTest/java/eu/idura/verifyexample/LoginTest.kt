package eu.idura.verifyexample

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.textAsString
import androidx.test.uiautomator.uiAutomator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginTest {
  @get:Rule
  val captureOnFailure = CaptureOnFailure()

  @Test
  fun runMockLogin() =
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with Mock" }.click()
      // A mock login still round-trips the browser, the token endpoint and the JWKS
      // fetch, which on a cold CI emulator has been observed to land just past
      // onElement's 10s default. Wait long enough that a slow-but-working login is
      // not reported as a missing element.
      onElement(timeoutMs = LOGIN_TIMEOUT_MS) { textAsString() == "Logged in!" }.click()
    }

  fun assertTabType(device: UiDevice) =
    uiAutomator {
      waitForAppToBeVisible("com.android.chrome")
      val minimizeButton =
        device.findObject(
          By.res("com.android.chrome:id/custom_tabs_minimize_button"),
        )

      if (BuildConfig.TAB_TYPE == "AUTH_TAB") {
        assertNull("Minimize button should not exist in auth tab", minimizeButton)
      } else if (BuildConfig.TAB_TYPE == "CUSTOM_TAB") {
        assertNotNull("Minimize button should exist in custom tab", minimizeButton)
      } else {
        throw Error("Unsupported tab type ${BuildConfig.TAB_TYPE}")
      }
    }

  @Test
  fun runMitID() {
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with MitID" }.click()

      assertTabType(device)

      onElement { textAsString()?.lowercase() == "continue" }.click()
      onElement { textAsString()?.lowercase()?.trim() == ("open mitid app") }.click()

      waitForAppToBeVisible("dk.mitid.app.android")

      device.dumpWindowHierarchy(System.out)

      onElement { textAsString()?.lowercase() == "1" }.click()
      onElement { textAsString()?.lowercase() == "1" }.click()
      onElement { textAsString()?.lowercase() == "2" }.click()
      onElement { textAsString()?.lowercase() == "2" }.click()
      onElement { textAsString()?.lowercase() == "3" }.click()
      onElement { textAsString()?.lowercase() == "3" }.click()

      val button =
        device.wait(
          Until.findObject(By.desc("Approve")),
          5000,
        )

      button.swipe(Direction.RIGHT, 1.0f)
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)

      onElement { textAsString() == "Logged in!" }.click()
      onElement { textAsString() == "dkmitid" }
    }
  }

  // The test BankID security code for the "Foo Bar" / "Test av BankID" user.
  private val seBankIdSecurityCode = "112233"

  /**
   * Local-only test against a real device + the real BankID test app. It cannot run
   * on the CI emulator (BankID refuses to run emulated).
   *
   * BankID's PIN keypad detects and rejects injected taps (UiAutomator `click()` /
   * `adb shell input tap`) and fails the login. Only the keypad is guarded —
   * navigating the app, Chrome and the BankID intro screen with ordinary taps, and
   * reading the UI hierarchy, are all fine. So we use UiAutomator for everything
   * except the keypad, which we drive through a kernel-level UHID touchscreen that
   * looks like real hardware input. See [UhidTouch].
   */
  @Test
  fun runSEBankIDSameDevice() {
    // A stale BankID order or a leftover Custom Tab from a previous run can break
    // the post-login redirect back into the app, so start each run from a clean slate.
    shell("am force-stop com.bankid.bus")
    shell("am force-stop com.android.chrome")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with SE BankID same device" }.click()

      assertTabType(device)

      onElement { textAsString()?.lowercase() == "open" }.click()

      runSEBankIDInApp()

      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "Logged in!" }
      onElement { textAsString() == "sebankid" }
    }
  }

  /**
   * Local-only test against a real device + the real BankID test app. It cannot run
   * on the CI emulator (BankID refuses to run emulated).
   *
   * BankID's PIN keypad detects and rejects injected taps (UiAutomator `click()` /
   * `adb shell input tap`) and fails the login. Only the keypad is guarded —
   * navigating the app, Chrome and the BankID intro screen with ordinary taps, and
   * reading the UI hierarchy, are all fine. So we use UiAutomator for everything
   * except the keypad, which we drive through a kernel-level UHID touchscreen that
   * looks like real hardware input. See [UhidTouch].
   */
  @Test
  fun runSEBankIDSelectorPage() {
    // A stale BankID order or a leftover Custom Tab from a previous run can break
    // the post-login redirect back into the app, so start each run from a clean slate.
    shell("am force-stop com.bankid.bus")
    shell("am force-stop com.android.chrome")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with SE BankID selector page" }.click()

      assertTabType(device)

      onElement { textAsString()?.lowercase()?.endsWith("on this device") == true }.click()
      onElement { textAsString()?.lowercase() == "open" }.click()

      runSEBankIDInApp()

      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "Logged in!" }
      onElement { textAsString() == "sebankid" }
    }
  }

  fun runSEBankIDInApp() =
    uiAutomator {
      waitForAppToBeVisible("com.bankid.bus")

      onElement { textAsString()?.lowercase() == "identify with security code" }.click()
      // Wait for the keypad before switching to hardware taps.
      onElement { textAsString() == "1" }

      UhidTouch.start(device.displayWidth, device.displayHeight).use { uhid ->
        // The keypad occasionally drops a single hardware tap (most often on the
        // repeated digits in "112233"). Rather than tap all six blind, enter them one
        // at a time and confirm each digit lands in the field before moving on,
        // re-tapping the same key if it didn't register.
        val codeField = By.res("com.bankid.bus", "SecurityCodeEditText")
        // An empty field shows its hint ("Security Code"), so capture that and treat
        // it as zero entered digits; once digits are typed the text is their count.
        val emptyHint = device.findObject(codeField)?.text

        fun enteredDigits(): Int {
          val text = device.findObject(codeField)?.text
          return if (text.isNullOrEmpty() || text == emptyHint) 0 else text.length
        }

        fun tapKey(digit: Char) {
          val selector = By.clazz("android.widget.Button").textStartsWith(digit.toString())
          val key =
            device.wait(Until.findObject(selector), 5_000)
              ?: error("BankID keypad digit '$digit' not found")
          val center = key.visibleCenter
          uhid.tap(center.x, center.y)
        }

        seBankIdSecurityCode.forEachIndexed { index, digit ->
          val target = index + 1
          var taps = 0
          while (enteredDigits() < target) {
            check(taps < 5) {
              "BankID keypad digit '$digit' (#$target) didn't register after $taps taps"
            }
            tapKey(digit)
            taps++
            // Give the field time to update before re-checking, so a slow update
            // isn't mistaken for a dropped tap.
            val deadline = System.currentTimeMillis() + 800
            while (enteredDigits() < target && System.currentTimeMillis() < deadline) {
              Thread.sleep(50)
            }
          }
          check(enteredDigits() == target) {
            "BankID keypad over-registered at digit '$digit' (#$target): ${enteredDigits()} digits"
          }
        }

        val identify =
          device.wait(Until.findObject(By.text("Identify")), 5_000)
            ?: error("BankID 'Identify' button not found")
        val center = identify.visibleCenter
        uhid.tap(center.x, center.y)
      }
    }

  /**
   * Types a numeric code on the secure keyguard credential pad via key events. The
   * pad is FLAG_SECURE (no screenshots, not in the a11y tree) but accepts injected
   * key events — unlike BankID's in-app keypad, so no UHID is needed here.
   */
  private fun UiDevice.enterPin(pin: String) {
    pin.forEach { pressKeyCode(KeyEvent.KEYCODE_0 + (it - '0')) }
    pressKeyCode(KeyEvent.KEYCODE_ENTER)
  }

  /**
   * Local-only test of the Norwegian BankID (Aletheia) browser flow. No external app
   * is involved — it runs entirely in the Custom Tab, then the device passkey is
   * unlocked via the Android biometric prompt. Requires the device to have a
   * screen-lock PIN of 1337; we choose "Use PIN" rather than the fingerprint sensor,
   * which can't be injected on a real device.
   */
  @Test
  fun runNorwegianBankID() {
    shell("am force-stop com.android.chrome")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with NO BankID" }.click()

      assertTabType(device)

      onElement { textAsString() == "Confirm login" }.click()
      // Android biometric prompt — fall back to the screen-lock PIN.
      onElement { textAsString() == "Use PIN" }.click()
      Thread.sleep(1_500) // let the secure credential pad appear
      device.enterPin("1337")

      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "Logged in!" }
      onElement { textAsString() == "nobankid-oidc" }
    }
  }

  /**
   * Local-only test of the Vipps (MobilePay) login. It app-switches to the Vipps app,
   * which is unlocked with its own passcode (1236, distinct from the device screen-lock
   * PIN), then confirms the login. Requires the Vipps test app installed and enrolled.
   */
  @Test
  fun runVipps() {
    shell("am force-stop com.android.chrome")
    shell("am force-stop no.dnb.vipps.mt")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with Vipps" }.click()

      assertTabType(device)

      onElement { textAsString() == "Open Vipps" }.click()
      device.openVippsApp()
      device.enterVippsCode("1236")
      // Vipps "Do you want to log in to Criipto?" confirmation.
      onElement { textAsString() == "Continue" }.click()

      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "Logged in!" }
      onElement { textAsString() == "novippslogin" }
    }
  }

  private companion object {
    /**
     * Generous enough to cover a cold emulator completing the whole OIDC round trip,
     * since overshooting only costs wall-clock on a run that was going to fail anyway.
     */
    const val LOGIN_TIMEOUT_MS = 30_000L
  }
}
