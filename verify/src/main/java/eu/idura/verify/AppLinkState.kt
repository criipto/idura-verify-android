package eu.idura.verify

import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.os.Build

internal data class AppLinkState(
  val hostState: String,
  val linkHandlingAllowed: Boolean,
) {
  val opensInApp get() = linkHandlingAllowed && hostState != "none"
}

internal fun appLinkState(
  context: Context,
  host: String,
): AppLinkState? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null

  val userState =
    runCatching {
      context
        .getSystemService(DomainVerificationManager::class.java)
        ?.getDomainVerificationUserState(context.packageName)
    }.getOrNull() ?: return null

  return AppLinkState(
    hostState =
      when (userState.hostToStateMap[host]) {
        DomainVerificationUserState.DOMAIN_STATE_VERIFIED -> "verified"
        DomainVerificationUserState.DOMAIN_STATE_SELECTED -> "selected"
        DomainVerificationUserState.DOMAIN_STATE_NONE -> "none"
        else -> "undeclared"
      },
    linkHandlingAllowed = userState.isLinkHandlingAllowed,
  )
}
