package eu.idura.verify

import android.net.Uri

/**
 * Idura Verify can be configured to return the OIDC response parameters in the URL fragment
 * (`https://domain/android/callback#code=...&state=...`) rather than the query string. Neither
 * AppAuth nor our own response handling looks at the fragment, so callbacks are rewritten into
 * the equivalent query-string form on arrival and everything downstream stays query-only.
 *
 * Fragments that hold no parameters — and callbacks that already use the query string — are
 * returned unchanged, which also makes this safe to apply more than once along a callback path.
 */
internal fun Uri.withFragmentParametersAsQuery(): Uri {
  val fragment = encodedFragment
  if (fragment.isNullOrEmpty() || !fragment.contains("=")) {
    return this
  }

  // Uri only exposes parameter parsing for the query component, so reinterpret the fragment as
  // one. Both are `key=value&key=value` with the same percent-encoding rules.
  val fragmentParameters = Uri.Builder().encodedQuery(fragment).build()

  val builder = buildUpon().encodedFragment(null)
  fragmentParameters.queryParameterNames.forEach { name ->
    fragmentParameters.getQueryParameters(name).forEach { value ->
      builder.appendQueryParameter(name, value)
    }
  }
  return builder.build()
}
