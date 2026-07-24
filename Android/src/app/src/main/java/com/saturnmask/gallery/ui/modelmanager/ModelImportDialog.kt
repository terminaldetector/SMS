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

package com.saturnmask.gallery.ui.modelmanager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saturnmask.gallery.R
import com.saturnmask.gallery.common.isPixel10
import com.saturnmask.gallery.data.Accelerator
import com.saturnmask.gallery.data.BooleanSwitchConfig
import com.saturnmask.gallery.data.Config
import com.saturnmask.gallery.data.ConfigKey
import com.saturnmask.gallery.data.ConfigKeys
import com.saturnmask.gallery.data.DEFAULT_MAX_TOKEN
import com.saturnmask.gallery.data.DEFAULT_TEMPERATURE
import com.saturnmask.gallery.data.DEFAULT_TOPK
import com.saturnmask.gallery.data.DEFAULT_TOPP
import com.saturnmask.gallery.data.IMPORTS_DIR
import com.saturnmask.gallery.data.LabelConfig
import com.saturnmask.gallery.data.NumberSliderConfig
import com.saturnmask.gallery.data.SegmentedButtonConfig
import com.saturnmask.gallery.data.ValueType
import com.saturnmask.gallery.data.convertValueToTargetType
import com.saturnmask.gallery.proto.ImportedModel
import com.saturnmask.gallery.proto.LlmConfig
import com.saturnmask.gallery.ui.common.ConfigEditorsPanel
import com.saturnmask.gallery.ui.common.ensureValidFileName
import com.saturnmask.gallery.ui.common.humanReadableSize
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "AGModelImportDialog"

private val SUPPORTED_ACCELERATORS: List<Accelerator> =
  if (isPixel10()) {
    listOf(Accelerator.CPU, Accelerator.NPU)
  } else {
    listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)
  }

private val IMPORT_CONFIGS_LLM: List<Config> =
  listOf(
    LabelConfig(key = ConfigKeys.NAME),
    LabelConfig(key = ConfigKeys.MODEL_TYPE),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_MAX_TOKENS,
      sliderMin = 100f,
      sliderMax = 4096f,
      defaultValue = DEFAULT_MAX_TOKEN.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPK,
      sliderMin = 1f,
      sliderMax = 100f,
      defaultValue = DEFAULT_TOPK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPP,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = DEFAULT_TOPP,
      valueType = ValueType.FLOAT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 2.0f,
      defaultValue = DEFAULT_TEMPERATURE,
      valueType = ValueType.FLOAT,
    ),
    // No "Support image"/"Support audio"/"Support speculative decoding" toggles: whether a
    // .task/.litertlm file actually has a vision/audio encoder, or supports speculative decoding,
    // is a property of the file itself — LlmChatModelHelper.initialize() detects image/audio by
    // trying and backing off on the specific engine-creation failure (see its doc comment), and
    // speculative decoding is gated by litertlm's own Capabilities.hasSpeculativeDecodingSupport()
    // probe at load time regardless of what's declared here. "Support thinking" stays manual —
    // unlike a missing encoder, a model without thinking training doesn't fail to load, it just
    // silently produces no thinking-channel output, so there's no failure signal to detect from.
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_THINKING, defaultValue = false),
    SegmentedButtonConfig(
      key = ConfigKeys.COMPATIBLE_ACCELERATORS,
      defaultValue = SUPPORTED_ACCELERATORS[0].label,
      options = SUPPORTED_ACCELERATORS.map { it.label },
      allowMultiple = true,
    ),
  )

@Composable
fun ModelImportDialog(
  uri: Uri,
  onDismiss: () -> Unit,
  onDone: (ImportedModel) -> Unit,
  defaultValues: Map<ConfigKey, Any> = emptyMap(),
) {
  val context = LocalContext.current
  val info = remember { getFileSizeAndDisplayNameFromUri(context = context, uri = uri) }
  var fileSize by remember { mutableLongStateOf(info.first) }
  val fileName by remember { mutableStateOf(ensureValidFileName(info.second)) }

  LaunchedEffect(uri) {
    if (uri.scheme == "http" || uri.scheme == "https") {
      kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
          // Get the file size from the download url.
          val downloadUrl = getDownloadUrl(uri)
          val connection = java.net.URL(downloadUrl).openConnection()
          connection.connect()
          val size = connection.contentLengthLong
          if (size > 0) {
            fileSize = size
          }
          connection.getInputStream().close()
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }
  }

  val initialValues: Map<String, Any> = remember {
    mutableMapOf<String, Any>().apply {
      for (config in IMPORT_CONFIGS_LLM) {
        put(config.key.label, config.defaultValue)
      }
      put(ConfigKeys.NAME.label, fileName)
      // TODO: support other types.
      put(ConfigKeys.MODEL_TYPE.label, "LLM")

      for ((key, value) in defaultValues) {
        put(key.label, value)
      }
    }
  }
  val values: SnapshotStateMap<String, Any> = remember {
    mutableStateMapOf<String, Any>().apply { putAll(initialValues) }
  }
  val interactionSource = remember { MutableInteractionSource() }

  Dialog(onDismissRequest = onDismiss) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null, // Disable the ripple effect
        ) {
          focusManager.clearFocus()
        },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Title.
        Text(
          stringResource(R.string.import_model),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          // Default configs for users to set.
          ConfigEditorsPanel(configs = IMPORT_CONFIGS_LLM, values = values)

          // Whether a file actually runs on GPU/NPU depends on its architecture, which can't be
          // validated from a filename alone — this only sets expectations, it can't restrict the
          // picker to what's actually safe.
          Text(
            stringResource(R.string.import_model_accelerator_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          // Cancel button.
          TextButton(onClick = { onDismiss() }) { Text(stringResource(R.string.cancel)) }

          // Import button
          Button(
            onClick = {
              val supportedAccelerators =
                (convertValueToTargetType(
                    value = values.get(ConfigKeys.COMPATIBLE_ACCELERATORS.label)!!,
                    valueType = ValueType.STRING,
                  )
                    as String)
                  .split(",")
              val defaultMaxTokens =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_MAX_TOKENS.label)!!,
                  valueType = ValueType.INT,
                )
                  as Int
              val defaultTopk =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_TOPK.label)!!,
                  valueType = ValueType.INT,
                )
                  as Int
              val defaultTopp =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_TOPP.label)!!,
                  valueType = ValueType.FLOAT,
                )
                  as Float
              val defaultTemperature =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_TEMPERATURE.label)!!,
                  valueType = ValueType.FLOAT,
                )
                  as Float
              val supportThinking =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_THINKING.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val downloadUrl = getDownloadUrl(uri)
              val importedModel: ImportedModel =
                ImportedModel.newBuilder()
                  .setFileName(fileName)
                  .setFileSize(fileSize)
                  .setUrl(if (uri.scheme == "http" || uri.scheme == "https") downloadUrl else "")
                  .setLlmConfig(
                    LlmConfig.newBuilder()
                      .addAllCompatibleAccelerators(supportedAccelerators)
                      .setDefaultMaxTokens(defaultMaxTokens)
                      .setDefaultTopk(defaultTopk)
                      .setDefaultTopp(defaultTopp)
                      .setDefaultTemperature(defaultTemperature)
                      // Declared optimistically — LlmChatModelHelper.initialize() corrects these
                      // to what the model file actually supports on first load (see its doc
                      // comment); speculative decoding is separately gated by litertlm's live
                      // Capabilities probe regardless of this declaration.
                      .setSupportImage(true)
                      .setSupportAudio(true)
                      .setSupportThinking(supportThinking)
                      .setSupportSpeculativeDecoding(true)
                      .build()
                  )
                  .build()
              onDone(importedModel)
            }
          ) {
            Text(stringResource(R.string.import_action))
          }
        }
      }
    }
  }
}

@Composable
fun ModelImportingDialog(
  uri: Uri,
  info: ImportedModel,
  onDismiss: () -> Unit,
  onDone: (ImportedModel) -> Unit,
) {
  var error by remember { mutableStateOf("") }
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var progress by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    // Import.
    importModel(
      context = context,
      coroutineScope = coroutineScope,
      fileName = info.fileName,
      fileSize = info.fileSize,
      uri = uri,
      onDone = { onDone(info) },
      onProgress = { progress = it },
      onError = { error = it },
    )
  }

  Dialog(
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    onDismissRequest = onDismiss,
  ) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Title.
        Text(
          stringResource(R.string.import_model),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        // No error.
        if (error.isEmpty()) {
          // Progress bar.
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "${info.fileName} (${info.fileSize.humanReadableSize()})",
              style = MaterialTheme.typography.labelSmall,
            )
            val animatedProgress = remember { Animatable(0f) }
            LinearProgressIndicator(
              progress = { animatedProgress.value },
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            LaunchedEffect(progress) {
              animatedProgress.animateTo(progress, animationSpec = tween(150))
            }
          }
        }
        // Has error.
        else {
          Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              Icons.Rounded.Error,
              contentDescription = stringResource(R.string.cd_error),
              tint = MaterialTheme.colorScheme.error,
            )
            Text(
              error,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onDismiss() }) { Text(stringResource(R.string.close)) }
          }
        }
      }
    }
  }
}

private fun importModel(
  context: Context,
  coroutineScope: CoroutineScope,
  fileName: String,
  fileSize: Long,
  uri: Uri,
  onDone: () -> Unit,
  onProgress: (Float) -> Unit,
  onError: (String) -> Unit,
) {
  // TODO: handle error.
  coroutineScope.launch(Dispatchers.IO) {
    // If it's a model from the web, we don't need to copy the file over.
    if (uri.scheme == "http" || uri.scheme == "https") {
      Log.d(TAG, "importing web model from $uri. File name: $fileName. File size: $fileSize")
      // Simulate a quick progress animation to show the user it's being added
      // for (i in 1..10) {
      //   kotlinx.coroutines.delay(50)
      //   onProgress(i.toFloat() / 10f)
      // }
      Log.d(TAG, "import done for web model")
      onDone()
      return@launch
    }

    // Get the last component of the uri path as the imported file name.
    val decodedUri = URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8.name())
    Log.d(TAG, "importing model from $decodedUri. File name: $fileName. File size: $fileSize")

    // Create <app_external_dir>/imports if not exist.
    val importsDir = File(context.getExternalFilesDir(null), IMPORTS_DIR)
    if (!importsDir.exists()) {
      importsDir.mkdirs()
    }

    // Import by copying the file over.
    val outputFile = File(context.getExternalFilesDir(null), "$IMPORTS_DIR/$fileName")
    val outputStream = FileOutputStream(outputFile)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesRead: Int
    var lastSetProgressTs: Long = 0
    var importedBytes = 0L
    val inputStream = context.contentResolver.openInputStream(uri)
    try {
      if (inputStream != null) {
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
          outputStream.write(buffer, 0, bytesRead)
          importedBytes += bytesRead

          // Report progress every 200 ms.
          val curTs = System.currentTimeMillis()
          if (curTs - lastSetProgressTs > 200) {
            Log.d(TAG, "importing progress: $importedBytes, $fileSize")
            lastSetProgressTs = curTs
            if (fileSize != 0L) {
              onProgress(importedBytes.toFloat() / fileSize.toFloat())
            }
          }
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
      onError(e.message ?: context.getString(R.string.failed_to_import))
      return@launch
    } finally {
      inputStream?.close()
      outputStream.close()
    }
    Log.d(TAG, "import done")
    onProgress(1f)
    onDone()
  }
}

private fun getFileSizeAndDisplayNameFromUri(context: Context, uri: Uri): Pair<Long, String> {
  if (uri.scheme == "http" || uri.scheme == "https") {
    return Pair(0L, uri.lastPathSegment ?: "")
  }
  val contentResolver = context.contentResolver
  var fileSize = 0L
  var displayName = ""

  try {
    contentResolver
      .query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
          fileSize = cursor.getLong(sizeIndex)

          val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
          displayName = cursor.getString(nameIndex)
        }
      }
  } catch (e: Exception) {
    e.printStackTrace()
    return Pair(0L, "")
  }

  return Pair(fileSize, displayName)
}

// Get the download url for the model.
private fun getDownloadUrl(uri: Uri): String {
  return if (uri.toString().contains("huggingface.co") && uri.toString().contains("/blob/")) {
    uri.toString().replaceFirst("/blob/", "/resolve/")
  } else {
    uri.toString()
  }
}
