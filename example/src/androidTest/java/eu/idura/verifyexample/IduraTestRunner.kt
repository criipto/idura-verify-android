package eu.idura.verifyexample

import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.uiautomator.UiDevice
import java.io.File

/**
 * Instrumentation runner that stops Chrome from showing its one-time first run during the suite.
 * Until the first run is done, Chrome shows a full-screen prompt in place of any tab it is asked to
 * open — including the Custom/Auth Tab a login test drives — so the prompt swallows the OAuth
 * redirect and the test times out waiting for a logged-in screen it will never see.
 *
 * Rather than click through the prompts, tell Chrome not to raise them: `--disable-fre`
 * short-circuits the first-run sequencer, terms-of-service gate included. Chrome only reads flags
 * from /data/local/tmp when it is the debug app and adb is enabled, which is what `am set-debug-app
 * --persistent` arranges, and the file's first token is discarded as argv[0]. See
 * CommandLineInitUtil in Chromium's base/android.
 *
 * Best effort: a failure here is logged rather than thrown, because throwing out of [onStart] kills
 * the instrumentation before a single test runs. The login tests surface the fallout on their own
 * terms.
 */
class IduraTestRunner : AndroidJUnitRunner() {
  override fun onStart() {
    runCatching { suppressChromeFirstRun() }.onFailure {
      Log.w(TAG, "Could not prepare Chrome; logins may fail", it)
    }
    super.onStart()
  }

  private fun suppressChromeFirstRun() {
    val device = UiDevice.getInstance(this)
    val staged = File(targetContext.getExternalFilesDir(null), COMMAND_LINE_FILE_NAME)
    staged.writeText("_ --disable-fre --no-first-run --no-default-browser-check")
    device.executeShellCommand("cp ${staged.absolutePath} $COMMAND_LINE_FILE")
    staged.delete()

    device.executeShellCommand("chmod 0644 $COMMAND_LINE_FILE")
    device.executeShellCommand("am set-debug-app --persistent $CHROME_PACKAGE")
    // Chrome parses the file once per process, so a Chrome left running by an earlier test
    // or by the emulator image would otherwise keep the flags it started with.
    device.executeShellCommand("am force-stop $CHROME_PACKAGE")
  }

  private companion object {
    const val TAG = "IduraTestRunner"
    const val CHROME_PACKAGE = "com.android.chrome"
    const val COMMAND_LINE_FILE_NAME = "chrome-command-line"
    const val COMMAND_LINE_FILE = "/data/local/tmp/$COMMAND_LINE_FILE_NAME"
  }
}
