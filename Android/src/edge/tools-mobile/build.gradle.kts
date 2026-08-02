plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.saturnmask.edge.tools.mobile"
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
  buildFeatures { compose = true }
}

dependencies {
  api(libs.litertlm)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.material3)
  implementation(libs.material.icon.extended)
}
