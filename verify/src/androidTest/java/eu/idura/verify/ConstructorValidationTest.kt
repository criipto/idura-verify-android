package eu.idura.verify

import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real Idura domain, being the tenant this repository targets, see the `iduraDomain` Gradle
 * property. Not the `verify-android-tests.invalid` host the rest of the instrumented suite uses:
 * the point of these tests is which configurations the constructor accepts and rejects, so the
 * accepted one has to be a configuration a consumer could actually pass.
 */
private const val VALID_DOMAIN = "samples.test.idura.broker"

/**
 * Covers the constructor's rejection of a misconfigured domain or redirect URI. Instrumented
 * because the redirect URI checks are about how `android.net.Uri` parses what it is handed, which
 * the JVM stub does not do — the domain checks themselves are unit tested in [DomainUnitTest].
 */
@RunWith(AndroidJUnit4::class)
class ConstructorValidationTest {
  /**
   * The default redirect URI is derived from the domain, so a domain carrying a scheme yields
   * `https://https://…`, which parses as an https URI and would only fail later as an
   * unresolvable endpoint.
   */
  @Test
  fun domainCarryingASchemeIsRejected() {
    assertRejects(domain = "https://$VALID_DOMAIN")
  }

  /** An empty domain yields the valid but useless `https:///android/callback`. */
  @Test
  fun emptyDomainIsRejected() {
    assertRejects(domain = "")
  }

  @Test
  fun redirectUriOnAnotherSchemeIsRejected() {
    assertRejects(redirectUri = "myapp://callback".toUri())
  }

  /**
   * An opaque URI has no host or path for the auth tab to recognise the callback by, and passes
   * the scheme check.
   */
  @Test
  fun opaqueRedirectUriIsRejected() {
    assertRejects(redirectUri = "https:callback".toUri())
  }

  @Test
  fun redirectUriWithoutAPathIsRejected() {
    assertRejects(redirectUri = "https://$VALID_DOMAIN".toUri())
  }

  /** The configuration the README documents, to keep the checks above from rejecting everything. */
  @Test
  fun aBareDomainWithTheDefaultRedirectUriIsAccepted() {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        IduraVerify(
          clientID = "urn:idura:verify:android:tests",
          domain = VALID_DOMAIN,
          activity = activity,
        )
      }
    }
  }

  private fun assertRejects(
    domain: String = VALID_DOMAIN,
    redirectUri: Uri? = null,
  ) {
    ActivityScenario.launch(TestHostActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        assertThrows(IllegalArgumentException::class.java) {
          if (redirectUri == null) {
            IduraVerify(
              clientID = "urn:idura:verify:android:tests",
              domain = domain,
              activity = activity,
            )
          } else {
            IduraVerify(
              clientID = "urn:idura:verify:android:tests",
              domain = domain,
              redirectUri = redirectUri,
              activity = activity,
            )
          }
        }
      }
    }
  }
}
