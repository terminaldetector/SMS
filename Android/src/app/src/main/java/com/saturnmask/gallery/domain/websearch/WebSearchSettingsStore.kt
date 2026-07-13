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

package com.saturnmask.gallery.domain.websearch

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AGWebSearchSettings"
private const val PREFS_NAME = "web_search_settings"
private const val KEY_SELECTED_PROVIDER = "selected_provider"
private const val KEY_DAILY_CALL_COUNT = "daily_call_count"
private const val KEY_DAILY_CALL_DATE = "daily_call_date"

private fun apiKeyPrefKey(provider: WebSearchProvider) = "${provider.name.lowercase()}_api_key"

/**
 * Search backends the user can choose between in [WebSearchSettingsBottomSheet]. [NONE] is the
 * default and deliberately not a working provider — per the roadmap, there's no "works out of the
 * box" default: with [NONE] selected (or no key set for the chosen provider), `webSearch` should
 * tell the model search isn't configured rather than silently return an empty result.
 */
enum class WebSearchProvider {
  NONE,
  BRAVE,
  SERPER,
}

/**
 * Shared across every provider, not just Brave: Serper's quota is a one-time 2,500-query pool
 * rather than a monthly renewal, and even a no-cost provider shouldn't be hammered by a runaway
 * agent loop (e.g. Actions+RAG+Web all enabled and the model re-searching on every turn). 25/day
 * is well under Brave's ~33/day free-tier average and leaves Serper's pool lasting months of
 * normal use. Not user-configurable: this is a safety rail, not a preference, and a wrong value
 * here fails safe (blocks a call) rather than unsafe.
 */
const val DAILY_WEB_SEARCH_CALL_LIMIT = 25

/**
 * Stores the user's own API key per [WebSearchProvider] plus which provider is active. Keys are a
 * per-user, self-supplied credential (like pointing the app at your own OpenAI key would be) —
 * nothing is bundled or shared, and the app makes zero web-search calls until the user picks a
 * provider and pastes in a key.
 *
 * Uses [EncryptedSharedPreferences] since these are secrets; falls back to plain
 * [SharedPreferences] only if key-store-backed encryption is unavailable on the device (some
 * older/custom ROMs), which is still strictly better than crashing the settings screen — noted via
 * a log warning so it's not a silent security downgrade.
 */
@Singleton
class WebSearchSettingsStore @Inject constructor(@ApplicationContext context: Context) {

  private val prefs: SharedPreferences =
    runCatching {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
          context,
          PREFS_NAME,
          masterKey,
          EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
          EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
      }
      .getOrElse { e ->
        Log.w(TAG, "EncryptedSharedPreferences unavailable, storing API key unencrypted", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      }

  fun getSelectedProvider(): WebSearchProvider =
    runCatching {
        WebSearchProvider.valueOf(prefs.getString(KEY_SELECTED_PROVIDER, WebSearchProvider.NONE.name)!!)
      }
      .getOrDefault(WebSearchProvider.NONE)

  fun setSelectedProvider(provider: WebSearchProvider) {
    prefs.edit().putString(KEY_SELECTED_PROVIDER, provider.name).apply()
  }

  fun getApiKey(provider: WebSearchProvider): String? =
    prefs.getString(apiKeyPrefKey(provider), null)?.takeIf { it.isNotBlank() }

  fun setApiKey(provider: WebSearchProvider, key: String) {
    prefs.edit().putString(apiKeyPrefKey(provider), key.trim()).apply()
  }

  fun clearApiKey(provider: WebSearchProvider) {
    prefs.edit().remove(apiKeyPrefKey(provider)).apply()
  }

  fun hasApiKey(provider: WebSearchProvider): Boolean = !getApiKey(provider).isNullOrBlank()

  private fun todaysCallCount(): Int {
    val today = LocalDate.now().toString()
    return if (prefs.getString(KEY_DAILY_CALL_DATE, null) == today) {
      prefs.getInt(KEY_DAILY_CALL_COUNT, 0)
    } else {
      0
    }
  }

  /**
   * Call immediately before making a Brave Search request. Atomically checks-and-increments
   * today's count (resetting first if the stored date has rolled over) and returns whether this
   * call is still within [DAILY_WEB_SEARCH_CALL_LIMIT] — false means the caller must refuse the
   * request rather than spend it. The count is consumed here, not after a successful response, so
   * a request that fails downstream (bad key, network error) still counts — otherwise a broken key
   * would let a retry loop bypass the cap entirely.
   */
  @Synchronized
  fun tryConsumeDailyCall(): Boolean {
    val count = todaysCallCount()
    if (count >= DAILY_WEB_SEARCH_CALL_LIMIT) return false
    prefs.edit().putString(KEY_DAILY_CALL_DATE, LocalDate.now().toString()).putInt(KEY_DAILY_CALL_COUNT, count + 1).apply()
    return true
  }

  fun getDailyCallsRemaining(): Int = (DAILY_WEB_SEARCH_CALL_LIMIT - todaysCallCount()).coerceAtLeast(0)
}
