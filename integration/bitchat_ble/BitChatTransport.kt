// SCAFFOLD (not compiled here). BitChat/BLE transport for HELIX (client join via Bluetooth).
//
// Bridges the Kotlin bitchat-core BLE mesh to the HELIX Transport contract, so a BT client can
// join the mesh and talk to a server; servers relay to each other over WiFi/USB (a MeshRouter
// with a BitChatTransport + a WiFi/USB downstream). Message-oriented: a HELIX frame is a whole
// bitchat message payload — NO length-prefix (bitchat delivers whole messages, unlike a stream).
//
// bitchat-core does its own BLE multi-hop relay + Noise link encryption; HELIX rides on top
// (AEAD frame inside). BLE carries Track A (agents/chat) + control-plane, NOT heavy Track B.

package com.example.helix.bitchat

import com.bitchat.core.api.BitchatCore
import com.bitchat.android.model.BitchatMessage

class BitChatTransport(
    private val core: BitchatCore,
    private val nodeId: String,
) {
    private var onFrame: ((ByteArray) -> Unit)? = null
    // HELIX node_id <-> bitchat peerID directory (dst/src are sealed in the frame, so the
    // transport learns the mapping from a lightweight presence broadcast, see README).
    private val peerOf = HashMap<String, String>()   // helix nodeId -> bitchat peerID

    init {
        core.listener = object : BitchatCore.Listener {
            override fun onMessage(message: BitchatMessage) {
                // content carries the HELIX frame bytes; senderPeerID is bitchat-level addressing
                message.senderPeerID?.let { /* directory hints can be learned here */ }
                onFrame?.invoke(message.content.toByteArray(Charsets.ISO_8859_1))
            }
        }
    }

    fun onFrame(handler: (ByteArray) -> Unit) { onFrame = handler }

    fun broadcast(frame: ByteArray) {
        core.sendBroadcast(String(frame, Charsets.ISO_8859_1))   // raw bytes as message payload
    }

    fun send(nodeId: String, frame: ByteArray) {
        val peer = peerOf[nodeId]
        if (peer != null) core.sendPrivate(String(frame, Charsets.ISO_8859_1), peer)
        else broadcast(frame)                                    // unmapped -> flood (mesh)
    }

    fun peers(): Array<String> = core.getPeerNicknames().keys.toTypedArray()

    fun mapPeer(helixNodeId: String, peerId: String) { peerOf[helixNodeId] = peerId }
}
