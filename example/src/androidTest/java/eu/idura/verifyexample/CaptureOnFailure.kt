package eu.idura.verifyexample

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class CaptureOnFailure : TestWatcher() {
  override fun failed(
    e: Throwable,
    description: Description,
  ) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val dir =
      instrumentation.targetContext
        .getExternalFilesDir(null)!!
        .resolve("test-failures")
        .apply { mkdirs() }
    device.takeScreenshot(dir.resolve("${description.methodName}.png"))
    dir.resolve("${description.methodName}.xml").outputStream().use { out ->
      device.dumpWindowHierarchy(out)
    }
  }
}
