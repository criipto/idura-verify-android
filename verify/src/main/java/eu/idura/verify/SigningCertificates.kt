package eu.idura.verify

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

internal fun signingCertificateFingerprints(context: Context): List<String> =
  runCatching { signingCertificates(context).map { sha256Fingerprint(it.toByteArray()) } }
    .getOrDefault(emptyList())

@SuppressLint("PackageManagerGetSignatures")
@Suppress("DEPRECATION")
private fun signingCertificates(context: Context): List<Signature> {
  val packageManager = context.packageManager
  val packageName = context.packageName
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    packageManager
      .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
      .signingInfo
      ?.apkContentsSigners
      ?.toList()
      .orEmpty()
  } else {
    packageManager
      .getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
      .signatures
      ?.toList()
      .orEmpty()
  }
}

internal fun sha256Fingerprint(certificate: ByteArray): String =
  MessageDigest
    .getInstance("SHA-256")
    .digest(certificate)
    .joinToString(":") { "%02X".format(it) }
