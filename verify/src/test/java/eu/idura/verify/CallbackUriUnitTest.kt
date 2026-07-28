package eu.idura.verify

import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric only for a real android.net.Uri; pinned to a fixed SDK since none of this is
// version dependent.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallbackUriUnitTest {
  @Test
  fun moves_fragment_parameters_to_the_query() {
    val uri =
      "https://example.idura.broker/android/callback#code=abc123&state=xyz789"
        .toUri()
        .withFragmentParametersAsQuery()

    assertEquals("abc123", uri.getQueryParameter("code"))
    assertEquals("xyz789", uri.getQueryParameter("state"))
    assertEquals(null, uri.fragment)
  }

  @Test
  fun moves_fragment_error_responses_to_the_query() {
    val uri =
      "https://example.idura.broker/android/callback#error=access_denied&error_description=User%20cancelled"
        .toUri()
        .withFragmentParametersAsQuery()

    assertEquals("access_denied", uri.getQueryParameter("error"))
    assertEquals("User cancelled", uri.getQueryParameter("error_description"))
  }

  @Test
  fun preserves_percent_encoded_fragment_values() {
    val uri =
      "https://example.idura.broker/android/callback#state=a%2Bb%2Fc%3Dd&code=abc123"
        .toUri()
        .withFragmentParametersAsQuery()

    assertEquals("a+b/c=d", uri.getQueryParameter("state"))
    assertEquals("abc123", uri.getQueryParameter("code"))
  }

  @Test
  fun keeps_existing_query_parameters() {
    val uri =
      "https://example.idura.broker/android/callback?$APPSWITCH_QUERY_PARAM#code=abc123"
        .toUri()
        .withFragmentParametersAsQuery()

    assertEquals(
      setOf(APPSWITCH_QUERY_PARAM, "code"),
      uri.queryParameterNames,
    )
    assertEquals("abc123", uri.getQueryParameter("code"))
  }

  @Test
  fun leaves_query_mode_callbacks_untouched() {
    val original = "https://example.idura.broker/android/callback?code=abc123&state=xyz789".toUri()

    assertEquals(original, original.withFragmentParametersAsQuery())
  }

  @Test
  fun is_idempotent() {
    val once =
      "https://example.idura.broker/android/callback#code=abc123&state=xyz789"
        .toUri()
        .withFragmentParametersAsQuery()

    assertEquals(once, once.withFragmentParametersAsQuery())
  }

  @Test
  fun leaves_fragments_that_hold_no_parameters_untouched() {
    val original = "https://example.idura.broker/android/callback#done".toUri()

    assertEquals(original, original.withFragmentParametersAsQuery())
  }
}
