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

package com.saturnmask.gallery.data

import android.os.Build

// Keys used to send/receive data to Work.
const val KEY_MODEL_URL = "KEY_MODEL_URL"
const val KEY_MODEL_NAME = "KEY_MODEL_NAME"
const val KEY_MODEL_COMMIT_HASH = "KEY_MODEL_COMMIT_HASH"
const val KEY_MODEL_DOWNLOAD_MODEL_DIR = "KEY_MODEL_DOWNLOAD_MODEL_DIR"
const val KEY_MODEL_DOWNLOAD_FILE_NAME = "KEY_MODEL_DOWNLOAD_FILE_NAME"
const val KEY_MODEL_TOTAL_BYTES = "KEY_MODEL_TOTAL_BYTES"
const val KEY_MODEL_DOWNLOAD_RECEIVED_BYTES = "KEY_MODEL_DOWNLOAD_RECEIVED_BYTES"
const val KEY_MODEL_DOWNLOAD_RATE = "KEY_MODEL_DOWNLOAD_RATE"
const val KEY_MODEL_DOWNLOAD_REMAINING_MS = "KEY_MODEL_DOWNLOAD_REMAINING_SECONDS"
const val KEY_MODEL_DOWNLOAD_ERROR_MESSAGE = "KEY_MODEL_DOWNLOAD_ERROR_MESSAGE"
const val KEY_MODEL_DOWNLOAD_ACCESS_TOKEN = "KEY_MODEL_DOWNLOAD_ACCESS_TOKEN"
const val KEY_MODEL_EXTRA_DATA_URLS = "KEY_MODEL_EXTRA_DATA_URLS"
const val KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES = "KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES"
const val KEY_MODEL_IS_ZIP = "KEY_MODEL_IS_ZIP"
const val KEY_MODEL_UNZIPPED_DIR = "KEY_MODEL_UNZIPPED_DIR"
const val KEY_MODEL_START_UNZIPPING = "KEY_MODEL_START_UNZIPPING"
const val KEY_MODEL_IS_IMPORTED = "KEY_MODEL_IS_IMPORTED"

// Default values for LLM models.
// 4096, not 1024: this is the fallback used when nothing else specifies a value — mainly the
// manual-import dialog's starting slider position. Genuinely small/weak models (Gemma3-1B,
// Qwen2.5-1.5B) declare their own 1024 explicitly in the allowlist, so they're unaffected. A
// freshly-imported larger model (e.g. Gemma 4, which supports up to ~32k) left at this default
// has no headroom for Agent Chat's system prompt (skills + MCP tools + mode-gating text) on top
// of it — observed failing with "Input token ids are too long... 1088 >= 1024" on a Gemma-4-E4B
// import that had never touched the max-tokens slider. 4096 matches the slider's own ceiling in
// ModelImportDialog.kt, so it's not introducing a new ad-hoc number.
const val DEFAULT_MAX_TOKEN = 4096
const val DEFAULT_TOPK = 64
const val DEFAULT_TOPP = 0.95f
const val DEFAULT_TEMPERATURE = 1.0f
const val DEFAULT_MAX_OUTPUT_TOKEN = 1024
val DEFAULT_ACCELERATORS = listOf(Accelerator.GPU)
val DEFAULT_VISION_ACCELERATOR = Accelerator.GPU

// LiteRT LM Engine constants.
const val THOUGHT_CHANNEL = "thought"

// Max number of images allowed in a "ask image" session.
const val MAX_IMAGE_COUNT = 10

// Max number of images allowed in a "ask image" session for AI Core.
const val MAX_IMAGE_COUNT_AI_CORE = 1

// Max number of skills recommended in a "agent skills" session.
const val MAX_RECOMMENDED_SKILL_COUNT = 15

// Max number of audio clip in an "ask audio" session.
const val MAX_AUDIO_CLIP_COUNT = 1

// Max audio clip duration in seconds.
const val MAX_AUDIO_CLIP_DURATION_SEC = 30

// Audio-recording related consts.
const val SAMPLE_RATE = 16000

// The extension of the tmp download files.
const val TMP_FILE_EXT = "gallerytmp"

// Current device's SOC in lowercase.
val SOC =
  (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      Build.SOC_MODEL ?: ""
    } else {
      ""
    })
    .lowercase()

// URLs for Agent Skills.
object AgentSkillsURLs {
  const val REPOSITORY = "https://github.com/google-ai-edge/gallery/tree/main/skills"
  const val DISCUSSIONS = "https://github.com/google-ai-edge/gallery/discussions/categories/skills"
}
