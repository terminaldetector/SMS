// Conformance for the BitChat wire codec (ChatterUI/lib/bitchatCodec.ts).
//
//   node integration/chatterui_llamacpp/js/bitchat_codec_smoke.mjs
//
// Real interop means an UNMODIFIED BitChat app has to parse what we emit, so this checks the bytes
// against BinaryProtocol.swift's layout directly — field by field, at fixed offsets — rather than
// only round-tripping through our own encoder, which would happily agree with its own mistakes.
// Layout, constants and offsets: see integration/chatterui_llamacpp/BITCHAT_BRIDGE.md.

import {
  CHARACTERISTIC_UUID, Flags, MsgType, NoisePayloadType, SERVICE_UUID,
  SENDER_ID_SIZE, SIGNATURE_SIZE, V1_HEADER_SIZE, V2_HEADER_SIZE,
  decodePacket, encodeForSigning, encodePacket, optimalBlockSize, pad, unpad,
} from '../../../ChatterUI/lib/bitchatCodec.ts'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }
const eq = (a, b) => a.length === b.length && a.every((x, i) => x === b[i])
const bytes = (...n) => Uint8Array.from(n)
const seq = (n, from = 0) => Uint8Array.from({ length: n }, (_, i) => (from + i) & 0xff)

// --- constants must match BitChat's source exactly; a typo here is silent non-interop ---
check(SERVICE_UUID === 'F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C', 'mainnet service UUID matches BitChat')
check(CHARACTERISTIC_UUID === 'A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D', 'characteristic UUID matches BitChat')
check(MsgType.announce === 0x01 && MsgType.message === 0x02 && MsgType.leave === 0x03,
  'core message types 0x01/0x02/0x03')
check(MsgType.noiseHandshake === 0x10 && MsgType.noiseEncrypted === 0x11 && MsgType.fragment === 0x20,
  'noise + fragment types 0x10/0x11/0x20')
check(NoisePayloadType.privateMessage === 0x01 && NoisePayloadType.delivered === 0x03,
  'noise payload types 0x01/0x03')
check(Flags.hasRecipient === 0x01 && Flags.hasSignature === 0x02 && Flags.isCompressed === 0x04 &&
  Flags.hasRoute === 0x08 && Flags.isRSR === 0x10, 'flag bits 0x01/0x02/0x04/0x08/0x10')

// --- exact byte layout of a minimal v1 packet ---
const ts = 0x0102030405060708n
const p1 = {
  version: 1, type: MsgType.message, ttl: 7, timestamp: ts,
  senderID: seq(8, 0xa0), payload: bytes(0xde, 0xad, 0xbe, 0xef),
}
const raw = encodePacket(p1, { padding: false })
check(raw[0] === 1, 'byte 0 = version')
check(raw[1] === MsgType.message, 'byte 1 = type')
check(raw[2] === 7, 'byte 2 = ttl')
check(eq(raw.subarray(3, 11), bytes(1, 2, 3, 4, 5, 6, 7, 8)), 'bytes 3..10 = timestamp, big-endian')
check(raw[11] === 0, 'byte 11 = flags (offset 11, as BinaryProtocol.Offsets.flags)')
check(raw[12] === 0 && raw[13] === 4, 'bytes 12..13 = payloadLength, 16-bit big-endian for v1')
check(V1_HEADER_SIZE === 14 && raw.length === V1_HEADER_SIZE + 8 + 4, 'v1 header is 14 bytes, then senderID + payload')
check(eq(raw.subarray(14, 22), seq(8, 0xa0)), 'senderID follows the header, 8 bytes')

// --- flags drive optional fields, in the documented order ---
const withAll = encodePacket({
  ...p1, recipientID: seq(8, 0xb0), signature: seq(64, 1),
}, { padding: false })
check(withAll[11] === (Flags.hasRecipient | Flags.hasSignature), 'flags set for recipient + signature')
check(eq(withAll.subarray(22, 30), seq(8, 0xb0)), 'recipientID sits right after senderID')
check(eq(withAll.subarray(withAll.length - SIGNATURE_SIZE), seq(64, 1)), 'signature is the last 64 bytes')

// --- v2: wider length field, and a source route that is NOT counted in payloadLength ---
const v2 = encodePacket({
  ...p1, version: 2, route: [seq(8, 0xc0), seq(8, 0xd0)],
}, { padding: false })
check(V2_HEADER_SIZE === 16, 'v2 header is 16 bytes')
check(eq(v2.subarray(12, 16), bytes(0, 0, 0, 4)), 'v2 payloadLength is 32-bit big-endian')
check((v2[11] & Flags.hasRoute) !== 0, 'hasRoute flag set on v2')
check(v2[24] === 2, 'route begins with a hop count')
check(v2.length === V2_HEADER_SIZE + 8 + 1 + 16 + 4, 'route bytes are extra, not folded into payloadLength')

// hasRoute is meaningless on v1 — it must be dropped, not mis-encoded
const v1Route = encodePacket({ ...p1, route: [seq(8, 0xc0)] }, { padding: false })
check((v1Route[11] & Flags.hasRoute) === 0 && v1Route.length === raw.length, 'route is ignored on v1')

// --- ids are forced to exactly 8 bytes, as Swift does with prefix/zero-fill ---
const shortId = encodePacket({ ...p1, senderID: bytes(1, 2, 3) }, { padding: false })
check(eq(shortId.subarray(14, 22), bytes(1, 2, 3, 0, 0, 0, 0, 0)), 'short senderID is zero-padded to 8')
const longId = encodePacket({ ...p1, senderID: seq(12, 1) }, { padding: false })
check(eq(longId.subarray(14, 22), seq(8, 1)), 'long senderID is truncated to 8')

// --- padding: PKCS#7 to a block size, chosen with 16 bytes of AEAD headroom ---
check(optimalBlockSize(100) === 256 && optimalBlockSize(240) === 256 && optimalBlockSize(241) === 512,
  'block size accounts for a 16-byte tag (241+16 > 256)')
check(optimalBlockSize(5000) === 5000, 'oversized data is left unpadded (it gets fragmented)')
const padded = pad(bytes(1, 2, 3), 8)
check(eq(padded, bytes(1, 2, 3, 5, 5, 5, 5, 5)), 'PKCS#7 pad bytes equal the pad length')
check(eq(unpad(padded), bytes(1, 2, 3)), 'unpad reverses it')
check(eq(unpad(bytes(1, 2, 9)), bytes(1, 2, 9)), 'invalid padding is left alone, not corrupted')
const bigPad = pad(seq(300), 8)
check(bigPad.length === 300, 'no padding when data already exceeds the target')

// --- decode: round-trips, padded and not ---
for (const [name, packet] of [
  ['minimal v1', p1],
  ['v1 + recipient + signature', { ...p1, recipientID: seq(8, 0xb0), signature: seq(64, 3) }],
  ['v2 + route', { ...p1, version: 2, route: [seq(8, 0xc0), seq(8, 0xd0)] }],
  ['empty payload', { ...p1, payload: new Uint8Array(0) }],
  ['RSR flag', { ...p1, isRSR: true }],
]) {
  const wire = encodePacket(packet) // padded, as it goes on air
  const back = decodePacket(wire)
  check(!!back, `${name}: decodes from padded bytes`)
  check(back.version === packet.version && back.type === packet.type && back.ttl === packet.ttl,
    `${name}: version/type/ttl survive`)
  check(back.timestamp === packet.timestamp, `${name}: 64-bit timestamp survives exactly`)
  check(eq(back.payload, packet.payload), `${name}: payload survives`)
  check(eq(back.senderID, packet.senderID), `${name}: senderID survives`)
  check(!!back.recipientID === !!packet.recipientID, `${name}: recipient presence survives`)
  check(!!back.signature === !!packet.signature, `${name}: signature presence survives`)
  check(!!back.isRSR === !!packet.isRSR, `${name}: RSR flag survives`)
  if (packet.route)
    check(back.route?.length === packet.route.length && eq(back.route[1], packet.route[1]),
      `${name}: route hops survive`)
}

// A timestamp that a double could not hold exactly — the reason the codec uses bigint. Getting this
// wrong would corrupt the bytes that signatures are computed over.
const huge = { ...p1, timestamp: 0xfffffffffffffffn }
check(decodePacket(encodePacket(huge)).timestamp === huge.timestamp, 'timestamps beyond 2^53 round-trip exactly')

// --- signing bytes: no signature, TTL zeroed, RSR cleared (relays mutate both) ---
const signed = { ...p1, ttl: 7, signature: seq(64, 9), isRSR: true }
const forSig = encodeForSigning(signed)
check(forSig[2] === 0, 'signing bytes force TTL to 0 so relays can decrement it')
check((forSig[11] & Flags.hasSignature) === 0, 'signing bytes exclude the signature itself')
check((forSig[11] & Flags.isRSR) === 0, 'signing bytes clear the mutable RSR flag')
check(eq(encodeForSigning({ ...signed, ttl: 1 }), forSig), 'a relayed packet signs to the same bytes')

// --- decoder rejects malformed input instead of returning half a packet ---
check(decodePacket(bytes(3, 1, 1)) === null, 'rejects an unknown version')
check(decodePacket(new Uint8Array(10)) === null, 'rejects a truncated header')
const truncated = encodePacket(p1, { padding: false }).subarray(0, 20)
check(decodePacket(truncated) === null, 'rejects a packet cut short of its payload')
const liar = encodePacket(p1, { padding: false }).slice()
liar[13] = 0xff // claim far more payload than is present
check(decodePacket(liar) === null, 'rejects a payloadLength that overruns the buffer')

console.log(`\nALL PASSED (${pass} checks) — BitChat packet codec matches the reference wire format.`)
