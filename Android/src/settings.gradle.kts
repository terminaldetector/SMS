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

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id == "com.google.android.gms.oss-licenses-plugin") {
        useModule("com.google.android.gms:oss-licenses-plugin:0.11.0")
      }
    }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    //        mavenLocal()
    google()
    mavenCentral()
  }
}

rootProject.name = "AI Edge Gallery"

include(":app")

// Distilled GEDGE modules for Chatter Triangle / host apps
include(":edge:core")
include(":edge:llm")
include(":edge:embedder")
include(":edge:rag")
include(":edge:websearch")
include(":edge:mcp")
include(":edge:tools-mobile")
include(":edge:tools-coder")

project(":edge:core").projectDir = file("edge/core")
project(":edge:llm").projectDir = file("edge/llm")
project(":edge:embedder").projectDir = file("edge/embedder")
project(":edge:rag").projectDir = file("edge/rag")
project(":edge:websearch").projectDir = file("edge/websearch")
project(":edge:mcp").projectDir = file("edge/mcp")
project(":edge:tools-mobile").projectDir = file("edge/tools-mobile")
project(":edge:tools-coder").projectDir = file("edge/tools-coder")
