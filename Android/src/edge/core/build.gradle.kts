plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.hilt.application)
  kotlin("kapt")
}

android {
  namespace = "com.saturnmask.edge.core"
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
  api(libs.androidx.core.ktx)
  api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  api(libs.com.google.code.gson)
  implementation(libs.hilt.android)
  kapt(libs.hilt.android.compiler)
}
