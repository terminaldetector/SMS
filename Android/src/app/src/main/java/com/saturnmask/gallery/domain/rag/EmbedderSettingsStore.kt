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

package com.saturnmask.gallery.domain.rag

import android.content.Context
import com.saturnmask.gallery.data.Accelerator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "embedder_settings"
private const val KEY_SELECTED_SOURCE = "selected_source"
private const val KEY_CUSTOM_MODEL_PATH = "custom_model_path"
private const val KEY_CUSTOM_TOKENIZER_PATH = "custom_tokenizer_path"
private const val KEY_CUSTOM_DIMENSION = "custom_dimension"
private const val KEY_ACCELERATOR = "accelerator"
private const val DEFAULT_CUSTOM_DIMENSION = 768

/** Which [TextEmbedder] backs RAG search: the built-in EmbeddingGemma download, or a user-supplied
 *  third-party `.tflite` + `tokenizer.json` pair. See [FallbackTextEmbedder.refreshActiveEmbedder]. */
enum class EmbedderSource {
  BUILT_IN,
  CUSTOM,
}

/**
 * Stores which embedder source is active plus the on-disk paths of a user-imported custom
 * embedder, mirroring [com.saturnmask.gallery.domain.websearch.WebSearchSettingsStore]'s shape.
 * Plain (not encrypted) [android.content.SharedPreferences] — unlike an API key, a local file path
 * isn't a secret.
 */
@Singleton
class EmbedderSettingsStore @Inject constructor(@ApplicationContext context: Context) {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun getSelectedSource(): EmbedderSource =
    runCatching {
        EmbedderSource.valueOf(prefs.getString(KEY_SELECTED_SOURCE, EmbedderSource.BUILT_IN.name)!!)
      }
      .getOrDefault(EmbedderSource.BUILT_IN)

  fun setSelectedSource(source: EmbedderSource) {
    prefs.edit().putString(KEY_SELECTED_SOURCE, source.name).apply()
  }

  fun getCustomModelPath(): String? = prefs.getString(KEY_CUSTOM_MODEL_PATH, null)

  fun getCustomTokenizerPath(): String? = prefs.getString(KEY_CUSTOM_TOKENIZER_PATH, null)

  fun getCustomDimension(): Int = prefs.getInt(KEY_CUSTOM_DIMENSION, DEFAULT_CUSTOM_DIMENSION)

  // GPU is the only alternative offered — there's no NPU delegate in the Play Services TFLite GPU
  // API this embedder uses, so CPU/GPU are the only two real choices.
  fun getAccelerator(): Accelerator =
    runCatching { Accelerator.valueOf(prefs.getString(KEY_ACCELERATOR, Accelerator.CPU.name)!!) }
      .getOrDefault(Accelerator.CPU)

  fun setAccelerator(accelerator: Accelerator) {
    prefs.edit().putString(KEY_ACCELERATOR, accelerator.name).apply()
  }

  fun setCustomEmbedderFiles(modelPath: String, tokenizerPath: String, dimension: Int) {
    prefs
      .edit()
      .putString(KEY_CUSTOM_MODEL_PATH, modelPath)
      .putString(KEY_CUSTOM_TOKENIZER_PATH, tokenizerPath)
      .putInt(KEY_CUSTOM_DIMENSION, dimension)
      .apply()
  }

  fun clearCustomEmbedder() {
    prefs
      .edit()
      .remove(KEY_CUSTOM_MODEL_PATH)
      .remove(KEY_CUSTOM_TOKENIZER_PATH)
      .remove(KEY_CUSTOM_DIMENSION)
      .putString(KEY_SELECTED_SOURCE, EmbedderSource.BUILT_IN.name)
      .apply()
  }
}
