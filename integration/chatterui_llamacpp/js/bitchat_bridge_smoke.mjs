// End-to-end proof of the BitChat -> HELIX bridge, with no radio involved.
//
//   node integration/chatterui_llamacpp/js/bitchat_bridge_smoke.mjs
//
// Two bridges are wired to each other through an in-memory link that behaves like BLE does — it
// only moves bytes, and it can be told to cap them so real fragmentation happens. One side plays a
// BitChat user, the other answers with a stand-in for the mesh's model. Everything that runs here
// is the same code the phone runs; only the transport is substituted.

import {
  BitchatBridge, decodeMessage, encodeMessage, hex, peerIdFromPublicKey,
} from '../../../ChatterUI/lib/bitchatBridge.ts'
import { generateStaticKey } from '../../../ChatterUI/lib/bitchatNoise.ts'
import { sha256 } from '@noble/hashes/sha2'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }
const eq = (a, b) => a.length === b.length && a.every((x, i) => x === b[i])

// --- peer identity ---
{
  const key = generateStaticKey()
  const id = peerIdFromPublicKey(key.pub)
  check(id.length === 8, 'a peer id is 8 bytes')
  check(eq(id, sha256(key.pub).slice(0, 8)),
    'peer id = first 8 bytes of SHA-256 over the Noise static key, as BitChat derives it')
}

// --- message payload ---
{
  const msg = {
    id: 'abc123', sender: 'alice', content: 'hello there', timestamp: 1_700_000_000_000,
    isPrivate: true, senderPeerID: 'deadbeefdeadbeef',
  }
  const wire = encodeMessage(msg)
  check(wire[0] === 0x02 + 0x10, 'flags carry isPrivate (0x02) and hasSenderPeerID (0x10)')
  const view = new DataView(wire.buffer, wire.byteOffset)
  check(Number(view.getBigUint64(1, false)) === msg.timestamp, 'timestamp is 8 bytes, big-endian, in ms')
  const back = decodeMessage(wire)
  check(back.id === msg.id && back.sender === msg.sender && back.content === msg.content,
    'id/sender/content round-trip')
  check(back.timestamp === msg.timestamp && back.isPrivate === true, 'timestamp and privacy flag round-trip')
  check(back.senderPeerID === msg.senderPeerID, 'optional senderPeerID round-trips')

  const minimal = decodeMessage(encodeMessage({ id: 'x', sender: 's', content: '', timestamp: 1 }))
  check(minimal.content === '' && !minimal.senderPeerID, 'a message with no optional fields decodes')
  check(decodeMessage(new Uint8Array(3)) === null, 'a truncated message payload is refused')
  const unicode = decodeMessage(encodeMessage({ id: 'i', sender: 'бот', content: 'привет 👋', timestamp: 2 }))
  check(unicode.content === 'привет 👋' && unicode.sender === 'бот', 'non-ASCII survives as UTF-8')
}

// A link that behaves like BLE: bytes in, bytes out, optionally capped per write.
function makeLink(maxWrite = Infinity) {
  const ends = {}
  const mk = (me, other) => ({
    async send(_peerId, data) {
      if (data.length > maxWrite) return false // the transport would reject an oversized write
      queueMicrotask(() => ends[other].deliver(data))
      return true
    },
  })
  return {
    transportFor: (me, other) => mk(me, other),
    register: (name, bridge) => { ends[name] = { deliver: (d) => bridge.onData('link', d) } },
  }
}

async function settle() {
  for (let i = 0; i < 50; i++) await new Promise((r) => setImmediate(r))
}

// --- handshake + a private message answered by the "model" ---
{
  const link = makeLink()
  const userKey = generateStaticKey()
  const helixKey = generateStaticKey()

  const asked = []
  const user = new BitchatBridge(userKey, link.transportFor('user', 'helix'),
    async () => 'the user does not answer prompts', { nickname: 'alice' })
  const helix = new BitchatBridge(helixKey, link.transportFor('helix', 'user'),
    async (prompt, from) => { asked.push([prompt, from]); return `echo: ${prompt}` },
    { nickname: 'helix' })
  link.register('user', user)
  link.register('helix', helix)

  // Answers come back to the user side, so the user's own responder is what observes them.
  const replies = []
  const userSeen = new BitchatBridge(userKey, link.transportFor('user', 'helix'),
    async (prompt) => { replies.push(prompt); return '' }, { nickname: 'alice' })
  link.register('user', userSeen)

  await userSeen.startHandshake('link', helix.peerID)
  await settle()
  check(userSeen.hasSession(helix.peerID), 'initiator ends up with a session')
  check(helix.hasSession(userSeen.peerID), 'responder ends up with a session')

  await userSeen.sendPrivate('link', helix.peerID, 'what is 2+2?')
  await settle()
  check(asked.length === 1 && asked[0][0] === 'what is 2+2?',
    'the private message reached the responder as a prompt')
  check(asked[0][1] === 'alice', 'the prompt carries the sender nickname')
  check(replies.length === 1 && replies[0] === 'echo: what is 2+2?',
    'the answer came back to the user over the same session')

  // A second exchange must work on the established session (nonces advance, no re-handshake).
  await userSeen.sendPrivate('link', helix.peerID, 'and again')
  await settle()
  check(asked.length === 2 && replies[1] === 'echo: and again', 'further messages reuse the session')
}

// --- an answer too big for one BLE write must fragment and reassemble ---
{
  const link = makeLink(512)
  const userKey = generateStaticKey()
  const helixKey = generateStaticKey()
  const long = 'x'.repeat(4000)

  const replies = []
  const user = new BitchatBridge(userKey, link.transportFor('user', 'helix'),
    async (p) => { replies.push(p); return '' }, { nickname: 'alice', chunkSize: 400 })
  const helix = new BitchatBridge(helixKey, link.transportFor('helix', 'user'),
    async () => long, { nickname: 'helix', chunkSize: 400 })
  link.register('user', user)
  link.register('helix', helix)

  await user.startHandshake('link', helix.peerID)
  await settle()
  await user.sendPrivate('link', helix.peerID, 'give me a long answer')
  await settle()
  check(replies.length === 1, 'a fragmented answer arrives as exactly one message')
  check(replies[0] === long, `all ${long.length} characters survived fragmentation and reassembly`)
}

// --- robustness ---
{
  const link = makeLink()
  const helixKey = generateStaticKey()
  const strangerKey = generateStaticKey()
  const helix = new BitchatBridge(helixKey, link.transportFor('helix', 'user'), async () => 'hi')
  const stranger = new BitchatBridge(strangerKey, link.transportFor('user', 'helix'), async () => '')
  link.register('helix', helix)
  link.register('user', stranger)

  // Garbage and encrypted-before-handshake must be ignored, not throw.
  await helix.onData('link', new Uint8Array([1, 2, 3]))
  await helix.onData('link', new Uint8Array(64))
  check(true, 'malformed input is ignored rather than throwing')
  check(!helix.hasSession(stranger.peerID), 'no session is created by garbage')

  // A packet addressed to somebody else is not ours to answer.
  let answered = false
  const other = new BitchatBridge(generateStaticKey(), { send: async () => { answered = true; return true } },
    async () => 'should not run')
  await other.onData('link', new Uint8Array(200))
  check(!answered, 'a packet for a different recipient produces no reply')
}

console.log(`\nALL PASSED (${pass} checks) — a BitChat private message reaches the model and the answer returns.`)
