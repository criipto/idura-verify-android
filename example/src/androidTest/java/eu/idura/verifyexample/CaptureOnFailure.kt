package eu.idura.verifyexample

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

class CaptureOnFailure : TestWatcher() {
  override fun failed(
    e: Throwable,
    description: Description,
  ) {
    try {
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      val device = UiDevice.getInstance(instrumentation)
      // AGP injects additionalTestOutputDir for connected tests and auto-pulls
      // whatever the test writes there into build/outputs/connected_android_test_additional_output/.
      // /sdcard/Android/data/<pkg>/ would be scoped and unreliable to adb-pull.
      val outputDir =
        InstrumentationRegistry
          .getArguments()
          .getString("additionalTestOutputDir")
          ?.let(::File)
          ?: instrumentation.targetContext.getExternalFilesDir(null)!!.resolve("test-failures")
      outputDir.mkdirs()
      val png = outputDir.resolve("${description.methodName}.png")
      val xml = outputDir.resolve("${description.methodName}.xml")
      val pngOk = device.takeScreenshot(png)
      xml.outputStream().use { device.dumpWindowHierarchy(it) }
      Log.i("CaptureOnFailure", "wrote $png (screenshot ok=$pngOk) and $xml")
    } catch (t: Throwable) {
      Log.e("CaptureOnFailure", "capture failed", t)
    }
  }
}
