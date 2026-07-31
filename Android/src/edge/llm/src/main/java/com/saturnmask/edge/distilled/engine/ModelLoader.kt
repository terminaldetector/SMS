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

import android.content.Context
import com.saturnmask.gallery.data.Model
import com.saturnmask.gallery.data.RuntimeType
import java.io.File

/**
 * Resolves on-disk model paths and basic readiness checks for LiteRT-LM / AICore models.
 * Download orchestration stays in the host app (HF auth, WorkManager).
 */
object ModelLoader {

  fun resolvePath(context: Context, model: Model): String = model.getPath(context)

  fun isFilePresent(context: Context, model: Model): Boolean =
    File(resolvePath(context, model)).exists()

  fun runtimeType(model: Model): RuntimeType = model.runtimeType

  fun prepareForLoad(model: Model) {
    model.preProcess()
  }
}
