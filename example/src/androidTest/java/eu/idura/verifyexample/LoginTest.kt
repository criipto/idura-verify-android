package eu.idura.verifyexample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.textAsString
import androidx.test.uiautomator.uiAutomator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginTest {
  @Test
  fun runMockLogin() =
    uiAutomator {
      startApp("eu.idura.verifyexample")
      onElement { textAsString() == "Login with Mock" }.click()
      onElement { textAsString() == "Logged in!" }.click()
    }

  fun assertTabType(device: UiDevice) {
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

      waitForAppToBeVisible("com.android.chrome")
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
      // device.swipe(280, 2200, 820, 2200, 40)
      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)

      onElement { textAsString() == "Logged in!" }.click()
      onElement { textAsString() == "dkmitid" }
    }
  }

//  @Test
//  fun runSEBankID() {
//    uiAutomator {
//      startApp("eu.idura.verifyexample")
//
//      onElement { textAsString() == "Login with SE BankID" }.click()
//      waitForAppToBeVisible("com.android.chrome")
//      onElement { textAsString()?.lowercase() == "open" }.click()
//
//      waitForAppToBeVisible("com.bankid.bus")
//
//      onElement { textAsString()?.lowercase() == "identify with security code" }.click()
// //      onElement { textAsString()?.lowercase() == "cancel" }.click()
//
//      val elem = onElement { textAsString()?.lowercase() == "1" }
//
//      device.swipe(
//        elem.visibleCenter.x - 2,
//        elem.visibleCenter.y + 2,
//        elem.visibleCenter.x + 4,
//        elem.visibleCenter.y + 8,
//        8,
//      )
//      println("${elem.visibleCenter.x} ${elem.visibleCenter.y}")
//      Thread.sleep(2125)
//      // elem.click(Point(elem.visibleCenter.x + 13, elem.visibleCenter.y - 7), 12)
//
// //      onElement { textAsString()?.lowercase() == "1" }.click(Point(10, 10))
//
//      println("sleeping")
//      Thread.sleep(30_000)
//      println("waking")
//
// //      Thread.sleep(123)
// //      onElement { textAsString()?.lowercase() == "2" }.click()
// //      Thread.sleep(1312)
// //      onElement { textAsString()?.lowercase() == "2" }.click()
// //      Thread.sleep(2)
// //      onElement { textAsString()?.lowercase() == "3" }.click()
// //      Thread.sleep(55)
// //      onElement { textAsString()?.lowercase() == "3" }.click()
// //
// //      onElement { textAsString()?.lowercase() == "identify" }.click()
//
//      waitForAppToBeVisible("eu.idura.verifyexample", 30_000)
//
//      onElement { textAsString() == "Logged in!" }.click()
//      onElement { textAsString() == "sebankid" }
//    }
//  }
}
