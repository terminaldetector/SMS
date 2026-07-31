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

package com.saturnmask.gallery.data

import com.saturnmask.edge.distilled.settings.AccessTokenStore
import javax.inject.Inject
import javax.inject.Singleton

/** Gallery adapter: binds protobuf DataStore tokens to distilled [AccessTokenStore]. */
@Singleton
class DataStoreAccessTokenStore
@Inject
constructor(private val dataStoreRepository: DataStoreRepository) : AccessTokenStore {
  override fun readAccessToken(): String? =
    dataStoreRepository.readAccessTokenData()?.accessToken?.takeIf { it.isNotEmpty() }

  override fun saveAccessToken(accessToken: String, refreshToken: String, expiresAt: Long) {
    dataStoreRepository.saveAccessTokenData(accessToken, refreshToken, expiresAt)
  }

  override fun clearAccessToken() {
    dataStoreRepository.clearAccessTokenData()
  }
}
