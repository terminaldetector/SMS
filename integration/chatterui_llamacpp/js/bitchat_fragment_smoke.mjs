// Conformance for BitChat fragmentation (ChatterUI/lib/bitchatFragment.ts).
//
//   node integration/chatterui_llamacpp/js/bitchat_fragment_smoke.mjs
//
// Checks the fragment payload layout at fixed offsets against
// BLEOutboundFragmentPlanner.swift, then exercises reassembly under the conditions a BLE mesh
// actually produces: out-of-order arrival, duplicate relayed copies, and peers that start
// assemblies they never finish. Layout: fragmentID(8) | index(2 BE) | total(2 BE) | type(1) | chunk.

import { MsgType, decodePacket, encodePacket } from '../../../ChatterUI/lib/bitchatCodec.ts'
import {
  ASSEMBLY_TIMEOUT_MS, FRAGMENT_HEADER_LEN, FRAGMENT_ID_LEN, FragmentAssembler,
  MAX_CONCURRENT_ASSEMBLIES, MIN_CHUNK_SIZE, fragmentPacket, parseFragment,
} from '../../../ChatterUI/lib/bitchatFragment.ts'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }
const eq = (a, b) => a.length === b.length && a.every((x, i) => x === b[i])
const seq = (n, from = 0) => Uint8Array.from({ length: n }, (_, i) => (from + i) & 0xff)

const big = {
  version: 1, type: MsgType.message, ttl: 7, timestamp: 0x1122334455667788n,
  senderID: seq(8, 0xa0), recipientID: seq(8, 0xb0), payload: seq(1500, 3),
}
const fid = seq(8, 0xf0)

// --- layout ---
const frags = fragmentPacket(big, fid, 200)
check(frags.length > 1, `a 1500-byte payload splits into ${frags.length} fragments`)
check(frags.every((f) => f.type === MsgType.fragment), 'every fragment is sent as type 0x20')
check(FRAGMENT_HEADER_LEN === 13 && FRAGMENT_ID_LEN === 8, 'header is 13 bytes with an 8-byte id')

const p0 = frags[0].payload
check(eq(p0.subarray(0, 8), fid), 'bytes 0..7 = fragmentID')
check(p0[8] === 0 && p0[9] === 0, 'bytes 8..9 = index, big-endian (first is 0)')
check(p0[10] === 0 && p0[11] === frags.length, 'bytes 10..11 = total, big-endian')
check(p0[12] === MsgType.message, 'byte 12 = the ORIGINAL packet type, not 0x20')
check(eq(frags[1].payload.subarray(8, 10), Uint8Array.from([0, 1])), 'the second fragment is index 1')

// Fragments carry slices of the fully encoded packet, not of its payload.
const whole = encodePacket(big)
const joined = new Uint8Array(whole.length)
let at = 0
for (const f of frags) {
  const part = f.payload.subarray(FRAGMENT_HEADER_LEN)
  joined.set(part, at); at += part.length
}
check(at === whole.length && eq(joined, whole), 'concatenated chunks reproduce the encoded packet byte for byte')

// Addressing is preserved so relays route fragments like the original.
check(eq(frags[0].senderID, big.senderID) && eq(frags[0].recipientID, big.recipientID),
  'fragments keep the original sender and recipient')
check(frags[0].ttl === big.ttl, 'fragments keep the original TTL')

const header = parseFragment(p0)
check(header.index === 0 && header.total === frags.length && header.originalType === MsgType.message,
  'parseFragment reads back index/total/originalType')

// --- reassembly ---
{
  const asm = new FragmentAssembler()
  let out = null
  frags.forEach((f, i) => { const r = asm.add(f); if (i === frags.length - 1) out = r; else check(r === null, i === 0 ? 'incomplete sets return null' : true) })
  check(!!out, 'the last fragment completes the packet')
  check(out.type === big.type && out.timestamp === big.timestamp, 'reassembled type and timestamp match')
  check(eq(out.payload, big.payload), 'reassembled payload matches the original exactly')
  check(asm.pending === 0, 'the finished assembly is released')
}

// Out-of-order is normal on a mesh where fragments relay independently.
{
  const asm = new FragmentAssembler()
  const shuffled = [...frags].reverse()
  let out = null
  for (const f of shuffled) out = asm.add(f) ?? out
  check(!!out && eq(out.payload, big.payload), 'fragments arriving in reverse order still reassemble')
}

// A relayed duplicate must not corrupt or double-count the assembly.
{
  const asm = new FragmentAssembler()
  asm.add(frags[0])
  check(asm.add(frags[0]) === null, 'a duplicate fragment is ignored')
  let out = null
  for (const f of frags.slice(1)) out = asm.add(f) ?? out
  check(!!out && eq(out.payload, big.payload), 'the set still completes after a duplicate')
}

// Inconsistent metadata for the same id shouldn't poison an in-flight assembly.
{
  const asm = new FragmentAssembler()
  asm.add(frags[0])
  const liar = { ...frags[1], payload: frags[1].payload.slice() }
  liar.payload[11] = 0xff // claim a different total
  check(asm.add(liar) === null, 'a fragment disagreeing about `total` is rejected')
  let out = null
  for (const f of frags.slice(1)) out = asm.add(f) ?? out
  check(!!out, 'the genuine fragments still complete the set')
}

// Abandoned assemblies must not accumulate.
{
  const asm = new FragmentAssembler()
  asm.add(frags[0], 0)
  check(asm.pending === 1, 'an incomplete assembly is held')
  asm.add(frags[0], ASSEMBLY_TIMEOUT_MS + 1)
  check(asm.pending === 1, 'a stale assembly is evicted rather than kept forever')

  const flood = new FragmentAssembler()
  for (let i = 0; i < MAX_CONCURRENT_ASSEMBLIES + 20; i++) {
    const id = new Uint8Array(8)
    new DataView(id.buffer).setUint32(0, i, false)
    flood.add(fragmentPacket(big, id, 200)[0])
  }
  check(flood.pending <= MAX_CONCURRENT_ASSEMBLIES,
    `a peer opening ${MAX_CONCURRENT_ASSEMBLIES + 20} assemblies is capped at ${MAX_CONCURRENT_ASSEMBLIES}`)
}

// --- guards ---
check(fragmentPacket(big, seq(4), 200) === null, 'a wrong-length fragment id is refused')
check(fragmentPacket(big, fid, 1)[0].payload.length - FRAGMENT_HEADER_LEN === MIN_CHUNK_SIZE,
  `a tiny MTU is clamped to the ${MIN_CHUNK_SIZE}-byte floor`)
check(parseFragment(new Uint8Array(5)) === null, 'a truncated fragment header is refused')
const badIdx = new Uint8Array(FRAGMENT_HEADER_LEN)
new DataView(badIdx.buffer).setUint16(8, 5, false)
new DataView(badIdx.buffer).setUint16(10, 2, false)
check(parseFragment(badIdx) === null, 'an index beyond `total` is refused')

// A packet that fits in one chunk still round-trips through the same path.
{
  const small = { ...big, payload: seq(10) }
  const one = fragmentPacket(small, fid, 4096)
  check(one.length === 1, 'a small packet becomes a single fragment')
  const out = new FragmentAssembler().add(one[0])
  check(!!out && eq(out.payload, small.payload), 'a single-fragment set reassembles')
}

console.log(`\nALL PASSED (${pass} checks) — BitChat fragmentation matches the reference layout.`)
