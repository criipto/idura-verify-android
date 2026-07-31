import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.maven.publish)
  kotlin("plugin.serialization") version "2.1.21"
}

var version = providers.gradleProperty("version").get()
if (providers.gradleProperty("snapshot").getOrNull() != null) {
  version += "-SNAPSHOT"
}

setVersion(version)

android {
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

  namespace = "eu.idura.verify"
  compileSdk = 36

  defaultConfig {
    minSdk = 26

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
    buildConfigField("String", "VERSION", "\"$version\"")
  }

  buildTypes {
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
}

// The redirect URI intent filter leaves the host as a `${iduraDomain}` placeholder for consumers
// to substitute, which is what has to reach the published AAR. The androidTest APK merges that
// same manifest though, and the merger refuses to leave a placeholder unresolved, so the library's
// instrumented tests could not be built at all. Substitute a value for the test APK only — setting
// it in `defaultConfig` would bake this dead host into the AAR and break consumers' app links.
androidComponents {
  onVariants { variant ->
    variant.androidTest?.manifestPlaceholders?.put("iduraDomain", "verify-android-tests.invalid")
  }
}

mavenPublishing {
  configure(AndroidSingleVariantLibrary("automaticTabSelectionRelease"))
  publishToMavenCentral()

  signAllPublications()

  coordinates("eu.idura", "verify", version)

  pom {
    name = "Idura Verify"
    description = "An SDK which allows you to integrate Idura Verify in your Android app."
    inceptionYear = "2025"
    url = "https://github.com/criipto/criipto-verify-android"
    licenses {
      license {
        name = "MIT"
        url = "https://mit-license.org/"
        distribution = "https://mit-license.org/"
      }
    }
    developers {
      developer {
        id = "janmeier"
        email = "jan.meier@idura.eu"
        name = "Jan Aagaard Meier"
        url = "https://github.com/janmeier"
        organization = "Idura"
        organizationUrl = "https://idura.eu"
      }
    }
    scm {
      url = "https://github.com/criipto/criipto-verify-android"
      connection = "scm:git:git://github.com/criipto/criipto-verify-android.git"
      developerConnection = "scm:git:ssh://git@github.com/criipto/criipto-verify-android.git"
    }
  }
}

dependencies {
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)

  implementation(libraryLibs.androidx.browser)
  implementation(libraryLibs.androidx.appcompat)
  implementation(libraryLibs.appauth)
  implementation(libraryLibs.java.jwt)
  implementation(libraryLibs.nimbus.jose.jwt)
  implementation(libraryLibs.ktor.client.core)
  implementation(libraryLibs.ktor.client.android)
  implementation(libraryLibs.ktor.client.content.negotiation)
  implementation(libraryLibs.ktor.serialization.kotlinx.json)
  implementation(platform(libraryLibs.opentelemetry.bom))
  implementation(libraryLibs.opentelemetry.api)
  implementation(libraryLibs.opentelemetry.sdk)
  implementation(libraryLibs.opentelemetry.exporter.otlp)

  implementation(libraryLibs.java.uuid.generator)

  testImplementation(libraryLibs.kotlinx.coroutines.test)
}
