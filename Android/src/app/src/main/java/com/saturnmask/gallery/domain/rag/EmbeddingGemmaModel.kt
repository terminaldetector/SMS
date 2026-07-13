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

import com.saturnmask.gallery.data.Model
import com.saturnmask.gallery.data.ModelDataFile

/**
 * Lookup key for [Model.getExtraDataFile] on [EMBEDDING_GEMMA_MODEL] — shared so the download
 * trigger ([com.saturnmask.gallery.customtasks.agentchat.RagManagerViewModel]) and the file-path
 * providers ([RagEmbeddingFilesModule]) can't drift apart on the string literal.
 */
const val EMBEDDING_GEMMA_TOKENIZER_DATA_FILE_NAME = "tokenizer"

// Rough estimates, NOT verified against the real Content-Length — mixed-precision quantized 300M
// weights are typically in the 100-200MB range, and a large multilingual tokenizer.json is
// typically single-digit-to-low-double-digit MB. Only affects the download progress bar's
// percentage math (cosmetic); not a functional blocker. Correct these once a real download has
// actually reported the true sizes.
private const val WEIGHTS_SIZE_BYTES_ESTIMATE = 200_000_000L
private const val TOKENIZER_SIZE_BYTES_ESTIMATE = 17_000_000L

/**
 * Single source of truth for the EmbeddingGemma download: both [RagEmbeddingFilesModule] (the
 * paths the built-in [TfLiteTextEmbedder] instance reads from) and `RagManagerViewModel` (the
 * download trigger) reference this exact instance, so "where we tell WorkManager to write" and
 * "where we tell the embedder to read" can't drift apart.
 *
 * Two separate, both Gemma-license-gated HuggingFace repos: the LiteRT-converted weights come
 * from `litert-community/embeddinggemma-300m` (mirrors the filename [RagEngineModule.kt] already
 * expected), the tokenizer comes from the base `google/embeddinggemma-300m` repo instead of the
 * LiteRT repo — the LiteRT repo only ships a raw `sentencepiece.model`, which the
 * `ai.djl.huggingface.tokenizers.HuggingFaceTokenizer` used by [TfLiteTextEmbedder] can't load
 * directly (it wants the HuggingFace `tokenizer.json` format, which the base repo has).
 * [Model.extraDataFiles] entries carry their own independent URL, so one [Model] with one extra
 * file covers both repos — no need for two separate downloads.
 */
val EMBEDDING_GEMMA_MODEL: Model by lazy {
  Model(
      name = "EmbeddingGemma-300M",
      url =
        "https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main/" +
          "embeddinggemma-300M_seq256_mixed-precision.tflite?download=true",
      sizeInBytes = WEIGHTS_SIZE_BYTES_ESTIMATE,
      downloadFileName = "embeddinggemma-300M_seq256_mixed-precision.tflite",
      version = "1",
      extraDataFiles =
        listOf(
          ModelDataFile(
            name = EMBEDDING_GEMMA_TOKENIZER_DATA_FILE_NAME,
            url =
              "https://huggingface.co/google/embeddinggemma-300m/resolve/main/tokenizer.json" +
                "?download=true",
            downloadFileName = "tokenizer.json",
            sizeInBytes = TOKENIZER_SIZE_BYTES_ESTIMATE,
          )
        ),
    )
    .also { it.preProcess() } // sets totalBytes = sizeInBytes + extraDataFiles size, for progress
}
