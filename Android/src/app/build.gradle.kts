/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

android {
  namespace = "com.saturnmask.gallery"
  compileSdk { this.version = release(37) { minorApiLevel = 0 } }

  defaultConfig {
    applicationId = "com.saturnmask.gallery"
    minSdk = 31
    targetSdk = 37
    versionCode = 37
    versionName = "1.0.17"

    // Needed for HuggingFace auth workflows, and now also MCP's Google OAuth (see
    // common/GoogleOAuthConfig.kt) -- shared by both, since this placeholder name is baked into the
    // net.openid:appauth library's own manifest and isn't something a second, differently-named
    // placeholder would actually wire up. Use the scheme of the "Redirect URLs" in the HuggingFace
    // app; GoogleOAuthConfig.redirectUri must use this same scheme, just a different path.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    manifestPlaceholders["applicationName"] = "com.saturnmask.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.documentfile)
  implementation(libs.moshi.kotlin)
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  // --- roadmap patch additions ---
  implementation("com.tom-roush:pdfbox-android:2.0.27.0") // PDF text extraction (RAG parsers)
  // NOTE: originally also added com.google.ai.edge.litert:litert:2.1.0 here for the neural
  // embedder's model-loading API, but it conflicts with dependencies already in this project:
  //  - duplicate native lib/arm64-v8a/libLiteRt.so (litertlm-android already bundles a LiteRT
  //    runtime internally)
  //  - duplicate org.tensorflow.lite.* classes vs. the already-present libs.tflite artifacts
  // EmbeddingGemmaTextEmbedder.kt was rewritten instead to run against Play Services TFLite's
  // org.tensorflow.lite.InterpreterApi (via libs.tflite, already a dependency below), which avoids
  // both conflicts above. See that file's doc comment for what's proven vs. still unverified.
  // 0.33.0, not 0.31.1: ai.djl.android:tokenizer-native (below) only ever published one version,
  // 0.33.0 — pinned both to that so the Java API and its native binary are the same DJL release.
  implementation("ai.djl.huggingface:tokenizers:0.33.0")
  // ai.djl.huggingface:tokenizers alone only works on desktop/server JVMs (it resolves its native
  // library at runtime by OS string, e.g. "osx-x86_64" — meaningless on Android). This is the
  // Android-native companion artifact that actually makes HuggingFaceTokenizer usable here.
  implementation("ai.djl.android:tokenizer-native:0.33.0")
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
