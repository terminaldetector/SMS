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

package com.saturnmask.gallery.customtasks.agentchat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.saturnmask.gallery.data.Accelerator
import com.saturnmask.gallery.data.ModelDownloadStatusType
import com.saturnmask.gallery.domain.rag.EmbedderSource
import com.saturnmask.gallery.domain.rag.RagDocumentInfo
import com.saturnmask.gallery.domain.rag.RagDocumentPriority
import com.saturnmask.gallery.domain.rag.RagMode

/**
 * Consolidated "RAG settings" sheet — combines the on/off switch (previously the "RAG" trigger
 * chip's own instant-toggle behavior), a Static/Dynamic mode picker for new attachments, and the
 * existing document add/list/delete UI (plain text/markdown/PDF/DOCX/EPUB — extend the MIME type
 * filter below and [com.saturnmask.gallery.domain.rag.RagEngineImpl.addDocument] for more
 * formats), so the whole feature lives behind one entry point instead of three (see
 * [AgentModeTriggers], `MessageInputText.kt`'s removed "RAG" button).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagManagerBottomSheet(
  onDismiss: () -> Unit,
  ragEnabled: Boolean,
  onRagToggled: (Boolean) -> Unit,
  viewModel: RagManagerViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()

  val pickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      uri?.let { viewModel.importDocument(it) }
    }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(20.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = "RAG", style = MaterialTheme.typography.titleMedium)
        Switch(checked = ragEnabled, onCheckedChange = onRagToggled)
      }

      Text(
        text = "New attachments go to:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = uiState.selectedMode == RagMode.STATIC,
          onClick = { viewModel.setMode(RagMode.STATIC) },
          label = { Text("Always available") },
        )
        FilterChip(
          selected = uiState.selectedMode == RagMode.DYNAMIC,
          onClick = { viewModel.setMode(RagMode.DYNAMIC) },
          label = { Text("This chat only") },
        )
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        FilterChip(
          selected = uiState.embedderSource == EmbedderSource.BUILT_IN,
          onClick = { viewModel.useBuiltInEmbedder() },
          label = { Text("Built-in") },
        )
        FilterChip(
          selected = uiState.embedderSource == EmbedderSource.CUSTOM,
          onClick = { viewModel.useCustomEmbedder() },
          label = { Text("Custom") },
        )
      }
      Text(
        text = "Embedder runs on:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        FilterChip(
          selected = uiState.embedderAccelerator == Accelerator.CPU,
          onClick = { viewModel.setEmbedderAccelerator(Accelerator.CPU) },
          label = { Text("CPU") },
        )
        FilterChip(
          selected = uiState.embedderAccelerator == Accelerator.GPU,
          onClick = { viewModel.setEmbedderAccelerator(Accelerator.GPU) },
          label = { Text("GPU") },
        )
      }
      if (uiState.embedderSource == EmbedderSource.BUILT_IN) {
        EmbeddingModelDownloadSection(
          state = uiState.embeddingModelDownload,
          onDownloadClicked = viewModel::downloadEmbeddingModel,
          onCancelClicked = viewModel::cancelEmbeddingModelDownload,
        )
      } else {
        CustomEmbedderSection(
          customModelFileName = uiState.customModelFileName,
          customTokenizerFileName = uiState.customTokenizerFileName,
          importing = uiState.customEmbedderImporting,
          error = uiState.customEmbedderError,
          onImport = viewModel::importCustomEmbedder,
          onClear = viewModel::clearCustomEmbedder,
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = "Documents", style = MaterialTheme.typography.titleMedium)
        Button(
          onClick = {
            pickerLauncher.launch(
              arrayOf(
                "text/plain",
                "text/markdown",
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/epub+zip",
              )
            )
          },
          enabled = !uiState.importing,
        ) {
          Icon(Icons.Outlined.UploadFile, contentDescription = null)
          Spacer(modifier = Modifier.padding(start = 4.dp))
          Text("Add")
        }
      }

      uiState.error?.let { error ->
        Text(
          text = error,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 8.dp),
        )
      }

      if (uiState.importing) {
        ImportProgressIndicator(
          fraction = uiState.importFraction,
          progress = uiState.importProgress,
          modifier = Modifier.padding(top = 12.dp),
        )
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

      if (uiState.documents.isEmpty() && !uiState.importing) {
        Text(
          text = "No documents indexed yet. Add a .txt, .md, .pdf, .docx, or .epub file to enable RAG search.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        LazyColumn {
          items(uiState.documents, key = { it.id }) { doc ->
            RagDocumentRow(
              doc = doc,
              onRemove = { viewModel.removeDocument(doc.id) },
              onPriorityChanged = { priority -> viewModel.setDocumentPriority(doc.id, priority) },
            )
          }
        }
      }
    }
  }
}

/**
 * A real, determinate progress bar once chunk-level progress is available; falls back to an
 * indeterminate bar for the brief window before the first chunk finishes (parsing a huge PDF, or
 * a neural embedder's one-time model load — see `TfLiteTextEmbedder.ensureInitialized` — can both
 * take a moment before chunk 1/N is even reported).
 */
@Composable
private fun ImportProgressIndicator(
  fraction: Float?,
  progress: ImportProgress?,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth()) {
    if (fraction != null) {
      val animatedFraction by animateFloatAsState(targetValue = fraction, label = "importProgress")
      LinearProgressIndicator(
        progress = { animatedFraction },
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        text = "Indexing… ${progress?.done ?: 0}/${progress?.total ?: 0} chunks",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
    } else {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      Text(
        text = "Reading document…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

/**
 * "Download the neural embedding model" section. RAG search works today on
 * [com.saturnmask.gallery.domain.rag.HashingTextEmbedder] regardless of this download's status —
 * this is purely a search-quality upgrade, so its states are all non-blocking to the rest of the
 * sheet.
 */
@Composable
private fun EmbeddingModelDownloadSection(
  state: EmbeddingModelDownloadUiState,
  onDownloadClicked: () -> Unit,
  onCancelClicked: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(text = "Embedding model", style = MaterialTheme.typography.titleMedium)
    when (state.status) {
      ModelDownloadStatusType.NOT_DOWNLOADED -> {
        Text(
          text =
            "Using fast built-in hashing search. Download the higher-quality EmbeddingGemma " +
              "model (~200 MB, requires a HuggingFace access token) for better results.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Button(onClick = onDownloadClicked) { Text("Download") }
      }
      ModelDownloadStatusType.IN_PROGRESS,
      ModelDownloadStatusType.PARTIALLY_DOWNLOADED,
      ModelDownloadStatusType.UNZIPPING -> {
        val fraction =
          if (state.totalBytes > 0) state.receivedBytes.toFloat() / state.totalBytes else null
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            if (fraction != null) {
              LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
              Text(
                text = "Downloading… ${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
              )
            } else {
              LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              Text(
                text = "Downloading…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
              )
            }
          }
          IconButton(onClick = onCancelClicked) {
            Icon(Icons.Outlined.Close, contentDescription = "Cancel download")
          }
        }
      }
      ModelDownloadStatusType.SUCCEEDED -> {
        Row(
          modifier = Modifier.padding(top = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "Neural embedding model downloaded",
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
      ModelDownloadStatusType.FAILED -> {
        Text(
          text = state.errorMessage ?: "Download failed.",
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        OutlinedButton(onClick = onDownloadClicked) { Text("Retry") }
      }
    }
  }
}

/**
 * Lets the user pick their own `.tflite` embedding model + `tokenizer.json` pair instead of the
 * built-in EmbeddingGemma download. Dimension is user-entered — the file's actual output tensor
 * shape isn't introspected (see `TfLiteTextEmbedder`'s doc comment on unverified tensor layout,
 * which applies equally to a custom model).
 */
@Composable
private fun CustomEmbedderSection(
  customModelFileName: String?,
  customTokenizerFileName: String?,
  importing: Boolean,
  error: String?,
  onImport: (modelUri: Uri, tokenizerUri: Uri, dimension: Int) -> Unit,
  onClear: () -> Unit,
) {
  var pickedModelUri by remember { mutableStateOf<Uri?>(null) }
  var pickedTokenizerUri by remember { mutableStateOf<Uri?>(null) }
  var dimensionText by remember { mutableStateOf("768") }

  val modelPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) pickedModelUri = uri
    }
  val tokenizerPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) pickedTokenizerUri = uri
    }

  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = "Use your own embedding model instead of EmbeddingGemma.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 8.dp),
    )

    if (customModelFileName != null && customTokenizerFileName != null) {
      Text(
        text = "Active: $customModelFileName + $customTokenizerFileName",
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) { Text("Clear") }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { modelPickerLauncher.launch(arrayOf("*/*")) }, enabled = !importing) {
          Text(pickedModelUri?.let { "Model picked" } ?: "Pick .tflite model")
        }
        OutlinedButton(
          onClick = { tokenizerPickerLauncher.launch(arrayOf("application/json", "*/*")) },
          enabled = !importing,
        ) {
          Text(pickedTokenizerUri?.let { "Tokenizer picked" } ?: "Pick tokenizer.json")
        }
      }

      OutlinedTextField(
        value = dimensionText,
        onValueChange = { dimensionText = it.filter(Char::isDigit) },
        label = { Text("Embedding dimension") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.padding(top = 8.dp),
      )

      error?.let {
        Text(
          text = it,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 8.dp),
        )
      }

      Button(
        onClick = {
          val modelUri = pickedModelUri
          val tokenizerUri = pickedTokenizerUri
          val dimension = dimensionText.toIntOrNull()
          if (modelUri != null && tokenizerUri != null && dimension != null && dimension > 0) {
            onImport(modelUri, tokenizerUri, dimension)
          }
        },
        enabled =
          !importing && pickedModelUri != null && pickedTokenizerUri != null &&
            dimensionText.toIntOrNull() != null,
        modifier = Modifier.padding(top = 8.dp),
      ) {
        Text(if (importing) "Importing…" else "Use custom embedder")
      }
    }
  }
}

@Composable
private fun RagDocumentRow(
  doc: RagDocumentInfo,
  onRemove: () -> Unit,
  onPriorityChanged: (RagDocumentPriority) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = doc.name, style = MaterialTheme.typography.bodyLarge)
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Text(
            text = if (doc.scope == RagMode.DYNAMIC) "This chat" else "Static",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
        // Tap to cycle LOW -> NORMAL -> HIGH -> LOW. Manual priority setting — see
        // RagDocumentPriority's doc comment for how this combines with the automatic signals.
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.tertiaryContainer,
          modifier =
            Modifier.clickable {
              onPriorityChanged(
                when (doc.priority) {
                  RagDocumentPriority.LOW -> RagDocumentPriority.NORMAL
                  RagDocumentPriority.NORMAL -> RagDocumentPriority.HIGH
                  RagDocumentPriority.HIGH -> RagDocumentPriority.LOW
                }
              )
            },
        ) {
          Text(
            text =
              when (doc.priority) {
                RagDocumentPriority.LOW -> "Priority: Low"
                RagDocumentPriority.NORMAL -> "Priority: Normal"
                RagDocumentPriority.HIGH -> "Priority: High"
              },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }
      Text(
        text = "${doc.chunkCount} chunks · ${doc.sizeBytes / 1024} KB",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onRemove) {
      Icon(Icons.Outlined.Delete, contentDescription = "Remove ${doc.name}")
    }
  }
}
