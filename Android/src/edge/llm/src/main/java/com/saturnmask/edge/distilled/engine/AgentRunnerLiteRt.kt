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

package com.saturnmask.edge.distilled.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONArray
import org.json.JSONObject

/**
 * HELIX Track A AgentRunner host adapter over [InferenceEngine] (LiteRT).
 *
 * Matches the poll-based contract from `INTEGRATION_BRIEF.md`, using distilled LiteRT-LM
 * instead of the MediaPipe `tasks-genai` scaffold.
 */
class AgentRunnerLiteRt(
  private val engine: InferenceEngine,
  private val agentId: String,
  private val skills: List<String> = listOf("chat"),
  private val taskTypes: List<String> = listOf("chat"),
) {
  private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
  private val cumulative = ConcurrentHashMap<String, String>()
  private val done = ConcurrentHashMap.newKeySet<String>()

  fun cardJson(): String =
    JSONObject()
      .put("agent_id", agentId)
      .put("skills", JSONArray(skills))
      .put("task_types", JSONArray(taskTypes))
      .put("engine", engine.kind.name)
      .toString()

  fun submit(taskId: String, prompt: String, context: String) {
    require(engine.isLoaded) { "InferenceEngine is not loaded" }
    queues[taskId] = ConcurrentLinkedQueue()
    cumulative[taskId] = ""
    done.remove(taskId)
    val full = if (context.isBlank()) prompt else "$context\n\n$prompt"
    engine.generate(
      request = EngineGenerateRequest(prompt = full),
      onPartial = { text, isDone ->
        val prev = cumulative[taskId].orEmpty()
        val delta = if (text.startsWith(prev)) text.removePrefix(prev) else text
        if (delta.isNotEmpty()) queues[taskId]?.add(delta)
        cumulative[taskId] = text
        if (isDone) done.add(taskId)
      },
      onError = { msg ->
        queues[taskId]?.add("[error] $msg")
        done.add(taskId)
      },
    )
  }

  /** Returns `[{"task":..,"chunk":..,"done":bool}, ...]` and clears emitted chunks. */
  fun poll(): String {
    val arr = JSONArray()
    val it = queues.entries.iterator()
    while (it.hasNext()) {
      val (tid, q) = it.next()
      while (true) {
        val chunk = q.poll() ?: break
        arr.put(JSONObject().put("task", tid).put("chunk", chunk).put("done", false))
      }
      if (done.contains(tid) && q.isEmpty()) {
        arr.put(JSONObject().put("task", tid).put("done", true))
        done.remove(tid)
        cumulative.remove(tid)
        it.remove()
      }
    }
    return arr.toString()
  }

  fun score(prompt: String, result: String): Double = result.length.toDouble().coerceAtLeast(1.0)

  fun stop() = engine.stop()
}
