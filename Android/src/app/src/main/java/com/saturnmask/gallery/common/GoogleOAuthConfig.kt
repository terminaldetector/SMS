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

package com.saturnmask.gallery.common

import androidx.core.net.toUri
import net.openid.appauth.AuthorizationServiceConfiguration

/**
 * Google OAuth 2.0 client config for the MCP Google Workspace presets (see
 * `domain/mcp/McpWorkspacePresets.kt`) — a separate client config from [ProjectConfig]
 * (HuggingFace's), so changing one provider's client credentials can't silently break the other's.
 *
 * [redirectUri] deliberately shares the SAME scheme as [ProjectConfig.redirectUri] (the
 * `appAuthRedirectScheme` manifest placeholder in `app/build.gradle.kts`) rather than getting its
 * own -- that placeholder name is baked into the `net.openid:appauth` library's own manifest
 * (contributed via manifest merging, nothing in this app's own AndroidManifest.xml references it),
 * so a differently-named placeholder wouldn't actually be wired to anything without also hand-adding
 * an `<activity-alias>` pointing at `RedirectUriReceiverActivity`, which isn't worth the added manifest
 * risk here. AppAuth doesn't require unique schemes per provider -- the pending `AuthorizationRequest`
 * is what's matched on redirect, not the scheme -- so one shared scheme with two different full
 * redirect URIs (different paths) is both valid and the lower-risk choice.
 *
 * To make this real: register an OAuth 2.0 client in Google Cloud Console for a type that supports
 * a custom-URI-scheme redirect (the RFC 8252 native-app pattern this app's HuggingFace flow already
 * uses via the same `net.openid:appauth` library), then replace [clientId]/[redirectUri] below --
 * [redirectUri] must start with whatever scheme is registered for `appAuthRedirectScheme`. Double
 * -check the current Google Cloud Console flow for which client type applies when you register it --
 * unverified against Google's live docs this session (network egress to developers.google.com was
 * unavailable).
 */
object GoogleOAuthConfig {
  const val clientId = "REPLACE_WITH_YOUR_GOOGLE_OAUTH_CLIENT_ID"

  // Registered redirect URI -- must use the same scheme as ProjectConfig.redirectUri
  // (the "appAuthRedirectScheme" placeholder in build.gradle.kts), just a different path, e.g.
  // "<scheme>://oauth2redirect/mcp-google" if ProjectConfig's is "<scheme>://oauth2redirect".
  const val redirectUri = "REPLACE_WITH_YOUR_GOOGLE_OAUTH_REDIRECT_URI"

  // OAuth 2.0 Endpoints (Authorization + Token Exchange) -- fixed, Google's own.
  private const val authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
  private const val tokenEndpoint = "https://oauth2.googleapis.com/token"

  val authServiceConfig =
    AuthorizationServiceConfiguration(
      authEndpoint.toUri(), // Authorization endpoint
      tokenEndpoint.toUri(), // Token exchange endpoint
    )
}
