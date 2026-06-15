package eu.idura.verifyexample

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Drives a kernel-level UHID virtual touchscreen so taps look like real hardware
 * input rather than injected events.
 *
 * BankID's PIN keypad ignores injected taps (UiAutomator `click()` / `adb shell
 * input tap`): the digit registers but the app detects the injection and fails the
 * flow. A UHID device re-enters through the kernel HID layer, so its touches are
 * indistinguishable from a finger. The native injector (`src/androidTest/cpp/
 * uhid_touch.c`) is compiled and pushed to `/data/local/tmp/uhid_touch` by the
 * `:example:deployUhidTouch` Gradle task, which the connected-test tasks depend on.
 *
 * It runs as the `shell` user (the only user that may open `/dev/uhid`) via
 * [android.app.UiAutomation.executeShellCommandRw], and we feed it tap commands over
 * its stdin. Use it for the keypad only; ordinary navigation taps don't need it.
 */
class UhidTouch private constructor(
  private val stdin: ParcelFileDescriptor.AutoCloseOutputStream,
  private val stdout: ParcelFileDescriptor.AutoCloseInputStream,
) : AutoCloseable {
  /** Taps the screen pixel (x, y) as a hardware touch. */
  fun tap(
    x: Int,
    y: Int,
  ) {
    stdin.write("$x $y\n".toByteArray())
    stdin.flush()
  }

  /** Closing stdin sends EOF, which makes the injector destroy the virtual device. */
  override fun close() {
    runCatching { stdin.close() }
    runCatching { stdout.close() }
  }

  companion object {
    /**
     * Starts the injector for a screen of [width] x [height] pixels and blocks until
     * the virtual touchscreen is live and ready to receive taps.
     */
    fun start(
      width: Int,
      height: Int,
    ): UhidTouch {
      val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
      val fds = automation.executeShellCommandRw("/data/local/tmp/uhid_touch $width $height")
      val stdout = ParcelFileDescriptor.AutoCloseInputStream(fds[0])
      val stdin = ParcelFileDescriptor.AutoCloseOutputStream(fds[1])
      // The injector prints "ready <w>x<h>" once the touchscreen is enumerated.
      val handshake = BufferedReader(InputStreamReader(stdout)).readLine()
      check(handshake != null && handshake.startsWith("ready")) {
        "uhid_touch did not start (is :example:deployUhidTouch on the path? got: $handshake)"
      }
      return UhidTouch(stdin, stdout)
    }
  }
}
