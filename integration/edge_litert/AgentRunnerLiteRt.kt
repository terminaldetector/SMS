// SCAFFOLD (not compiled here). Edge / LiteRT host runner for HELIX Track A.
//
// Implements the poll-based `HostAgentRunner` contract (see helix/agent/host.py). Python's
// PollingAgentRunner adapts this to AgentRunner, so the rest of Track A (registry, the four
// coordination modes, context, healing, signed votes) is unchanged.
//
// Dependency: com.google.mediapipe:tasks-genai (LiteRT LLM Inference API).
// Fill in the TODOs; keep the JSON shape of poll() exactly as documented.

package com.example.helix.edge

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

class AgentRunnerLiteRt(
    private val llm: LlmInference,          // built from a .task bundle
    private val agentId: String,
    private val skills: List<String> = listOf("chat"),
    private val taskTypes: List<String> = listOf("chat"),
) {
    // task_id -> streamed chunks not yet polled
    private val queues = HashMap<String, ConcurrentLinkedQueue<String>>()
    private val done = HashSet<String>()

    fun cardJson(): String = JSONObject().apply {
        put("agent_id", agentId)
        put("skills", JSONArray(skills))
        put("task_types", JSONArray(taskTypes))
        // TODO: put("mem", availableRamBytes()); put("tps", measuredTokensPerSec())
    }.toString()

    fun submit(taskId: String, prompt: String, context: String) {
        queues[taskId] = ConcurrentLinkedQueue()
        val full = if (context.isBlank()) prompt else "$context\n\n$prompt"
        // LiteRT streams partial results on a background thread.
        llm.generateResponseAsync(full) { partial, isDone ->
            queues[taskId]?.add(partial)
            if (isDone) done.add(taskId)
        }
    }

    // Returns [{"task":..,"chunk":..,"done":bool}, ...] and clears what it returns.
    fun poll(): String {
        val arr = JSONArray()
        val it = queues.entries.iterator()
        while (it.hasNext()) {
            val (tid, q) = it.next()
            var emitted = false
            while (true) {
                val chunk = q.poll() ?: break
                arr.put(JSONObject().put("task", tid).put("chunk", chunk).put("done", false))
                emitted = true
            }
            if (done.contains(tid) && q.isEmpty()) {
                arr.put(JSONObject().put("task", tid).put("done", true))
                done.remove(tid); it.remove()
            } else if (!emitted) {
                // nothing new for this task this tick
            }
        }
        return arr.toString()
    }

    // App-defined confidence for voting mode; LiteRT gives no score. TODO: real heuristic.
    fun score(prompt: String, result: String): Double = result.length.toDouble()
}
