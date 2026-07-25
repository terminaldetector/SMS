// Minimal in-app HELIX coordinator — the SERVER role a ChatterUI host phone runs so another
// ChatterUI phone (agent) joins directly, NO PC. Accepts a WebSocket agent (ws_server.mjs), learns
// it from AGENT_ANNOUNCE, and routes a prompt to it (single / voting). Node `net` here; the app runs
// the same logic over react-native-tcp-socket (helixCoordinator.ts). Uses the proven FrameCodec.

import net from 'node:net'
import { FrameCodec, Msg } from './frame_codec.mjs'
import { WsServerConnection } from './ws_server.mjs'

const td = new TextDecoder()

// An agent announces every ~500ms. If a phone drops off the Wi-Fi the TCP connection can sit
// half-open — no 'close' event ever arrives — so silence is what actually tells us it's gone.
// Mirrors AGENT_TIMEOUT_MS in ChatterUI/lib/helixCoordinator.ts.
const AGENT_TIMEOUT_MS = 15000

export class Coordinator {
  constructor(nodeId, clusterSecret, { agentTimeoutMs = AGENT_TIMEOUT_MS } = {}) {
    this.nodeId = nodeId
    this.codec = FrameCodec.fromClusterSecret(nodeId, Buffer.from(clusterSecret))
    this.server = null
    this.conns = new Map()  // agentId -> { conn, lastSeen }
    this._coll = new Map()  // tid -> { results:{}, votes:{} }
    this._agentTimeoutMs = agentTimeoutMs
  }

  listen(port = 8790, host = '0.0.0.0') {
    return new Promise((resolve, reject) => {
      this.server = net.createServer((sock) => this._onSocket(sock))
      this.server.on('error', reject)
      this.server.listen(port, host, () => resolve(this.server.address().port))
    })
  }

  _onSocket(sock) {
    const conn = new WsServerConnection(sock)
    const state = { remoteId: null }
    conn.onBinary((payload) => {
      if (state.remoteId === null) { state.remoteId = td.decode(payload); return } // handshake: agent node id
      const msg = this.codec.open(Buffer.from(payload))
      if (!msg) return
      const known = this.conns.get(msg.src)
      if (known) known.lastSeen = Date.now()  // any traffic counts as liveness
      if (msg.type === Msg.AGENT_ANNOUNCE) {
        this.conns.set(msg.src, { conn, lastSeen: Date.now() })
      } else if (msg.type === Msg.RESULT) {
        const c = this._coll.get(msg.tid); if (c) c.results[msg.src] = String(msg.body.text ?? '')
      } else if (msg.type === Msg.VOTE) {
        const c = this._coll.get(msg.tid)
        if (c) c.votes[msg.src] = [String(msg.body.candidate ?? ''), Number(msg.body.score ?? 0)]
      }
    })
    conn.onClose(() => {
      for (const [id, a] of this.conns) if (a.conn === conn) this.conns.delete(id)
    })
  }

  agents() {
    const cutoff = Date.now() - this._agentTimeoutMs
    for (const [id, a] of this.conns) {
      if (a.lastSeen >= cutoff) continue
      this.conns.delete(id)  // gone quiet — drop it so routing stays truthful
      try { a.conn.close() } catch {}
    }
    return [...this.conns.keys()]
  }

  async infer(prompt, mode = 'single', { timeoutMs = 8000 } = {}) {
    const agent = this.agents()[0]
    if (!agent) throw new Error('no agent joined yet')
    const tid = 't' + Math.random().toString(36).slice(2, 10)
    this._coll.set(tid, { results: {}, votes: {} })
    this.conns.get(agent).conn.send(this.codec.seal(Msg.TASK, { mode, prompt }, tid))
    const deadline = Date.now() + timeoutMs
    try {
      while (Date.now() < deadline) {
        const c = this._coll.get(tid)
        if (mode === 'voting') {
          const v = Object.values(c.votes)
          if (v.length) return v[0][0] // single-agent: its candidate is the decision
        } else if (Object.keys(c.results).length) {
          return Object.values(c.results)[0]
        }
        await new Promise((r) => setTimeout(r, 10))
      }
      throw new Error('infer timeout (agent did not answer)')
    } finally {
      this._coll.delete(tid)
    }
  }

  close() {
    this.server?.close()
    for (const a of this.conns.values()) a.conn.close()
  }
}
