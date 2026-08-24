package eu.idura.verify

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainUnitTest {
  @Test
  fun `accepts the shapes an Idura domain comes in`() {
    listOf(
      // An Idura managed domain, and the vanity domain equivalent.
      "samples.criipto.id",
      "login.example.com",
      // Hyphens inside a label, and the domain the instrumented tests build against.
      "verify-android-tests.invalid",
      // Two labels is the minimum, and a punycode label is still just a label.
      "example.com",
      "xn--rksmrgs-5wao1o.se",
    ).forEach { requireBareHost(it) }
  }

  @Test
  fun `rejects a domain carrying anything but the host`() {
    listOf(
      // The mistake that motivated this: the scheme survives into `https://https://…`.
      "https://samples.criipto.id",
      "//samples.criipto.id",
      "samples.criipto.id/",
      "samples.criipto.id/android/callback",
      "samples.criipto.id:443",
      "user@samples.criipto.id",
      "samples.criipto.id?query",
      "samples.criipto.id.",
      " samples.criipto.id ",
    ).forEach {
      assertThrows(it, IllegalArgumentException::class.java) { requireBareHost(it) }
    }
  }

  @Test
  fun `rejects a domain that is not a host name`() {
    listOf(
      // An empty domain yields the valid but useless `https:///android/callback`.
      "",
      " ",
      // Single label: no Idura domain is one, and it cannot be app link verified either.
      "localhost",
      "-leading.example.com",
      "trailing-.example.com",
      "under_score.example.com",
      "two words.example.com",
      "æøå.example.com",
    ).forEach {
      assertThrows(it, IllegalArgumentException::class.java) { requireBareHost(it) }
    }
  }

  /** The issuer of the ID token is lower case, and it is compared verbatim, see `exchangeCode`. */
  @Test
  fun `rejects a domain that is not lower case`() {
    val ex =
      assertThrows(IllegalArgumentException::class.java) {
        requireBareHost("Samples.Criipto.ID")
      }

    // Distinct from the shape complaint, so the message points at the actual problem.
    assertTrue(
      "unexpected message: ${ex.message}",
      ex.message!!.contains("must be lower case"),
    )
  }
}
