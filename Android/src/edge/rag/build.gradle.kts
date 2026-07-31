plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.hilt.application)
  kotlin("kapt")
}

android {
  namespace = "com.saturnmask.edge.rag"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    minSdk = 31
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
}

dependencies {
  api(project(":edge:embedder"))
  implementation(libs.moshi.kotlin)
  implementation("com.tom-roush:pdfbox-android:2.0.27.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation(libs.hilt.android)
  kapt(libs.hilt.android.compiler)
}
