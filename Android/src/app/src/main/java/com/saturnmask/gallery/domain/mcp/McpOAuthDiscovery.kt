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

package com.saturnmask.gallery.domain.mcp

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URI
import org.json.JSONObject

private const val TAG = "AGMcpOAuthDiscovery"

/** Endpoints found for an MCP server that isn't a built-in Google Workspace preset. */
data class DiscoveredOAuthConfig(
  val authorizationEndpoint: String,
  val tokenEndpoint: String,
  /** Null if the authorization server doesn't support RFC 7591 Dynamic Client Registration. */
  val registrationEndpoint: String?,
)

/**
 * OAuth endpoint discovery for arbitrary (non-Google-preset) MCP servers, per the MCP
 * Authorization spec's model: the MCP server is an OAuth 2.1 *resource server* that points to a
 * separate *authorization server*, discovered via RFC 9728 (OAuth 2.0 Protected Resource
 * Metadata) then RFC 8414 (OAuth 2.0 Authorization Server Metadata / OIDC Discovery).
 *
 * Deliberately simplified vs. the full spec flow, in ways worth knowing before relying on this:
 * - The spec's canonical trigger is an unauthenticated request to the MCP server returning `401`
 *   with a `WWW-Authenticate: Bearer resource_metadata="https://.../.well-known/oauth-protected-
 *   resource..."` header pointing at the exact metadata URL. This skips that probe entirely and
 *   goes straight to the RFC 9728 well-known URL conventionally derived from the MCP server URL's
 *   own origin (the common case for the real providers this was researched against -- Notion,
 *   Linear, Sentry, Supabase, Zapier, PayPal all host their MCP endpoint and their
 *   protected-resource metadata on the same host). A server that only advertises a *different*
 *   resource-metadata host via the 401 challenge (allowed by spec) won't be found by this.
 * - RFC 7591 Dynamic Client Registration is the only client-provisioning mechanism attempted.
 *   The newer Client ID Metadata Documents mechanism (which some spec revisions now prefer over
 *   DCR) is not implemented -- no real provider with confirmed CIMD support was found during
 *   research for this feature (it's very new as of when this was written).
 * - None of this was tested against a real third-party MCP server's actual endpoints this
 *   session -- built from the RFC text and second-hand research, not verified live.
 */
object McpOAuthDiscovery {

  suspend fun discoverGenericOAuthConfig(
    client: HttpClient,
    serverUrl: String,
  ): DiscoveredOAuthConfig? {
    val resourceMetadata =
      fetchJson(client, wellKnownUrl(serverUrl, "oauth-protected-resource")) ?: return null
    val authServers = resourceMetadata.optJSONArray("authorization_servers")
    val issuer = if (authServers != null && authServers.length() > 0) authServers.getString(0) else null
    if (issuer == null) {
      Log.d(TAG, "No authorization_servers in protected resource metadata for $serverUrl")
      return null
    }
    val authServerMetadata = fetchAuthorizationServerMetadata(client, issuer) ?: return null
    val authorizationEndpoint = authServerMetadata.optString("authorization_endpoint").takeIf { it.isNotEmpty() }
    val tokenEndpoint = authServerMetadata.optString("token_endpoint").takeIf { it.isNotEmpty() }
    if (authorizationEndpoint == null || tokenEndpoint == null) {
      Log.d(TAG, "Authorization server metadata for $issuer missing required endpoints")
      return null
    }
    val registrationEndpoint = authServerMetadata.optString("registration_endpoint").takeIf { it.isNotEmpty() }
    return DiscoveredOAuthConfig(authorizationEndpoint, tokenEndpoint, registrationEndpoint)
  }

  /** Tries RFC 8414 (`oauth-authorization-server`) first, then falls back to OIDC Discovery
   * (`openid-configuration`) -- both use the same well-known-path-insertion convention and the
   * fields this needs (`authorization_endpoint`/`token_endpoint`/`registration_endpoint`) are
   * named identically in both. */
  private suspend fun fetchAuthorizationServerMetadata(client: HttpClient, issuer: String): JSONObject? {
    fetchJson(client, wellKnownUrl(issuer, "oauth-authorization-server"))?.let {
      return it
    }
    return fetchJson(client, wellKnownUrl(issuer, "openid-configuration"))
  }

  /** RFC 8414 section 3.1's well-known URL construction: insert `/.well-known/<name>` between the
   * origin and any path the base URL already has, e.g. `https://host/tenant1` +
   * `oauth-authorization-server` -> `https://host/.well-known/oauth-authorization-server/tenant1`. */
  private fun wellKnownUrl(base: String, wellKnownName: String): String {
    val uri = URI(base)
    val origin = "${uri.scheme}://${uri.authority}"
    val path = uri.rawPath?.trimEnd('/').orEmpty()
    return "$origin/.well-known/$wellKnownName$path"
  }

  private suspend fun fetchJson(client: HttpClient, url: String): JSONObject? =
    try {
      val response = client.get(url)
      if (response.status.isSuccess()) JSONObject(response.bodyAsText()) else null
    } catch (e: Exception) {
      Log.d(TAG, "Discovery fetch failed for $url: ${e.message}")
      null
    }
}
