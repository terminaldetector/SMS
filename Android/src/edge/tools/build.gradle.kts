plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "com.saturnmask.edge.tools"
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
  api(project(":edge:core"))
  api(project(":edge:rag"))
  api(project(":edge:websearch"))
  api(libs.litertlm)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
