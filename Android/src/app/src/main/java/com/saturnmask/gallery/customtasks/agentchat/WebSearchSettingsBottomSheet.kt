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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.saturnmask.gallery.domain.websearch.DAILY_WEB_SEARCH_CALL_LIMIT
import com.saturnmask.gallery.domain.websearch.WebSearchProvider

/**
 * Lets the user pick a [WebSearchProvider] and paste its API key to enable the `webSearch` agent
 * tool. Stateless aside from its Hilt-injected [WebSearchSettingsViewModel] (backed by a
 * `@Singleton` store), so it's safe to compose from more than one screen — currently both
 * `AgentChatScreen.kt` (the "Web search" trigger chip, see [AgentModeTriggers]) and
 * `HomeScreen.kt`'s burger-menu drawer (a `SquareDrawerItem`). Add a `showX` state var + trigger
 * at any new call site, same pattern as `showRagManagerBottomSheet`/[RagManagerBottomSheet].
 *
 * No provider is selected by default (see [WebSearchProvider.NONE]) — the roadmap calls for no
 * "works out of the box" default, so the model gets an explicit "search isn't configured" message
 * instead of a silent empty result until the user actively picks one here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSearchSettingsBottomSheet(
  onDismiss: () -> Unit,
  viewModel: WebSearchSettingsViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  // Keyed on the selected provider so switching providers clears any half-typed key for the
  // previous one rather than accidentally saving it under the new selection.
  var keyInput by remember(uiState.selectedProvider) { mutableStateOf("") }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(20.dp)) {
      Text(text = "Web search", style = MaterialTheme.typography.titleMedium)
      Text(
        text =
          "Search provider (used by the webSearch agent tool). Nothing is shared with Google or " +
            "Anthropic — whichever key you add below is stored on-device only.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        FilterChip(
          selected = uiState.selectedProvider == WebSearchProvider.NONE,
          onClick = { viewModel.selectProvider(WebSearchProvider.NONE) },
          label = { Text("Off") },
        )
        FilterChip(
          selected = uiState.selectedProvider == WebSearchProvider.BRAVE,
          onClick = { viewModel.selectProvider(WebSearchProvider.BRAVE) },
          label = { Text("Brave Search") },
        )
        FilterChip(
          selected = uiState.selectedProvider == WebSearchProvider.SERPER,
          onClick = { viewModel.selectProvider(WebSearchProvider.SERPER) },
          label = { Text("Serper.dev") },
        )
      }

      when (uiState.selectedProvider) {
        WebSearchProvider.NONE -> {
          Text(
            text = "Search is off. Pick a provider above to enable the webSearch tool.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
          )
        }
        WebSearchProvider.BRAVE -> {
          Text(
            text =
              "Requires your own key from api-dashboard.search.brave.com.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
          )
          Text(
            text =
              "⚠ Brave Search is metered pay-as-you-go: a payment method is required up front, " +
                "with no default spend cap on their side. The free tier is about \$5/month " +
                "(~1,000 queries). To guard against a runaway loop, this app caps itself at " +
                "$DAILY_WEB_SEARCH_CALL_LIMIT searches/day (${uiState.dailyCallsRemaining} left " +
                "today) — that's a local safety rail, not a substitute for setting your own " +
                "spend alerts in the Brave dashboard.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 12.dp),
          )
        }
        WebSearchProvider.SERPER -> {
          Text(
            text =
              "Requires your own key from serper.dev. Free tier is a one-time pool of 2,500 " +
                "queries, no card required up front — once it's used up you'll need a paid plan " +
                "there (unlike Brave's free tier, this pool doesn't renew monthly). This app " +
                "still caps itself at $DAILY_WEB_SEARCH_CALL_LIMIT searches/day " +
                "(${uiState.dailyCallsRemaining} left today) as a runaway-loop safety rail.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
          )
        }
      }

      if (uiState.selectedProvider != WebSearchProvider.NONE) {
        if (uiState.hasApiKey) {
          Text(text = "✓ API key configured", style = MaterialTheme.typography.bodyMedium)
          Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.testConnection() }, enabled = !uiState.testing) {
              Text("Test connection")
            }
            OutlinedButton(onClick = { viewModel.clearApiKey() }) { Text("Remove key") }
          }
        } else {
          OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
          )
          Button(
            onClick = { viewModel.saveApiKey(keyInput) },
            enabled = keyInput.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp),
          ) {
            Text("Save")
          }
        }
      }

      if (uiState.testing) {
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
          CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
          Text("Testing…")
        }
      }

      uiState.testResult?.let { result ->
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(text = result, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}
