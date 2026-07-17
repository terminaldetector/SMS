// SCAFFOLD (not compiled here). Foreground service wiring HELIX (Python core via Chaquopy)
// to the LiteRT runner and an IP transport, for the edge app.
//
// Pattern: run the Python `helix` package under Chaquopy; hand the Kotlin AgentRunnerLiteRt
// into a small Python bootstrap that builds an AgentNode; drive the asyncio loop on a worker
// thread. UI toggles this service on/off; PARTIAL chunks stream back to the chat.
//
// Requires: Chaquopy plugin (Python 3.12), helix/ copied into src/main/python/,
// foregroundServiceType="connectedDevice|dataSync" + Wi-Fi/Nearby permissions.

package com.example.helix.edge

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MeshService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var node: com.chaquo.python.PyObject   // helix AgentNode

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        startForeground(NOTIF_ID, buildNotification())      // TODO: notification

        val py = Python.getInstance()
        val runner = AgentRunnerLiteRt(buildLlm(), agentId = deviceAgentId())

        // Python glue: wrap the Kotlin runner (poll contract) and build an AgentNode.
        //   helix.agent.host.PollingAgentRunner(runner) -> AgentRunner
        //   + FrameCodec(identity.node_id, sealer) + Endpoint(WifiTransport, codec)
        //   + optional NodeIdentity (④) so votes/context are signed.
        // Provide a tiny helix/agent/edge_bootstrap.py returning (node, run_coro):
        val boot = py.getModule("helix.agent.edge_bootstrap")
        node = boot.callAttr("build_node", runner, clusterSecretBytes(), /*identity=*/true)

        // Drive the node's asyncio loops (announce/heartbeat/prune + pump) on a worker thread.
        scope.launch { boot.callAttr("run", node) }         // blocks in asyncio.run(...)
    }

    // Called from the MeshSkill/ViewModel to run a coordination request; returns the result.
    fun infer(prompt: String, mode: String): String {
        val boot = Python.getInstance().getModule("helix.agent.edge_bootstrap")
        return boot.callAttr("coordinate", node, prompt, mode).toString()   // single/parallel/voting/pipeline
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null   // or a Binder for the UI

    // --- TODOs the app fills in ---
    private fun buildLlm() = TODO("LlmInference.createFromOptions(context, .task path)")
    private fun deviceAgentId() = TODO("stable per-install id (or derive from ④ identity)")
    private fun clusterSecretBytes(): ByteArray = TODO("shared out-of-band (typed/QR once)")
    private fun buildNotification() = TODO("foreground notification")

    companion object { const val NOTIF_ID = 1001 }
}
