plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  kotlin("plugin.serialization") version "2.1.21"
}

// AGP only generates connectedAndroidTest tasks for a single build type. Default to
// debug so Android Studio's "Run test" hits the fast path; CI overrides to release
// via -PtestBuildType=release to exercise the build the SDK actually ships against.
val instrumentedTestBuildType =
  providers.gradleProperty("testBuildType").getOrElse("debug")

android {
  testBuildType = instrumentedTestBuildType

  signingConfigs {
    getByName("debug") {
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  flavorDimensions += "tabType"

  productFlavors {
    create("automaticTabSelection") {
      dimension = "tabType"
      buildConfigField("String", "TAB_TYPE", "\"AUTO\"")
    }
    create("customTab") {
      dimension = "tabType"
      buildConfigField("String", "TAB_TYPE", "\"CUSTOM_TAB\"")
    }
    create("authTab") {
      dimension = "tabType"
      buildConfigField("String", "TAB_TYPE", "\"AUTH_TAB\"")
    }
  }

  buildFeatures {
    buildConfig = true
  }
  namespace = "eu.idura.verifyexample"
  compileSdk = 36

  val iduraDomain = providers.gradleProperty("iduraDomain").get()
  val iduraClientId = providers.gradleProperty("iduraClientId").get()

  defaultConfig {
    applicationId = "eu.idura.verifyexample"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    manifestPlaceholders["iduraDomain"] = iduraDomain
  }

  buildTypes {
    all {
      buildConfigField("String", "IDURA_DOMAIN", "\"$iduraDomain\"")
      buildConfigField("String", "IDURA_CLIENT_ID", "\"$iduraClientId\"")
    }
    release {
      // Minify the example app so CI exercises the same R8 path real consumers hit,
      // catching missing consumer-rules.pro entries before they ship.
      isMinifyEnabled = true
      // The example app is never published — sign release with the checked-in
      // debug keystore (see signingConfigs above) so CI and local builds share
      // a signing-cert SHA that matches the binding on the Idura backend's
      // assetlinks.json.
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
  }
  buildFeatures {
    compose = true
  }
}

dependencies {
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)

  implementation(exampleLibs.androidx.core.ktx)
  implementation(exampleLibs.androidx.lifecycle.runtime.ktx)
  implementation(exampleLibs.androidx.activity.compose)
  implementation(platform(exampleLibs.androidx.compose.bom))
  implementation(exampleLibs.androidx.compose.ui)
  implementation(exampleLibs.androidx.compose.ui.graphics)
  implementation(exampleLibs.androidx.compose.ui.tooling.preview)
  implementation(exampleLibs.androidx.compose.material3)

  // Replace this dependency to test against the published version of the library
  implementation(project(":verify"))
  // implementation(exampleLibs.verify)

  androidTestImplementation(platform(exampleLibs.androidx.compose.bom))
  androidTestImplementation(exampleLibs.androidx.compose.ui.test.junit4)
  debugImplementation(exampleLibs.androidx.compose.ui.tooling)
  debugImplementation(exampleLibs.androidx.compose.ui.test.manifest)
}

// The SE BankID PIN keypad ignores injected taps (UiAutomator / `adb shell input
// tap`), so LoginTest#runSEBankID drives it through a kernel-level UHID virtual
// touchscreen. This task compiles that tiny native injector with the NDK and pushes
// it to the device. Requires NDK 27.1.12297006 installed and a single connected
// device (local-only — runSEBankID can't run on the CI emulator since BankID won't
// run emulated). The connected-test tasks depend on it only when the NDK is present
// (see below), so the NDK-less CI emulator job isn't blocked by it.
val uhidNdkDir = File(android.sdkDirectory, "ndk/27.1.12297006")
val deployUhidTouch by tasks.registering {
  group = "verification"
  description = "Compiles and pushes the UHID touch injector used by runSEBankID."
  val src = file("src/androidTest/cpp/uhid_touch.c")
  val out = layout.buildDirectory.file("uhid/uhid_touch")
  val sdkDir = android.sdkDirectory
  inputs.file(src)
  outputs.file(out)
  doLast {
    require(
      uhidNdkDir.isDirectory,
    ) { "NDK 27.1.12297006 not found at $uhidNdkDir — install it via the SDK Manager." }
    val hostDir =
      File(uhidNdkDir, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
        ?: error("NDK prebuilt toolchain not found under $uhidNdkDir")
    val clang = File(hostDir, "bin/aarch64-linux-android33-clang")
    val adb = File(sdkDir, "platform-tools/adb")
    val binary = out.get().asFile
    binary.parentFile.mkdirs()
    project.exec { commandLine(clang, "-O2", "-Wall", "-pie", "-o", binary, src) }
    project.exec { commandLine(adb, "push", binary, "/data/local/tmp/uhid_touch") }
    project.exec { commandLine(adb, "shell", "chmod", "755", "/data/local/tmp/uhid_touch") }
  }
}

// Only runSEBankID uses the injector, and building it needs the NDK, so wire it into
// the connected-test tasks only when the NDK is installed (i.e. local dev machines
// that can run runSEBankID). The CI emulator job runs just runMockLogin and has no
// NDK, so it skips the injector rather than failing to build it.
tasks.configureEach {
  if (uhidNdkDir.isDirectory && name.startsWith("connected") && name.endsWith("AndroidTest")) {
    dependsOn(deployUhidTouch)
  }
}

tasks.register("runAllBrowserTests") {
  group = "verification"
  description = "Runs UIAutomator tests for both Custom Tab and Auth Tab paths."

  val suffix = instrumentedTestBuildType.replaceFirstChar { it.uppercase() }
  // Run Custom Tabs flavor tests
  dependsOn("connectedCustomTab${suffix}AndroidTest")
  // Run Auth Tab flavor tests
  dependsOn("connectedAuthTab${suffix}AndroidTest")
}
