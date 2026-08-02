plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

android {
  namespace = "com.saturnmask.edge.websearch"
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
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.security.crypto)
  implementation(libs.moshi.kotlin)
  ksp(libs.moshi.kotlin.codegen)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation(libs.hilt.android)
  kapt(libs.hilt.android.compiler)
}
