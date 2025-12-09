plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.maven.publish)
  kotlin("plugin.serialization") version "2.2.20"
}

var version = providers.gradleProperty("version").get()
if (providers.gradleProperty("snapshot").getOrNull() != null) {
  version += "-SNAPSHOT"
}

setVersion(version)

android {
  buildFeatures {
    buildConfig = true
  }

  namespace = "eu.idura.verify"
  compileSdk {
    version = release(36)
  }

  defaultConfig {
    minSdk = 29

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

mavenPublishing {
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
  implementation(libraryLibs.jwks.rsa)
  implementation(libraryLibs.java.jwt)
  implementation(libraryLibs.ktor.client.core)
  implementation(libraryLibs.ktor.client.android)
  implementation(libraryLibs.ktor.client.content.negotiation)
  implementation(libraryLibs.ktor.serialization.kotlinx.json)
  implementation(platform(libraryLibs.opentelemetry.bom))
  implementation(libraryLibs.opentelemetry.api)
  implementation(libraryLibs.opentelemetry.sdk)
  implementation(libraryLibs.opentelemetry.extension.kotlin)
  implementation(libraryLibs.java.uuid.generator)

  testImplementation(libraryLibs.kotlinx.coroutines.test)
}
