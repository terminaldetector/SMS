/*
 * Copyright 2026 Saturn Mask / GEDGE distillation
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

package com.saturnmask.edge.distilled.engine

import com.saturnmask.gallery.data.Accelerator
import com.google.ai.edge.litertlm.Backend

/**
 * Maps Gallery accelerator labels / [Accelerator] values to LiteRT-LM [Backend] instances.
 *
 * This is the distilled stand-in for a "CompiledModel API" backend switch — this fork drives
 * LiteRT-LM `EngineConfig.backend` / `visionBackend` / `audioBackend` rather than a separate
 * CompiledModel surface.
 */
object BackendSelector {

  fun forAccelerator(
    accelerator: Accelerator,
    numOfThreads: Int = 0,
    nativeLibraryDir: String? = null,
  ): Backend = forLabel(accelerator.label, numOfThreads, nativeLibraryDir)

  fun forLabel(
    label: String,
    numOfThreads: Int = 0,
    nativeLibraryDir: String? = null,
  ): Backend =
    when (label) {
      Accelerator.CPU.label -> Backend.CPU(numOfThreads = numOfThreads)
      Accelerator.GPU.label -> Backend.GPU()
      Accelerator.NPU.label,
      Accelerator.TPU.label ->
        Backend.NPU(nativeLibraryDir = requireNotNull(nativeLibraryDir) {
          "nativeLibraryDir is required for NPU/TPU backends"
        })
      else -> Backend.CPU(numOfThreads = numOfThreads)
    }

  /** Gemma 3n audio path is CPU-only in this runtime. */
  fun audioBackend(numOfThreads: Int = 0): Backend = Backend.CPU(numOfThreads = numOfThreads)
}
