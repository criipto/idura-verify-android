package eu.idura.verify

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SigningCertificatesTest {
  @Test
  fun readsTheFingerprintOfTheCommittedDebugKeystore() {
    assertEquals(
      listOf(
        "8D:42:58:35:AE:15:03:2D:D6:BD:6B:76:79:CC:61:CF:F1:DB:4C:28:0C:F2:92:B4:19:28:77:FA:46:DE:2B:77",
      ),
      signingCertificateFingerprints(InstrumentationRegistry.getInstrumentation().targetContext),
    )
  }
}
