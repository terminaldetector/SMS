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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saturnmask.gallery.domain.websearch.WebSearchEngine
import com.saturnmask.gallery.domain.websearch.WebSearchProvider
import com.saturnmask.gallery.domain.websearch.WebSearchSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebSearchSettingsUiState(
  val selectedProvider: WebSearchProvider = WebSearchProvider.NONE,
  val hasApiKey: Boolean = false, // for selectedProvider specifically
  val testing: Boolean = false,
  val testResult: String? = null, // null = untested, else success or error message
  val dailyCallsRemaining: Int = 0,
)

@HiltViewModel
class WebSearchSettingsViewModel
@Inject
constructor(
  private val settingsStore: WebSearchSettingsStore,
  private val webSearchEngine: WebSearchEngine,
) : ViewModel() {

  private val _uiState =
    MutableStateFlow(
      WebSearchSettingsUiState(
        selectedProvider = settingsStore.getSelectedProvider(),
        hasApiKey = settingsStore.hasApiKey(settingsStore.getSelectedProvider()),
        dailyCallsRemaining = settingsStore.getDailyCallsRemaining(),
      )
    )
  val uiState = _uiState.asStateFlow()

  fun selectProvider(provider: WebSearchProvider) {
    settingsStore.setSelectedProvider(provider)
    _uiState.update {
      it.copy(
        selectedProvider = provider,
        hasApiKey = settingsStore.hasApiKey(provider),
        testResult = null,
      )
    }
  }

  fun saveApiKey(key: String) {
    val provider = uiState.value.selectedProvider
    settingsStore.setApiKey(provider, key)
    _uiState.update { it.copy(hasApiKey = settingsStore.hasApiKey(provider), testResult = null) }
  }

  fun clearApiKey() {
    val provider = uiState.value.selectedProvider
    settingsStore.clearApiKey(provider)
    _uiState.update { it.copy(hasApiKey = false, testResult = null) }
  }

  /** Fires one real search call so the user finds out the key is wrong here, not mid-chat. */
  fun testConnection() {
    viewModelScope.launch {
      val providerLabel = uiState.value.selectedProvider.name.lowercase().replaceFirstChar { it.uppercase() }
      _uiState.update { it.copy(testing = true, testResult = null) }
      val result = webSearchEngine.search(query = "test", maxResults = 1)
      _uiState.update {
        it.copy(
          testing = false,
          testResult =
            result.fold(
              onSuccess = { "✓ Connected — $providerLabel is working." },
              onFailure = { e -> "✗ ${e.message}" },
            ),
          // The test call itself counts against the daily cap (see
          // WebSearchSettingsStore.tryConsumeDailyCall) — reflect that immediately.
          dailyCallsRemaining = settingsStore.getDailyCallsRemaining(),
        )
      }
    }
  }
}
