package eu.idura.verify

/**
 * A dotted DNS host name: two or more labels of 1-63 characters, neither starting nor ending in a
 * hyphen. Deliberately narrow, since every domain Idura hands out has this shape.
 */
private val BARE_HOST =
  Regex(
    "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+",
    RegexOption.IGNORE_CASE,
  )

/**
 * Rejects a [domain] that is not a bare host name.
 *
 * The domain is interpolated into URLs in several unrelated places — the redirect URI, the OIDC
 * discovery document, the JWKS endpoint — and compared verbatim against the `iss` claim of the
 * issued ID token. A value carrying a scheme or a trailing slash therefore does not fail where it
 * was supplied, but much later as an unresolvable endpoint or an ID token that will not verify.
 */
internal fun requireBareHost(domain: String) {
  require(BARE_HOST.matches(domain)) {
    "domain must be a bare host name such as \"samples.idura.broker\", not \"$domain\" — " +
      "no scheme, user info, port, path or trailing slash"
  }

  require(domain == domain.lowercase()) {
    "domain must be lower case, not \"$domain\" — it is compared verbatim against the issuer " +
      "of the ID token, which is lower case"
  }
}
