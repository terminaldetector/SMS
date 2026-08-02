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

/**
 * Google's own remote MCP servers for Workspace — one per product, HTTP transport, OAuth 2.0.
 * Source: https://developers.google.com/workspace/guides/configure-mcp-servers (⚠️ marked
 * "Developer Preview" by Google as of when this was written — see [McpWorkspacePreset] doc).
 *
 * Deliberately NOT the various community `npx`/Docker-based Google Workspace MCP servers you'll
 * find searching GitHub (mcp-google-workspace, google_workspace_mcp, etc.) — those are local
 * stdio processes, which assume a Node.js/Python/Docker runtime. There is no such runtime on
 * Android; a stdio MCP server cannot run inside this app. Google's servers use plain HTTP, which
 * is the only transport that actually works from a mobile client.
 */
object McpWorkspacePresets {

  val ALL: List<McpWorkspacePreset> =
    listOf(
      McpWorkspacePreset(
        id = "gmail",
        displayName = "Gmail",
        serverUrl = "https://gmailmcp.googleapis.com/mcp/v1",
        readTools =
          listOf(
            "search_threads",
            "get_thread",
            "list_drafts",
            "list_labels",
          ),
        writeTools =
          listOf(
            // NOTE: there is no "send" tool in Gmail's official MCP tool list — only
            // create_draft. The model can never send an email directly through this server; a
            // human has to open Gmail and press send themselves. Worth knowing when deciding
            // what your confirmation gate needs to cover here.
            "create_draft",
            "create_label",
            "label_message",
            "label_thread",
            "unlabel_message",
            "unlabel_thread",
          ),
        oauthScopes =
          listOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",
          ),
      ),
      McpWorkspacePreset(
        id = "drive",
        displayName = "Google Drive",
        serverUrl = "https://drivemcp.googleapis.com/mcp/v1",
        readTools =
          listOf(
            "search_files",
            "read_file_content",
            "download_file_content",
            "get_file_metadata",
            "get_file_permissions",
            "list_recent_files",
          ),
        writeTools = listOf("create_file", "copy_file"),
        oauthScopes =
          listOf(
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/drive.file",
          ),
      ),
      McpWorkspacePreset(
        id = "calendar",
        displayName = "Google Calendar",
        serverUrl = "https://calendarmcp.googleapis.com/mcp/v1",
        readTools = listOf("list_calendars", "list_events", "get_event", "suggest_time"),
        writeTools = listOf("create_event", "update_event", "delete_event", "respond_to_event"),
        oauthScopes =
          listOf(
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
            "https://www.googleapis.com/auth/calendar.events.freebusy",
            "https://www.googleapis.com/auth/calendar.events.readonly",
            // NOTE: the scopes Google's guide lists for Calendar are all *.readonly /
            // *.freebusy — none of them actually grant write access. create_event/update_event/
            // delete_event are listed as MCP tools, but if you only request the scopes above,
            // those calls will fail with a permission error at Google's API layer, which is a
            // reasonable belt-and-suspenders default. Add
            // "https://www.googleapis.com/auth/calendar.events" explicitly if you want the
            // write tools to actually work — a deliberate choice to make, not an oversight here.
          ),
      ),
      McpWorkspacePreset(
        id = "people",
        displayName = "Contacts (People API)",
        serverUrl = "https://people.googleapis.com/mcp/v1",
        readTools = listOf("get_user_profile", "search_contacts", "search_directory_people"),
        writeTools = emptyList(),
        oauthScopes =
          listOf(
            "https://www.googleapis.com/auth/directory.readonly",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/contacts.readonly",
          ),
      ),
      McpWorkspacePreset(
        id = "chat",
        displayName = "Google Chat",
        serverUrl = "https://chatmcp.googleapis.com/mcp/v1",
        readTools = listOf("search_conversations", "list_messages", "search_messages"),
        writeTools = listOf("send_message"),
        oauthScopes =
          listOf(
            "https://www.googleapis.com/auth/chat.spaces.readonly",
            "https://www.googleapis.com/auth/chat.memberships.readonly",
            "https://www.googleapis.com/auth/chat.messages.readonly",
            "https://www.googleapis.com/auth/chat.messages.create",
            "https://www.googleapis.com/auth/chat.users.readstate.readonly",
          ),
      ),
    )
}

data class McpWorkspacePreset(
  val id: String,
  val displayName: String,
  val serverUrl: String,
  /** Tool names that only read data — safe to auto-approve if your app wants a fast path. */
  val readTools: List<String>,
  /**
   * Tool names that change state (send a message, create/delete an event, create a draft/file,
   * label email). Route these through the existing MCP confirmation gate
   * (`currentMcpPermissionAction`) — this list exists specifically so that gate can key off tool
   * name rather than guessing from the tool's free-text description.
   */
  val writeTools: List<String>,
  val oauthScopes: List<String>,
)
