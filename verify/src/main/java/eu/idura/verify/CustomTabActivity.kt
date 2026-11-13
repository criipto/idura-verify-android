package eu.idura.verify

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import net.openid.appauth.AuthorizationException

/**
 * A Kotlin port of AppAuth's `AuthorizationManagementActivity` (0.11.1), so that the commits after
 * it can change it in steps. It keeps the back stack below the browser tab: it opens the tab when
 * it first resumes, and on its next resume returns the response URI the receiver handed it as the
 * result's data, or a cancel when there is none.
 */
internal class CustomTabActivity : AppCompatActivity() {
  private var authorizationStarted = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    authorizationStarted = savedInstanceState?.getBoolean(KEY_AUTHORIZATION_STARTED) ?: false
  }

  override fun onResume() {
    super.onResume()

    val authIntent =
      IntentCompat.getParcelableExtra(intent, KEY_AUTH_INTENT, Intent::class.java)
    if (!authorizationStarted && authIntent != null) {
      try {
        startActivity(authIntent)
        authorizationStarted = true
      } catch (ex: ActivityNotFoundException) {
        Log.w(TAG, "No browser to open the Custom Tab in", ex)
        setResult(
          RESULT_CANCELED,
          AuthorizationException
            .fromTemplate(AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW, ex)
            .toIntent(),
        )
        finish()
      }
      return
    }

    val uri = intent.data
    if (uri != null) {
      setResult(RESULT_OK, Intent().setData(uri))
    } else {
      setResult(RESULT_CANCELED)
    }
    finish()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(KEY_AUTHORIZATION_STARTED, authorizationStarted)
  }

  companion object {
    private const val KEY_AUTH_INTENT = "authIntent"
    private const val KEY_AUTHORIZATION_STARTED = "authStarted"

    fun createIntent(
      context: Context,
      authIntent: Intent,
    ): Intent = Intent(context, CustomTabActivity::class.java).putExtra(KEY_AUTH_INTENT, authIntent)

    fun createResponseHandlingIntent(
      context: Context,
      responseUri: Uri?,
    ): Intent =
      Intent(context, CustomTabActivity::class.java)
        .setData(responseUri)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
  }
}
