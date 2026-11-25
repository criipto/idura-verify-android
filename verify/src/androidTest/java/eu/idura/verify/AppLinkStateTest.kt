package eu.idura.verify

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLinkStateTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
  fun reportsADeclaredHostThatCannotVerifyAsNone() {
    assertEquals(
      AppLinkState(hostState = "none", linkHandlingAllowed = true),
      appLinkState(context, "verify-android-tests.invalid"),
    )
  }

  @Test
  @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
  fun reportsAHostTheManifestDoesNotDeclareAsUndeclared() {
    assertEquals("undeclared", appLinkState(context, "example.com")?.hostState)
  }

  @Test
  @SdkSuppress(maxSdkVersion = Build.VERSION_CODES.R)
  fun reportsNothingBeforeAndroid12() {
    assertNull(appLinkState(context, "verify-android-tests.invalid"))
  }
}
