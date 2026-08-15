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

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.net.toUri
import com.saturnmask.gallery.common.GoogleOAuthConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest

private const val TAG = "AGMcpGoogleOAuth"

enum class GoogleOAuthResultType {
  SUCCEEDED,
  FAILED,
  USER_CANCELLED,
}

data class GoogleOAuthResult(
  val status: GoogleOAuthResultType,
  val accessToken: String? = null,
  val refreshToken: String? = null,
  val expiresAtMs: Long? = null,
  val scope: String? = null,
  val errorMessage: String? = null,
)

/**
 * Google OAuth 2.0 (authorization code + PKCE) request/exchange/refresh, for the MCP Google
 * Workspace presets (`domain/mcp/McpWorkspacePresets.kt`). Mirrors
 * `ui/modelmanager/ModelManagerViewModel`'s existing HuggingFace OAuth flow's structure exactly
 * (same `net.openid:appauth` library, same request/response shape) but adds real refresh-token
 * exchange, which that flow never built (it just reruns the full interactive login on expiry --
 * tolerable for a manual "download a model" click, not for MCP tool calls that can happen
 * mid-conversation).
 */
@Singleton
class McpGoogleOAuthHelper @Inject constructor(@ApplicationContext context: Context) {

  val authService = AuthorizationService(context)

  /**
   * [scopes] should come from the matched [com.saturnmask.gallery.domain.mcp.McpWorkspacePreset]'s
   * `oauthScopes`. `access_type=offline` + `prompt=consent` are both required for Google to
   * actually include a refresh_token in the response -- Google only issues one on a consent screen,
   * and without `prompt=consent` a repeat authorization can silently omit it.
   */
  fun getAuthorizationRequest(scopes: List<String>): AuthorizationRequest {
    return AuthorizationRequest.Builder(
        GoogleOAuthConfig.authServiceConfig,
        GoogleOAuthConfig.clientId,
        ResponseTypeValues.CODE,
        GoogleOAuthConfig.redirectUri.toUri(),
      )
      .setScope(scopes.joinToString(" "))
      .setAdditionalParameters(mapOf("access_type" to "offline", "prompt" to "consent"))
      .build()
  }

  fun handleAuthResult(result: ActivityResult, onResult: (GoogleOAuthResult) -> Unit) {
    val dataIntent = result.data
    if (dataIntent == null) {
      onResult(
        GoogleOAuthResult(status = GoogleOAuthResultType.FAILED, errorMessage = "Empty auth result")
      )
      return
    }

    val response = AuthorizationResponse.fromIntent(dataIntent)
    val exception = AuthorizationException.fromIntent(dataIntent)

    when {
      response?.authorizationCode != null -> {
        authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenEx
          ->
          when {
            tokenResponse == null -> {
              onResult(
                GoogleOAuthResult(
                  status = GoogleOAuthResultType.FAILED,
                  errorMessage = "Token exchange failed: ${tokenEx?.message}",
                )
              )
            }
            tokenResponse.accessToken == null -> {
              onResult(
                GoogleOAuthResult(status = GoogleOAuthResultType.FAILED, errorMessage = "Empty access token")
              )
            }
            tokenResponse.refreshToken == null -> {
              onResult(
                GoogleOAuthResult(
                  status = GoogleOAuthResultType.FAILED,
                  errorMessage = "Empty refresh token",
                )
              )
            }
            tokenResponse.accessTokenExpirationTime == null -> {
              onResult(
                GoogleOAuthResult(
                  status = GoogleOAuthResultType.FAILED,
                  errorMessage = "Empty expiration time",
                )
              )
            }
            else -> {
              Log.d(TAG, "Token exchange successful.")
              onResult(
                GoogleOAuthResult(
                  status = GoogleOAuthResultType.SUCCEEDED,
                  accessToken = tokenResponse.accessToken,
                  refreshToken = tokenResponse.refreshToken,
                  expiresAtMs = tokenResponse.accessTokenExpirationTime,
                  scope = tokenResponse.scope ?: response.scope,
                )
              )
            }
          }
        }
      }

      exception != null -> {
        onResult(
          GoogleOAuthResult(
            status =
              if (exception.message == "User cancelled flow") GoogleOAuthResultType.USER_CANCELLED
              else GoogleOAuthResultType.FAILED,
            errorMessage = exception.message,
          )
        )
      }

      else -> {
        onResult(GoogleOAuthResult(status = GoogleOAuthResultType.USER_CANCELLED))
      }
    }
  }

  /**
   * Exchanges a stored refresh token for a fresh access token, without any interactive login --
   * the capability the existing HuggingFace flow never built (see class doc comment). Returns a
   * [GoogleOAuthResult] with [GoogleOAuthResultType.FAILED] if the refresh token itself is no
   * longer valid (e.g. revoked by the user in their Google account) -- the caller should surface
   * this the same way any other MCP connection error is already surfaced, not treat it specially.
   */
  suspend fun refreshAccessToken(refreshToken: String): GoogleOAuthResult =
    suspendCancellableCoroutine { continuation ->
      val tokenRequest =
        TokenRequest.Builder(GoogleOAuthConfig.authServiceConfig, GoogleOAuthConfig.clientId)
          .setGrantType(GrantTypeValues.REFRESH_TOKEN)
          .setRefreshToken(refreshToken)
          .build()
      authService.performTokenRequest(tokenRequest) { tokenResponse, tokenEx ->
        val result =
          when {
            tokenResponse?.accessToken == null -> {
              GoogleOAuthResult(
                status = GoogleOAuthResultType.FAILED,
                errorMessage = "Token refresh failed: ${tokenEx?.message ?: "empty access token"}",
              )
            }
            tokenResponse.accessTokenExpirationTime == null -> {
              GoogleOAuthResult(
                status = GoogleOAuthResultType.FAILED,
                errorMessage = "Token refresh failed: empty expiration time",
              )
            }
            else -> {
              GoogleOAuthResult(
                status = GoogleOAuthResultType.SUCCEEDED,
                accessToken = tokenResponse.accessToken,
                // A refresh response doesn't always repeat the refresh token -- Google's typically
                // doesn't change it, so keep reusing the one we were given if a new one isn't sent.
                refreshToken = tokenResponse.refreshToken ?: refreshToken,
                expiresAtMs = tokenResponse.accessTokenExpirationTime,
                scope = tokenResponse.scope,
              )
            }
          }
        if (continuation.isActive) continuation.resume(result)
      }
    }
}
