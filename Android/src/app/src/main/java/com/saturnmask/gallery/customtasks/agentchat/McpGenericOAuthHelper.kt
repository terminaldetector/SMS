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
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.core.net.toUri
import com.saturnmask.gallery.domain.mcp.DiscoveredOAuthConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.RegistrationRequest
import net.openid.appauth.RegistrationResponse
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest

private const val TAG = "AGMcpGenericOAuth"

/**
 * The one fixed redirect URI for every OAuth provider this app self-registers with via Dynamic
 * Client Registration (any MCP server that isn't a built-in Google Workspace preset -- see
 * McpOAuthDiscovery.kt). Unlike GoogleOAuthConfig/ProjectConfig (which must match whatever
 * redirect URI *you* registered by hand in that provider's own console), a DCR-registered client
 * gets to declare its own redirect URI as part of the registration request -- so this only needs
 * to be internally consistent with itself, not matched against anything external. It still must
 * share the same scheme as the `appAuthRedirectScheme` manifest placeholder (build.gradle.kts) --
 * that placeholder name is baked into the AppAuth library's own manifest, so a working redirect
 * has to use its resolved scheme; only the path after `://` is free to pick, which is why this
 * uses a distinct path from GoogleOAuthConfig/ProjectConfig's redirect URIs.
 */
object McpGenericOAuthConfig {
  const val redirectUri = "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP://oauth/mcp/generic"
}

/**
 * Google OAuth (McpGoogleOAuthHelper.kt) hardcodes one provider's endpoints/client ID.  This is
 * the same request/exchange/refresh flow (same `net.openid:appauth` library, same PKCE pattern)
 * but parameterized per-call, for any MCP server whose OAuth endpoints and client ID were found
 * by McpOAuthDiscovery.kt instead of being pre-registered.
 *
 * The RegistrationRequest/RegistrationResponse API surface used in [registerClient] was verified
 * directly against the AppAuth-Android library source this session (not guessed) -- one thing
 * worth flagging from that: `RegistrationRequest.Builder` has no `setApplicationType` method;
 * `applicationType` is hardcoded "native" internally, which is what we want for this app anyway.
 */
@Singleton
class McpGenericOAuthHelper @Inject constructor(@ApplicationContext context: Context) {

  val authService = AuthorizationService(context)

  /**
   * RFC 7591 Dynamic Client Registration. [config]'s `registrationEndpoint` must be non-null --
   * AppAuth's `performRegistrationRequest` dereferences it unconditionally and will NPE if it's
   * missing, so only call this when [DiscoveredOAuthConfig.registrationEndpoint] was non-null.
   * Requests a public/native client (PKCE only, no client secret) via
   * `token_endpoint_auth_method=none`, which is what a mobile app should ask for.
   */
  suspend fun registerClient(
    config: AuthorizationServiceConfiguration,
    redirectUri: Uri,
  ): RegistrationResponse? =
    suspendCancellableCoroutine { continuation ->
      val request =
        RegistrationRequest.Builder(config, listOf(redirectUri))
          .setGrantTypeValues(listOf(GrantTypeValues.AUTHORIZATION_CODE, GrantTypeValues.REFRESH_TOKEN))
          .setResponseTypeValues(listOf(ResponseTypeValues.CODE))
          .setTokenEndpointAuthenticationMethod("none")
          .build()
      authService.performRegistrationRequest(request) { response, ex ->
        if (ex != null) Log.d(TAG, "Dynamic client registration failed: ${ex.message}")
        if (continuation.isActive) continuation.resume(response)
      }
    }

  fun getAuthorizationRequest(
    config: AuthorizationServiceConfiguration,
    clientId: String,
    scopes: List<String>,
  ): AuthorizationRequest {
    val builder =
      AuthorizationRequest.Builder(
        config,
        clientId,
        ResponseTypeValues.CODE,
        McpGenericOAuthConfig.redirectUri.toUri(),
      )
    if (scopes.isNotEmpty()) builder.setScope(scopes.joinToString(" "))
    return builder.build()
  }

  /**
   * Unlike Google (McpGoogleOAuthHelper.handleAuthResult), a generic OAuth 2.1 authorization
   * server is not required to return a refresh token or an expiration time -- Google's own
   * guarantee of one only comes from Google-specific `access_type=offline`+`prompt=consent`
   * parameters this flow doesn't send (there's no generic equivalent). A missing refresh token
   * just means this server's tokens can't be silently renewed later; a missing expiry is treated
   * as "expires immediately" so the next use always tries a refresh (and surfaces a clear error
   * if that's not possible, rather than silently reusing a token that might already be dead).
   */
  fun handleAuthResult(result: ActivityResult, onResult: (GoogleOAuthResult) -> Unit) {
    val dataIntent = result.data
    if (dataIntent == null) {
      onResult(GoogleOAuthResult(status = GoogleOAuthResultType.FAILED, errorMessage = "Empty auth result"))
      return
    }
    val response = AuthorizationResponse.fromIntent(dataIntent)
    val exception = AuthorizationException.fromIntent(dataIntent)
    when {
      response?.authorizationCode != null -> {
        authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenEx ->
          when {
            tokenResponse?.accessToken == null -> {
              onResult(
                GoogleOAuthResult(
                  status = GoogleOAuthResultType.FAILED,
                  errorMessage = "Token exchange failed: ${tokenEx?.message ?: "empty access token"}",
                )
              )
            }
            else -> {
              Log.d(TAG, "Generic OAuth token exchange successful.")
              onResult(
                GoogleOAuthResult(
                  status = GoogleOAuthResultType.SUCCEEDED,
                  accessToken = tokenResponse.accessToken,
                  refreshToken = tokenResponse.refreshToken,
                  expiresAtMs = tokenResponse.accessTokenExpirationTime ?: 0L,
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
      else -> onResult(GoogleOAuthResult(status = GoogleOAuthResultType.USER_CANCELLED))
    }
  }

  /**
   * Refreshes an access token for a server discovered via McpOAuthDiscovery.kt. Throws (via the
   * FAILED result the caller already checks) if [refreshToken] is null -- a server that never
   * gave us one can't be silently renewed, and the caller's existing error path already surfaces
   * that the same way any other connection failure is surfaced, so no special UI is needed.
   */
  suspend fun refreshAccessToken(
    tokenEndpoint: String,
    clientId: String,
    refreshToken: String,
  ): GoogleOAuthResult =
    suspendCancellableCoroutine { continuation ->
      // authorizationEndpoint is never dereferenced by a refresh-grant token request -- reusing
      // tokenEndpoint here just satisfies AuthorizationServiceConfiguration's non-null
      // constructor parameter, it isn't actually used for anything in this call.
      val config = AuthorizationServiceConfiguration(tokenEndpoint.toUri(), tokenEndpoint.toUri())
      val tokenRequest =
        TokenRequest.Builder(config, clientId)
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
            else -> {
              GoogleOAuthResult(
                status = GoogleOAuthResultType.SUCCEEDED,
                accessToken = tokenResponse.accessToken,
                refreshToken = tokenResponse.refreshToken ?: refreshToken,
                expiresAtMs = tokenResponse.accessTokenExpirationTime ?: 0L,
                scope = tokenResponse.scope,
              )
            }
          }
        if (continuation.isActive) continuation.resume(result)
      }
    }
}
