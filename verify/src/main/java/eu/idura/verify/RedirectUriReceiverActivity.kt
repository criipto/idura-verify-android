package eu.idura.verify

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import net.openid.appauth.AuthorizationManagementActivity

// This is heavily inspired by the `net.openid.appauth.RedirectUriReceiverActivity` class, but only starts the
// `AuthorizationManagementActivity` if this is _not_ a DK MitID app switch.
class RedirectUriReceiverActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceBundle: Bundle?) {
    super.onCreate(savedInstanceBundle)

    val isAppswitch =
      intent.data!!
        .queryParameterNames
        .contains(APPSWITCH_QUERY_PARAM)

    if (!isAppswitch) {
      // this is taken from net.openid.appauth.AuthorizationManagementActivity.RedirectUriReceiverActivity
      startActivity(
        AuthorizationManagementActivity.createResponseHandlingIntent(
          this,
          intent.data,
        ),
      )
    }
    finish()
  }
}
