package eu.idura.verify

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import net.openid.appauth.AuthorizationManagementActivity

// This is heavily inspired by the `net.openid.appauth.RedirectUriReceiverActivity` class, but only starts the
// `AuthorizationManagementActivity` if this is _not_ a DK MitID app switch.
class RedirectUriReceiverActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceBundle: Bundle?) {
    super.onCreate(savedInstanceBundle)

    // AppAuth reads the response — including the state it validates the request against — from
    // the query string only, so a fragment-mode callback has to be rewritten before it is handed
    // over, or the flow fails as a state mismatch.
    val callbackUri = intent.data!!.withFragmentParametersAsQuery()

    val isAppswitch =
      callbackUri
        .queryParameterNames
        .contains(APPSWITCH_QUERY_PARAM)

    if (!isAppswitch) {
      // this is taken from net.openid.appauth.AuthorizationManagementActivity.RedirectUriReceiverActivity
      startActivity(
        AuthorizationManagementActivity.createResponseHandlingIntent(
          this,
          callbackUri,
        ),
      )
    }
    finish()
  }
}
