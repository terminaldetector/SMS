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

package com.saturnmask.gallery.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.saturnmask.gallery.R
import com.saturnmask.gallery.ui.modelmanager.ModelManagerViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/**
 * The HuggingFace access-token display/entry/clear UI, split out of [SettingsDialog] into its own
 * burger-menu entry point (a `SquareDrawerItem` in `HomeScreen.kt`, same pattern as
 * `WebSearchSettingsBottomSheet`) — a one-time-setup credential doesn't need to live inside the
 * general Settings catch-all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuggingFaceTokenBottomSheet(
  modelManagerViewModel: ModelManagerViewModel,
  onDismiss: () -> Unit,
) {
  var hfToken by remember { mutableStateOf(modelManagerViewModel.getTokenStatusAndData().data) }
  var customHfToken by remember { mutableStateOf("") }
  var isFocused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val dateFormatter = remember {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.systemDefault())
      .withLocale(Locale.getDefault())
  }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(20.dp)) {
      Text(text = "HuggingFace access token", style = MaterialTheme.typography.titleMedium)

      Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        // Show the start of the token.
        val curHfToken = hfToken
        if (curHfToken != null && curHfToken.accessToken.isNotEmpty()) {
          Text(
            curHfToken.accessToken.substring(0, min(16, curHfToken.accessToken.length)) + "...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            "Expires at: ${dateFormatter.format(Instant.ofEpochMilli(curHfToken.expiresAtMs))}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          Text(
            "Not available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            "The token will be automatically retrieved when a gated model is downloaded",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          OutlinedButton(
            onClick = {
              modelManagerViewModel.clearAccessToken()
              hfToken = null
            },
            enabled = curHfToken != null,
          ) {
            Text("Clear")
          }
          val handleSaveToken = {
            modelManagerViewModel.saveAccessToken(
              accessToken = customHfToken,
              refreshToken = "",
              expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10,
            )
            hfToken = modelManagerViewModel.getTokenStatusAndData().data
            focusManager.clearFocus()
          }
          BasicTextField(
            value = customHfToken,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { handleSaveToken() }),
            modifier =
              Modifier.fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused },
            onValueChange = { customHfToken = it },
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
          ) { innerTextField ->
            Box(
              modifier =
                Modifier.border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color =
                      if (isFocused) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                  )
                  .height(40.dp),
              contentAlignment = Alignment.CenterStart,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                  if (customHfToken.isEmpty()) {
                    Text(
                      "Enter token manually",
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      style = MaterialTheme.typography.bodySmall,
                    )
                  }
                  innerTextField()
                }
                if (customHfToken.isNotEmpty()) {
                  IconButton(modifier = Modifier.offset(x = 1.dp), onClick = handleSaveToken) {
                    Icon(
                      Icons.Rounded.CheckCircle,
                      contentDescription = stringResource(R.string.cd_done_icon),
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
