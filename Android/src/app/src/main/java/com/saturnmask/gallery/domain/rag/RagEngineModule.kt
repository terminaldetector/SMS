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
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Standard Hilt binding: makes the [RagEngine] singleton injectable into Hilt-aware classes
 * (e.g. [RagManagerViewModel]).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RagEngineModule {
  @Binds abstract fun bindRagEngine(impl: RagEngineImpl): RagEngine
}

/**
 * [AgentToolsImpl] is NOT constructed by Hilt (see `AgentChatTaskModule.kt`:
 * `private val agentTools: AgentTools = AgentToolsImpl()`), so it can't use field/constructor
 * injection. This entry point lets it pull the *same* singleton [RagEngine] instance that
 * [RagManagerViewModel] uses, so documents added via the UI are immediately visible to the
 * `ragSearch` tool and vice versa.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RagEngineEntryPoint {
  fun ragEngine(): RagEngine
}

fun ragEngineFrom(context: android.content.Context): RagEngine =
  EntryPointAccessors.fromApplication(
      context.applicationContext,
      RagEngineEntryPoint::class.java,
    )
    .ragEngine()

// ---------------------------------------------------------------------------------------------
// Everything below is new (neural embedder pass) — see TextEmbedder.kt / TfLiteTextEmbedder.kt /
// FallbackTextEmbedder.kt / HashingTextEmbedder.kt / EmbedderSettingsStore.kt for the
// implementations these bind.
// ---------------------------------------------------------------------------------------------

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class EmbeddingModelFile

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class EmbeddingTokenizerFile

/**
 * Where the (large, license-gated, not bundled in the APK) EmbeddingGemma files live once
 * downloaded via [EMBEDDING_GEMMA_MODEL] / `DownloadRepository` (see
 * `RagManagerViewModel.downloadEmbeddingModel()`). These paths are derived from
 * [com.saturnmask.gallery.data.Model.getPath] against that same shared [Model] instance, rather
 * than hardcoded separately, so they can't drift out of sync with wherever `DownloadWorker`
 * actually writes the files.
 *
 * The tokenizer file is `tokenizer.json` (HuggingFace `tokenizers` JSON format, loadable by
 * `ai.djl.huggingface.tokenizers.HuggingFaceTokenizer`, e.g. from `google/embeddinggemma-300m`),
 * NOT the raw `sentencepiece.model` the LiteRT-converted repo ships — DJL's HuggingFace tokenizer
 * library expects the JSON format; loading a raw SentencePiece `.model` file needs the separate
 * `ai.djl.sentencepiece` native library instead, which isn't in use here.
 */
@Module
@InstallIn(SingletonComponent::class)
object RagEmbeddingFilesModule {
  @Provides
  @EmbeddingModelFile
  fun provideEmbeddingModelFile(@ApplicationContext context: Context): File =
    File(EMBEDDING_GEMMA_MODEL.getPath(context, EMBEDDING_GEMMA_MODEL.downloadFileName))

  @Provides
  @EmbeddingTokenizerFile
  fun provideEmbeddingTokenizerFile(@ApplicationContext context: Context): File {
    val tokenizerDataFile =
      EMBEDDING_GEMMA_MODEL.getExtraDataFile(EMBEDDING_GEMMA_TOKENIZER_DATA_FILE_NAME)
        ?: error("EMBEDDING_GEMMA_MODEL is missing its tokenizer ModelDataFile entry")
    return File(EMBEDDING_GEMMA_MODEL.getPath(context, tokenizerDataFile.downloadFileName))
  }

  /**
   * The built-in embedder: always EmbeddingGemma, always these two files. A user-selected custom
   * (third-party) embedder is a second, separately-constructed [TfLiteTextEmbedder] instance —
   * not provided by Hilt, since which files it points at is a runtime user choice, not fixed at
   * graph-construction time. See [FallbackTextEmbedder.refreshActiveEmbedder].
   */
  @Provides
  @Singleton
  fun provideBuiltInTfLiteTextEmbedder(
    @ApplicationContext context: Context,
    @EmbeddingModelFile modelFile: File,
    @EmbeddingTokenizerFile tokenizerFile: File,
  ): TfLiteTextEmbedder =
    TfLiteTextEmbedder(
      context = context,
      modelFile = modelFile,
      tokenizerFile = tokenizerFile,
      dimension = 768,
      id = "embeddinggemma-300m-tflite",
      queryPrefix = EMBEDDING_GEMMA_QUERY_PREFIX,
      documentPrefix = EMBEDDING_GEMMA_DOCUMENT_PREFIX,
    )
}

/**
 * The active [TextEmbedder]: [FallbackTextEmbedder] wraps whichever neural embedder is currently
 * selected (built-in EmbeddingGemma or a user-imported custom one — see [EmbedderSettingsStore])
 * and transparently degrades to [HashingTextEmbedder] if it isn't ready (or fails to load). This
 * is the ONLY line to change if you want to swap the *default* embedder wiring again later.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TextEmbedderModule {
  @Binds abstract fun bindTextEmbedder(impl: FallbackTextEmbedder): TextEmbedder
}
