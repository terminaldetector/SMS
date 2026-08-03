/*
 * Copyright 2026 Google LLC
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

package com.saturnmask.gallery.runtime

import com.saturnmask.gallery.data.Model
import com.saturnmask.gallery.data.RuntimeType
import com.saturnmask.gallery.ui.llmchat.LlmChatModelHelper

var testingModelHelper: LlmModelHelper? = null

val Model.runtimeHelper: LlmModelHelper
  get() {
    testingModelHelper?.let {
      return it
    }
    // AICore is not carried into TriangleUI. Two reasons, and the first alone settles it:
    // ML Kit's GenAI APIs are package-allowlisted by Google, so AICoreModelHelper could never run
    // in this app whatever it was asked to do. The second is that its transitive tree
    // (com.google.mlkit:genai-common) declares minSdk 26 against this app's 24, which failed the
    // manifest merger — a real build cost for a path with no reachable function.
    //
    // A model still declaring RuntimeType.AICORE therefore falls through to LiteRT-LM rather than
    // being routed at a helper that is not here.
    if (this.runtimeType == RuntimeType.AICORE) {
      android.util.Log.w(
        "ModelHelperExt",
        "model '" + this.name + "' asks for AICORE, which is not built into this app — using LiteRT-LM",
      )
    }
    return LlmChatModelHelper
  }
