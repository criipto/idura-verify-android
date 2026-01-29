plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  kotlin("plugin.serialization") version "2.2.20"
}

android {
  buildFeatures {
    buildConfig = true
  }
  namespace = "eu.idura.verifyexample"
  compileSdk {
    version = release(36)
  }

  val iduraDomain = providers.gradleProperty("iduraDomain").get()
  val iduraClientId = providers.gradleProperty("iduraClientId").get()

  defaultConfig {
    applicationId = "eu.idura.verifyexample"
    minSdk = 29
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
      isMinifyEnabled = false
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

  // Use this dependency to run against your local version of the library, and run e2e tests
  implementation(project(":verify"))

  // Use this dependency to use the published version of the library
  // implementation(exampleLibs.verify)

  androidTestImplementation(exampleLibs.androidx.test.uiautomator)

  androidTestImplementation(platform(exampleLibs.androidx.compose.bom))
  androidTestImplementation(exampleLibs.androidx.compose.ui.test.junit4)
  debugImplementation(exampleLibs.androidx.compose.ui.tooling)
  debugImplementation(exampleLibs.androidx.compose.ui.test.manifest)
}
