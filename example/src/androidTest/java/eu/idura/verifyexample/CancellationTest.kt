package eu.idura.verifyexample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.textAsString
import androidx.test.uiautomator.uiAutomator
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

// The SDK maps both an aborted browser flow and the IdP's `access_denied` to
// UserCancelledException, which the example app renders as "User cancelled login".
// Each test cancels at a different point — closing the browser tab, the in-browser
// cancel control, or inside the app-switch app — and asserts that message comes back.
@RunWith(AndroidJUnit4::class)
class CancellationTest {
  // CaptureOnFailure (outer) dumps a screenshot + hierarchy on the final failure;
  // RetryRule (inner) re-runs @Retry-annotated flaky tests so a one-off app-switch
  // timing race doesn't fail the whole run.
  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(CaptureOnFailure()).around(RetryRule())

  @Test
  fun runMitIDCancelByClosingBrowser() {
    shell("am force-stop com.android.chrome")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with MitID" }.click()
      onElement { textAsString() == "CONTINUE" } // wait for the MitID page to load
      device.closeBrowserTab()
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }

  @Test
  fun runMitIDCancelInUi() {
    shell("am force-stop com.android.chrome")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with MitID" }.click()
      onElement { textAsString() == "Cancel" }.click()
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }

  @Test
  fun runVippsCancelByClosingBrowser() {
    shell("am force-stop com.android.chrome")
    shell("am force-stop no.dnb.vipps.mt")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with Vipps" }.click()
      onElement { textAsString() == "Open Vipps" } // wait for the Vipps page to load
      device.closeBrowserTab()
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }

  @Test
  fun runVippsCancelInUi() {
    shell("am force-stop com.android.chrome")
    shell("am force-stop no.dnb.vipps.mt")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with Vipps" }.click()
      val cancel =
        device.wait(Until.findObject(By.desc("Cancel and go back to Criipto")), 10_000)
          ?: error("Vipps web Cancel not found")
      cancel.click()
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }

  @Test
  fun runNorwegianBankIDCancelByClosingBrowser() {
    shell("am force-stop com.android.chrome")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with NO BankID" }.click()
      onElement { textAsString() == "Confirm login" }
      device.closeBrowserTab()
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }

  @Test
  fun runNorwegianBankIDCancelInUi() {
    shell("am force-stop com.android.chrome")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with NO BankID" }.click()
      onElement { textAsString() == "Decline" }.click()
      // BankID shows a "You cancelled" confirmation; acknowledge it to return.
      onElement { textAsString() == "OK" }.click()
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }

  @Test
  fun runSEBankIDCancelByClosingBrowser() {
    shell("am force-stop com.android.chrome")
    shell("am force-stop com.bankid.bus")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with SE BankID same device" }.click()
      onElement { textAsString() == "Open" }
      device.closeBrowserTab()
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }

  @Test
  @Retry(attempts = 3)
  fun runSEBankIDCancelInApp() {
    shell("am force-stop com.android.chrome")
    shell("am force-stop com.bankid.bus")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with SE BankID same device" }.click()
      onElement { textAsString()?.lowercase() == "open" }.click()
      waitForAppToBeVisible("com.bankid.bus")
      onElement { textAsString()?.lowercase() == "identify with security code" }.click()
      onElement { textAsString() == "1" } // wait for the keypad
      // Abort the identification from inside BankID via its top-bar back button.
      val up =
        device.wait(Until.findObject(By.desc("Navigate up")), 5_000)
          ?: error("BankID 'Navigate up' button not found")
      up.click()
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }

  @Test
  fun runVippsCancelInApp() {
    shell("am force-stop com.android.chrome")
    shell("am force-stop no.dnb.vipps.mt")
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with Vipps" }.click()
      onElement { textAsString() == "Open Vipps" }.click()
      device.openVippsApp()
      device.enterVippsCode("1236")
      // The Vipps "Do you want to log in to Criipto?" confirmation has Continue / Cancel.
      onElement { textAsString() == "Cancel" }.click()
      // Cancel opens an "Are you sure you want to cancel log in?" dialog; confirm with Yes.
      onElement { textAsString() == "Yes" }.click()
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
      onElement { textAsString() == "User cancelled login" }
    }
  }
}
