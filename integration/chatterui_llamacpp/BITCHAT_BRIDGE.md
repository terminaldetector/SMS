# BitChat ↔ HELIX bridge — protocol notes and plan

Goal: a BitChat user talks to the mesh's model from an **unmodified BitChat app**. That means real
wire compatibility with BitChat's BLE protocol, not a BitChat-inspired transport of our own.

Everything below was read from BitChat's own source (`permissionlesstech/bitchat`, `main`) — the
whitepaper describes the architecture but deliberately omits the constants, so it is not enough to
implement against.

## Confirmed constants

**BLE UUIDs** (`bitchat/Services/BLE/BLEService.swift`)

| | UUID |
|---|---|
| Service (mainnet) | `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C` |
| Service (testnet) | `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A` |
| Characteristic | `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D` |

**Packet header** (`localPackages/BitFoundation/Sources/BitFoundation/BinaryProtocol.swift`),
big-endian throughout:

```
version(1) | type(1) | ttl(1) | timestamp(8) | flags(1) | payloadLength(2 for v1, 4 for v2)
senderID(8) | [recipientID(8) if flags & 0x01] | [route if v2] | payload | [signature(64) if flags & 0x02]
```

- `flags`: `hasRecipient = 0x01`, `hasSignature = 0x02`, `isCompressed = 0x04`
- when compressed, the payload is prefixed by the original size (2 bytes v1 / 4 bytes v2)
- `senderIDSize = 8`, `recipientIDSize = 8`, `signatureSize = 64`
- v2 adds an optional source route: `hopCount(1) | hop(8) × hopCount`, not counted in payloadLength

**Message types** (`MessageType.swift`)

| Value | Name | | Value | Name |
|---|---|---|---|---|
| `0x01` | announce | | `0x22` | fileTransfer |
| `0x02` | message | | `0x23` | boardPost |
| `0x03` | leave | | `0x24` | prekeyBundle |
| `0x04` | courierEnvelope | | `0x25` | groupMessage |
| `0x10` | noiseHandshake | | `0x26` | ping |
| `0x11` | noiseEncrypted | | `0x27` | pong |
| `0x20` | fragment | | `0x28` | nostrCarrier |
| `0x21` | requestSync | | `0x29` | voiceFrame |

**Noise payload types** — first byte inside a decrypted `noiseEncrypted` payload
(`BitchatProtocol.swift`): `0x01` privateMessage, `0x02` readReceipt, `0x03` delivered,
`0x06` groupInvite, `0x07` groupKeyUpdate, `0x08` voiceFrame, `0x10` verifyChallenge,
`0x11` verifyResponse, `0x12` vouch.

**Crypto**: Noise `XX` (Curve25519 / ChaCha20-Poly1305 / SHA-256) for connected peers, one-way `X`
for offline seals; Ed25519 packet signatures. Signatures **exclude the TTL byte** so relays can
decrement it without invalidating them.

**Fragmentation**: ~469-byte fragments, 8-byte fragment ID plus index/total; reassembly is capped at
128 concurrent assemblies, 30 s timeout, 1 MiB.

**TTL**: originates at 7; relays clamp — dense graphs (≥6 links) cap broadcast TTL at 5, thin chains
(≤2 links) relay at the full incoming depth.

**Padding**: payloads padded to 256/512/1024/2048-byte blocks to hide length.

## The blocker: no usable React Native peripheral BLE

A BitChat node both **advertises + serves a GATT characteristic** (peripheral) and **scans +
connects** (central). Peers write packets to the characteristic. Half of that has no maintained RN
implementation:

| Package | Version | Last publish | Role |
|---|---|---|---|
| `react-native-ble-plx` | 3.5.1 | 2026-02 | central only |
| `react-native-ble-manager` | 12.5.1 | 2026-07 | central only |
| `react-native-ble-advertiser` | 0.0.17 | **2022** | advertising only, no GATT server |
| `react-native-peripheral` | 0.0.3 | **2022** | peripheral, abandoned |

So the peripheral half — `BluetoothLeAdvertiser` + `BluetoothGattServer` — has to be a **hand-written
native module**. The two abandoned options predate the New Architecture, which is exactly the
dependency class that has already cost this project twice (the `react-native-tcp-socket` startup
risk, and the duplicate-React-Native failure that made every native call return null).

## Plan

1. **Protocol codec in TS** (`lib/bitchatCodec.ts`) — packet encode/decode, flags, compression
   framing, fragmentation, padding. Pure logic, so it gets the same treatment as `helixPlacement.ts`:
   proven by a smoke test before any device is involved. Vectors come from BitChat's own
   `bitchatTests/`.
2. **Noise XX + Ed25519** (`lib/bitchatNoise.ts`) — `@noble` already provides Curve25519,
   ChaCha20-Poly1305, SHA-256 and Ed25519, all of which are already ChatterUI dependencies and are
   already proven byte-exact against the HELIX vectors.
3. **Native BLE module** (Kotlin): advertiser + GATT server + central scanner/connector, exposing a
   minimal JS surface (`onPacket`, `send(peer, bytes)`, `onPeerConnect/Disconnect`). Central can lean
   on `react-native-ble-plx`; the peripheral side is ours.
4. **Bridge** — a BitChat private message addressed to this node becomes a HELIX task; the mesh's
   answer goes back as a `privateMessage` over the same Noise session.

Steps 1–2 are verifiable here. Step 3 is verifiable only on real phones, and step 4 only once 3
works — worth being blunt about, because "it compiles" has already proven not to mean "it runs".
