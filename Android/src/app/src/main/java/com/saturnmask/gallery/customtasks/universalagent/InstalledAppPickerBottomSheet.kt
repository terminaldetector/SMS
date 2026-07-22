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

package com.saturnmask.gallery.customtasks.universalagent

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.saturnmask.gallery.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installed, launchable app — used by [InstalledAppPickerBottomSheet] to pick Universal
 *  Agent's scoped app. [icon] is null when the icon couldn't be decoded (falls back to a blank
 *  swatch rather than failing the whole row). */
private data class InstalledAppInfo(val packageName: String, val label: String, val icon: ImageBitmap?)

/**
 * Lets the user pick ONE installed app to restrict Universal Agent to (see
 * [UniversalAgentSettingsStore]). Requires the `<queries>` package-visibility block in
 * `AndroidManifest.xml` — without it, [android.content.pm.PackageManager.queryIntentActivities]
 * would only ever see this app itself on API 31+.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppPickerBottomSheet(
  onDismiss: () -> Unit,
  onAppSelected: (packageName: String, label: String) -> Unit,
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var apps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    apps = withContext(Dispatchers.IO) { queryInstalledApps(context) }
    loading = false
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          stringResource(R.string.universal_agent_choose_app_title),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
          Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
        }
      }
      if (loading) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }
      } else if (apps.isEmpty()) {
        Text(
          stringResource(R.string.universal_agent_choose_app_empty),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(16.dp),
        )
      } else {
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
          items(apps, key = { it.packageName }) { app ->
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clickable { onAppSelected(app.packageName, app.label) }
                  .padding(vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (app.icon != null) {
                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
              } else {
                Box(modifier = Modifier.size(40.dp))
              }
              Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                  app.packageName,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}

private fun queryInstalledApps(context: Context): List<InstalledAppInfo> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  @Suppress("DEPRECATION") val resolveInfos = pm.queryIntentActivities(intent, 0)
  return resolveInfos
    .asSequence()
    .filter { it.activityInfo.packageName != context.packageName }
    .distinctBy { it.activityInfo.packageName }
    .map { info ->
      InstalledAppInfo(
        packageName = info.activityInfo.packageName,
        label = info.loadLabel(pm).toString(),
        icon = runCatching { info.loadIcon(pm).toBitmap().asImageBitmap() }.getOrNull(),
      )
    }
    .sortedBy { it.label.lowercase() }
    .toList()
}
