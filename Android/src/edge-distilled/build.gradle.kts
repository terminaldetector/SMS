plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "com.saturnmask.edge.distilled"
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

/**
 * Aggregating façade matching the edge-distilled/ tree from the assimilation prompt.
 * Chatter Triangle should depend on this single module (or pick leaf :edge:* modules).
 */
dependencies {
  api(project(":edge:core"))
  api(project(":edge:llm"))
  api(project(":edge:embedder"))
  api(project(":edge:rag"))
  api(project(":edge:websearch"))
  api(project(":edge:mcp"))
  api(project(":edge:tools-mobile"))
  api(project(":edge:tools-coder"))
  api(project(":edge:tools"))
  api(project(":edge:settings"))
}
