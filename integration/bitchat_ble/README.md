# BitChat / BLE — client join via Bluetooth (Track A + control)

BT clients join the mesh over **bitchat-core** (BLE mesh + Noise) and talk to a **server**;
servers relay to each other over **WiFi/USB**. That two-tier shape is a
[`MeshRouter`](../../helix/mesh/router.py) on the server with two downstreams:

```
BT clients ──BLE(bitchat)──►  [ server: MeshRouter{ BitChatTransport, WifiTransport/StreamTransport } ]  ──WiFi/USB──►  other servers
```

## Mapping to the HELIX Transport contract
`BitChatTransport.kt` bridges bitchat-core → the contract (same as WiFi/USB, but
**message-oriented** — a HELIX frame is one bitchat message payload, no length-prefix):

| Contract | bitchat-core |
|---|---|
| `broadcast(frame)` | `sendBroadcast(frame)` |
| `send(nodeId, frame)` | `sendPrivate(frame, peerId)` (via node_id→peerID directory; flood if unmapped) |
| `onFrame(bytes)` | `Listener.onMessage(message.content)` |
| `peers()` | `getPeerNicknames().keys` |

## node_id ↔ peerID directory
HELIX `src`/`dst` are sealed inside the frame, so the transport can't read them. It learns the
`node_id → bitchat peerID` map from a **lightweight presence broadcast** (the same routing
presence as `MeshRouter`), and floods (mesh) when a mapping is unknown. bitchat-core already does
BLE multi-hop relay, so the HELIX router mostly **bridges domains** (BT↔backbone), not BLE routes.

## Scope & security
- **Track A (agents/chat) + control-plane only.** BLE bandwidth/MTU is too small for Track B
  tensor activations (prefill/decode ring) — those go over WiFi/USB.
- **Encryption:** bitchat gives Noise link encryption; HELIX AEAD rides on top (end-to-end).
  Within a fully-trusted bitchat channel you may use HELIX's HMAC-only sealer to avoid double
  encryption — a policy choice, not required.
- Conformance: the HELIX frame inside the payload must still match `helix/spec/vectors.json`.
