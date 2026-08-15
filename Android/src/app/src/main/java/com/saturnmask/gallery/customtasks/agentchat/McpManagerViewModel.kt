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

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saturnmask.gallery.BuildConfig
import com.saturnmask.gallery.domain.mcp.DiscoveredOAuthConfig
import com.saturnmask.gallery.domain.mcp.McpOAuthDiscovery
import com.saturnmask.gallery.proto.McpAuth
import com.saturnmask.gallery.proto.McpServer
import com.saturnmask.gallery.proto.McpServers
import com.saturnmask.gallery.proto.McpTool
import com.saturnmask.gallery.proto.UserData
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGMcpManagerVM"

data class McpServerState(val mcpServer: McpServer, val client: Client?, val error: String? = null)

data class McpManagerUiState(
  val mcpServers: List<McpServerState> = emptyList(),
  val loadingMcpServer: Boolean = false,
  val error: String? = null,
)

@HiltViewModel
class McpManagerViewModel
@Inject
constructor(
  private val mcpServersDataStore: DataStore<McpServers>,
  private val userDataDataStore: DataStore<UserData>,
  // Not private -- AddMcpServerFromUrlDialog.kt needs authService/getAuthorizationRequest() to
  // launch the Google sign-in flow directly, the same way ModelManagerViewModel exposes its own
  // HuggingFace `authService` to DownloadAndTryButton.kt.
  val googleOAuthHelper: McpGoogleOAuthHelper,
  // Same reasoning, for the generic (non-Google-preset) OAuth path -- see McpOAuthDiscovery.kt.
  val genericOAuthHelper: McpGenericOAuthHelper,
) : ViewModel() {
  private val _uiState = MutableStateFlow(McpManagerUiState())
  val uiState = _uiState.asStateFlow()

  private val httpClient = HttpClient(Android) { install(SSE) }

  /**
   * Loads the persisted MCP servers from the DataStore and initializes their client connections.
   */
  suspend fun loadMcpServers() {
    // 1. Set the loading indicator to signal background restoration is in progress.
    _uiState.update { it.copy(loadingMcpServer = true) }
    withContext(Dispatchers.IO) {
      try {
        // 2. Retrieve the list of configured MCP servers previously saved in DataStore.
        val savedServers = mcpServersDataStore.data.first().mcpServerList
        val loadedStates = savedServers.map { serverProto ->
          try {
            // Map existing tool enablement configurations to preserve user preferences upon
            // reconnect.
            val savedToolsMap = serverProto.toolsList.associate { it.name to it.enabled }
            val savedAlwaysAllowMap = serverProto.toolsList.associate { it.name to it.alwaysAllow }
            val (client, mcpTools) =
              initializeClientAndLoadTools(serverProto.url, savedToolsMap, savedAlwaysAllowMap)
            val serverVersion = client.serverVersion
            val updatedServerProto =
              serverProto
                .toBuilder()
                .clearTools()
                .addAllTools(mcpTools)
                .setEnabled(serverProto.enabled)
                .apply {
                  serverVersion?.name?.let { setName(it) }
                  serverVersion?.version?.let { setVersion(it) }
                  val desc = mcpTools.joinToString(", ") { it.name }
                  if (desc.isNotEmpty()) {
                    setDescription("Tools: $desc")
                  }
                }
                .build()
            McpServerState(mcpServer = updatedServerProto, client = client, error = null)
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error loading MCP server: ${serverProto.url}", e)
            // Fallback: If connection fails during startup, load the server in a disabled state.
            McpServerState(
              mcpServer = serverProto.toBuilder().setEnabled(false).build(),
              client = null,
              error = e.message ?: "Failed to connect",
            )
          }
        }
        // 3. Populate the live UI state with the freshly loaded client connections and tool
        // schemas.
        _uiState.update { it.copy(mcpServers = loadedStates, loadingMcpServer = false) }
        // 4. Re-sync the DataStore to reflect any newly discovered tool structures or disabled
        // fallback flags.
        mcpServersDataStore.updateData {
          McpServers.newBuilder()
            .addAllMcpServer(
              loadedStates.mapIndexed { index, state ->
                // If loading failed, persist the original state instead of the disabled state
                // used in memory, allowing retries on the next app start.
                if (state.error != null) savedServers[index] else state.mcpServer
              }
            )
            .build()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error reading saved MCP servers", e)
        _uiState.update { it.copy(loadingMcpServer = false) }
      }
    }
  }

  /**
   * Adds a new MCP server by URL, attempts to connect and discover its tools, and persists the
   * updated configuration.
   */
  fun addMcpServer(
    url: String,
    authType: McpAuth.AuthMethodCase,
    headerName: String,
    headerValue: String,
  ) {
    // Immediately display a loading spinner to the user while connecting, and clear any prior
    // error.
    _uiState.update { it.copy(loadingMcpServer = true, error = null) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Construct McpAuth for this call to pass to initializeClientAndLoadTools
        val currentAuthBuilder = McpAuth.newBuilder()
        when (authType) {
          McpAuth.AuthMethodCase.REQUEST_HEADER -> {
            currentAuthBuilder.setRequestHeader(
              McpAuth.RequestHeader.newBuilder()
                .setHeaderName(headerName)
                .setHeaderValue(headerValue)
                .build()
            )
          }
          McpAuth.AuthMethodCase.OAUTH -> {
            currentAuthBuilder.setOauth(McpAuth.OAuth.getDefaultInstance())
          }
          McpAuth.AuthMethodCase.NONE -> {
            currentAuthBuilder.setNone(true)
          }
          else -> {
            currentAuthBuilder.setNone(true)
          }
        }
        val currentAuth = currentAuthBuilder.build()

        // 1. Connect to the remote MCP server and fetch its list of exported tools.
        val (client, mcpTools) = initializeClientAndLoadTools(url, mcpAuth = currentAuth)
        val serverVersion = client.serverVersion
        val mcpServerProto =
          McpServer.newBuilder()
            .setUrl(url)
            .addAllTools(mcpTools)
            .setEnabled(true)
            .apply {
              serverVersion?.name?.let { setName(it) }
              serverVersion?.version?.let { setVersion(it) }
              val desc = mcpTools.joinToString(", ") { it.name }
              if (desc.isNotEmpty()) {
                setDescription("Tools: $desc")
              }
            }
            .build()

        val newState = McpServerState(mcpServer = mcpServerProto, client = client, error = null)

        // 2. Persist the server and tooling configurations to disk, deduplicating by URL.
        mcpServersDataStore.updateData { currentServers ->
          val filtered = currentServers.mcpServerList.filter { it.url != url }
          McpServers.newBuilder().addAllMcpServer(filtered + mcpServerProto).build()
        }

        // Persist auth data
        userDataDataStore.updateData { currentUserData ->
          currentUserData.toBuilder().putMcpAuths(url, currentAuth).build()
        }

        // 3. Update the local StateFlow so the UI updates dynamically.
        _uiState.update { currentState ->
          val filteredStates = currentState.mcpServers.filter { it.mcpServer.url != url }
          currentState.copy(mcpServers = filteredStates + newState, loadingMcpServer = false)
        }
        Log.d(TAG, "Analytics: mcp_management, action=add_server, status=success")
      } catch (e: Exception) {
        Log.e(TAG, "Error adding MCP server: $url", e)
        // Fallback: Update the UI state with the error message without preserving the server.
        _uiState.update { currentState ->
          currentState.copy(error = e.message ?: "Failed to connect", loadingMcpServer = false)
        }
        Log.d(
          TAG,
          "Analytics: mcp_management, action=add_server, status=failed, error_type=${e.javaClass.simpleName}",
        )
      }
    }
  }

  /**
   * Persists an MCP server that was already authorized via Google OAuth (see
   * AddMcpServerFromUrlDialog.kt's OAuth path, only reachable for the built-in Google Workspace
   * presets) -- mirrors [addMcpServer]'s persistence steps, but skips the auth-type branching
   * since OAuth is already resolved by the caller.
   */
  fun addMcpServerWithOAuthTokens(url: String, oauth: McpAuth.OAuth) {
    _uiState.update { it.copy(loadingMcpServer = true, error = null) }
    viewModelScope.launch(Dispatchers.IO) {
      try {
        val mcpAuth = McpAuth.newBuilder().setOauth(oauth).build()
        val (client, mcpTools) = initializeClientAndLoadTools(url, mcpAuth = mcpAuth)
        val serverVersion = client.serverVersion
        val mcpServerProto =
          McpServer.newBuilder()
            .setUrl(url)
            .addAllTools(mcpTools)
            .setEnabled(true)
            .apply {
              serverVersion?.name?.let { setName(it) }
              serverVersion?.version?.let { setVersion(it) }
              val desc = mcpTools.joinToString(", ") { it.name }
              if (desc.isNotEmpty()) {
                setDescription("Tools: $desc")
              }
            }
            .build()

        val newState = McpServerState(mcpServer = mcpServerProto, client = client, error = null)

        mcpServersDataStore.updateData { currentServers ->
          val filtered = currentServers.mcpServerList.filter { it.url != url }
          McpServers.newBuilder().addAllMcpServer(filtered + mcpServerProto).build()
        }

        userDataDataStore.updateData { currentUserData ->
          currentUserData.toBuilder().putMcpAuths(url, mcpAuth).build()
        }

        _uiState.update { currentState ->
          val filteredStates = currentState.mcpServers.filter { it.mcpServer.url != url }
          currentState.copy(mcpServers = filteredStates + newState, loadingMcpServer = false)
        }
        Log.d(TAG, "Analytics: mcp_management, action=add_server_oauth, status=success")
      } catch (e: Exception) {
        Log.e(TAG, "Error adding MCP server with OAuth: $url", e)
        _uiState.update { currentState ->
          currentState.copy(error = e.message ?: "Failed to connect", loadingMcpServer = false)
        }
        Log.d(
          TAG,
          "Analytics: mcp_management, action=add_server_oauth, status=failed, error_type=${e.javaClass.simpleName}",
        )
      }
    }
  }

  /** Clears any connection error. */
  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  /** Surfaces an error (e.g. a failed/cancelled Google OAuth attempt) via the same [McpManagerUiState.error]
   *  the add-server flow already uses, so the UI doesn't need a second error-display mechanism. */
  fun reportError(message: String) {
    _uiState.update { it.copy(error = message, loadingMcpServer = false) }
  }

  /** Removes an MCP server by its URL from both the current UI state and persistent DataStore. */
  fun removeMcpServer(url: String) {
    viewModelScope.launch(Dispatchers.IO) {
      mcpServersDataStore.updateData { currentServers ->
        val filtered = currentServers.mcpServerList.filter { it.url != url }
        McpServers.newBuilder().addAllMcpServer(filtered).build()
      }
      _uiState.update { currentState ->
        currentState.copy(mcpServers = currentState.mcpServers.filter { it.mcpServer.url != url })
      }
    }
  }

  /** Returns true if an MCP server with the given URL is already loaded. */
  fun hasMcpServer(url: String): Boolean {
    return _uiState.value.mcpServers.any { it.mcpServer.url == url }
  }

  /** Generates a textual prompt listing all enabled tools from all enabled MCP servers. */
  fun getToolsPrompt(): String {
    return _uiState.value.mcpServers
      .filter { it.mcpServer.enabled }
      .flatMap { it.mcpServer.toolsList }
      .filter { it.enabled }
      .joinToString("\n\n") { tool ->
        "MCP tool name: \"${tool.name}\"\n- Description: ${tool.description}\n- Input schema: ${tool.inputSchema}"
      }
  }

  /** Generates a summary of enablement state for all servers and tools. */
  fun getSelectedMcpsAndToolsSummary(): String = buildString {
    for (state in _uiState.value.mcpServers) {
      append("${state.mcpServer.url}:${state.mcpServer.enabled};")
      for (tool in state.mcpServer.toolsList) {
        append("${tool.name}:${tool.enabled};")
      }
    }
  }

  /** Enables or disables an entire MCP server, updating both UI state and DataStore persistence. */
  fun setMcpServerEnabled(url: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            state.copy(mcpServer = state.mcpServer.toBuilder().setEnabled(enabled).build())
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Enables or disables a specific tool under a given MCP server URL. */
  fun setMcpToolEnabled(url: String, toolName: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            val updatedTools =
              state.mcpServer.toolsList.map { tool ->
                if (tool.name == toolName) {
                  tool.toBuilder().setEnabled(enabled).build()
                } else {
                  tool
                }
              }
            state.copy(
              mcpServer = state.mcpServer.toBuilder().clearTools().addAllTools(updatedTools).build()
            )
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Enables or disables all loaded MCP servers in batch. */
  fun setAllMcpServerEnabled(enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.error != null) {
            state
          } else {
            state.copy(mcpServer = state.mcpServer.toBuilder().setEnabled(enabled).build())
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Enables or disables all tools under a specific MCP server URL. */
  fun setAllMcpToolsEnabled(url: String, enabled: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            val updatedTools =
              state.mcpServer.toolsList.map { tool -> tool.toBuilder().setEnabled(enabled).build() }
            state.copy(
              mcpServer = state.mcpServer.toBuilder().clearTools().addAllTools(updatedTools).build()
            )
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /** Sets always allow for a specific tool under a given MCP server URL. */
  fun setMcpToolAlwaysAllow(url: String, toolName: String, alwaysAllow: Boolean) {
    _uiState.update { currentState ->
      val updatedServers =
        currentState.mcpServers.map { state ->
          if (state.mcpServer.url == url) {
            val updatedTools =
              state.mcpServer.toolsList.map { tool ->
                if (tool.name == toolName) {
                  tool.toBuilder().setAlwaysAllow(alwaysAllow).build()
                } else {
                  tool
                }
              }
            state.copy(
              mcpServer = state.mcpServer.toBuilder().clearTools().addAllTools(updatedTools).build()
            )
          } else {
            state
          }
        }
      persistServers(updatedServers)
      currentState.copy(mcpServers = updatedServers)
    }
  }

  /**
   * Initializes a streaming transport client for the given URL and fetches its supported tools,
   * restoring their enabled states if available.
   */
  private suspend fun initializeClientAndLoadTools(
    url: String,
    savedToolsMap: Map<String, Boolean>? = null,
    savedAlwaysAllowMap: Map<String, Boolean>? = null,
    mcpAuth: McpAuth? = null,
  ): Pair<Client, List<McpTool>> {
    Log.d(TAG, "Initializing MCP for $url...")
    val client =
      Client(
        clientInfo =
          Implementation(name = "google-ai-edge-gallery", version = BuildConfig.VERSION_NAME)
      )
    // Retrieve authentication details from parameter or DataStore and configure the HTTP transport.
    val resolvedAuth = mcpAuth ?: userDataDataStore.data.first().mcpAuthsMap[url]
    val transport =
      when {
        resolvedAuth != null &&
          resolvedAuth.authMethodCase == McpAuth.AuthMethodCase.REQUEST_HEADER -> {
          val reqHeader = resolvedAuth.requestHeader
          StreamableHttpClientTransport(
            client = httpClient,
            url = url,
            requestBuilder = { headers.append(reqHeader.headerName, reqHeader.headerValue) },
          )
        }
        resolvedAuth != null && resolvedAuth.authMethodCase == McpAuth.AuthMethodCase.OAUTH -> {
          // Servers discovered+self-registered via McpOAuthDiscovery.kt have a client_id
          // persisted; the built-in Google Workspace presets never do (Google doesn't support
          // DCR), so they keep using the fixed GoogleOAuthConfig endpoints/client ID.
          val accessToken =
            if (resolvedAuth.oauth.clientId.isNotEmpty()) {
              resolveGenericAccessToken(url, resolvedAuth.oauth)
            } else {
              resolveGoogleAccessToken(url, resolvedAuth.oauth)
            }
          StreamableHttpClientTransport(
            client = httpClient,
            url = url,
            requestBuilder = { headers.append("Authorization", "Bearer $accessToken") },
          )
        }
        else -> StreamableHttpClientTransport(client = httpClient, url = url)
      }
    client.connect(transport)
    val toolsResponse = client.listTools()
    val mcpTools =
      toolsResponse?.tools.orEmpty().map { tool ->
        val isEnabled = savedToolsMap?.get(tool.name) ?: true
        val isAlwaysAllow = savedAlwaysAllowMap?.get(tool.name) ?: false
        // Manually build a valid JSON schema string using public SDK object properties.
        // This avoids restricted library visibility constraints while ensuring robust JSON
        // formatting
        // for cached tools and UI rendering.
        val propertiesJson = tool.inputSchema.properties.toString()
        val requiredJson =
          tool.inputSchema.required?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } ?: "[]"
        val schemaJson =
          """{"type":"object","properties":$propertiesJson,"required":$requiredJson}"""
        McpTool.newBuilder()
          .setName(tool.name)
          .setDescription(tool.description ?: "")
          .setInputSchema(schemaJson)
          .setEnabled(isEnabled)
          .setAlwaysAllow(isAlwaysAllow)
          .build()
      }
    Log.d(TAG, "Loaded ${mcpTools.size} tools from $url: ${mcpTools.joinToString { it.name }}")
    return Pair(client, mcpTools)
  }

  /**
   * Returns a valid access token for [oauth], refreshing it first if it's expired or close to it
   * (a real 5-minute buffer in milliseconds -- 5 * 60 * 1000 -- unlike the `5 * 60` typo in
   * ModelManagerViewModel's analogous HuggingFace expiry check, which is effectively a
   * ~0.3-second buffer, not fixed here since it's a separate, pre-existing, unrelated issue).
   * Persists a refreshed token back into userDataDataStore so later calls reuse it instead of
   * refreshing every time. Throws if refresh itself fails (e.g. the refresh token was revoked) --
   * the caller's existing try/catch (loadMcpServers/addMcpServer/addMcpServerWithOAuthTokens)
   * already turns that into the normal McpServerState.error path, no special-casing needed.
   */
  private suspend fun resolveGoogleAccessToken(url: String, oauth: McpAuth.OAuth): String {
    val bufferMs = 5 * 60 * 1000L
    if (System.currentTimeMillis() < oauth.expiresAtMs - bufferMs) {
      return oauth.accessToken
    }
    val refreshed = googleOAuthHelper.refreshAccessToken(oauth.refreshToken)
    if (refreshed.status != GoogleOAuthResultType.SUCCEEDED || refreshed.accessToken == null) {
      throw IllegalStateException(refreshed.errorMessage ?: "Failed to refresh Google OAuth token")
    }
    val updatedOAuth =
      oauth
        .toBuilder()
        .setAccessToken(refreshed.accessToken)
        .setRefreshToken(refreshed.refreshToken ?: oauth.refreshToken)
        .setExpiresAtMs(refreshed.expiresAtMs ?: oauth.expiresAtMs)
        .apply { refreshed.scope?.let { setScope(it) } }
        .build()
    userDataDataStore.updateData { currentUserData ->
      currentUserData
        .toBuilder()
        .putMcpAuths(url, McpAuth.newBuilder().setOauth(updatedOAuth).build())
        .build()
    }
    return refreshed.accessToken
  }

  /**
   * Same purpose as [resolveGoogleAccessToken], for a server discovered+self-registered via
   * McpOAuthDiscovery.kt (has [McpAuth.OAuth.getClientId] set). Unlike Google, a generic
   * provider isn't guaranteed to have given us a refresh token or a real expiry (see
   * McpGenericOAuthHelper.handleAuthResult's doc comment) -- expires_at_ms of 0 always fails the
   * freshness check below and forces a refresh attempt, which then throws its own clear error if
   * there's no refresh token to use, rather than silently reusing a token that might be dead.
   */
  private suspend fun resolveGenericAccessToken(url: String, oauth: McpAuth.OAuth): String {
    val bufferMs = 5 * 60 * 1000L
    if (oauth.expiresAtMs > 0 && System.currentTimeMillis() < oauth.expiresAtMs - bufferMs) {
      return oauth.accessToken
    }
    if (oauth.refreshToken.isEmpty()) {
      throw IllegalStateException("Access token expired and no refresh token is available")
    }
    val refreshed =
      genericOAuthHelper.refreshAccessToken(oauth.tokenEndpoint, oauth.clientId, oauth.refreshToken)
    if (refreshed.status != GoogleOAuthResultType.SUCCEEDED || refreshed.accessToken == null) {
      throw IllegalStateException(refreshed.errorMessage ?: "Failed to refresh OAuth token")
    }
    val updatedOAuth =
      oauth
        .toBuilder()
        .setAccessToken(refreshed.accessToken)
        .setRefreshToken(refreshed.refreshToken ?: oauth.refreshToken)
        .setExpiresAtMs(refreshed.expiresAtMs ?: oauth.expiresAtMs)
        .apply { refreshed.scope?.let { setScope(it) } }
        .build()
    userDataDataStore.updateData { currentUserData ->
      currentUserData
        .toBuilder()
        .putMcpAuths(url, McpAuth.newBuilder().setOauth(updatedOAuth).build())
        .build()
    }
    return refreshed.accessToken
  }

  /**
   * Runs OAuth discovery for [url] -- for the "arbitrary MCP server" OAuth path in
   * AddMcpServerFromUrlDialog.kt. Returns null if the server doesn't publish resource/
   * authorization-server metadata at the well-known locations McpOAuthDiscovery.kt checks.
   */
  suspend fun discoverGenericOAuthConfig(url: String): DiscoveredOAuthConfig? =
    withContext(Dispatchers.IO) { McpOAuthDiscovery.discoverGenericOAuthConfig(httpClient, url) }

  private fun persistServers(updatedServers: List<McpServerState>) {
    viewModelScope.launch(Dispatchers.IO) {
      mcpServersDataStore.updateData { currentServers ->
        val updatedProtos = updatedServers.map { state ->
          if (state.error != null) {
            currentServers.mcpServerList.find { it.url == state.mcpServer.url } ?: state.mcpServer
          } else {
            state.mcpServer
          }
        }
        McpServers.newBuilder().addAllMcpServer(updatedProtos).build()
      }
    }
  }
}
