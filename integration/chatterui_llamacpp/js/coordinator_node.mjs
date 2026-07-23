// Minimal in-app HELIX coordinator — the SERVER role a ChatterUI host phone runs so another
// ChatterUI phone (agent) joins directly, NO PC. Accepts a WebSocket agent (ws_server.mjs), learns
// it from AGENT_ANNOUNCE, and routes a prompt to it (single / voting). Node `net` here; the app runs
// the same logic over react-native-tcp-socket (helixCoordinator.ts). Uses the proven FrameCodec.

import net from 'node:net'
import { FrameCodec, Msg } from './frame_codec.mjs'
import { WsServerConnection } from './ws_server.mjs'

const td = new TextDecoder()

export class Coordinator {
  constructor(nodeId, clusterSecret) {
    this.nodeId = nodeId
    this.codec = FrameCodec.fromClusterSecret(nodeId, Buffer.from(clusterSecret))
    this.server = null
    this.conns = new Map()  // agentId -> WsServerConnection
    this._coll = new Map()  // tid -> { results:{}, votes:{} }
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
      if (msg.type === Msg.AGENT_ANNOUNCE) {
        this.conns.set(msg.src, conn)
      } else if (msg.type === Msg.RESULT) {
        const c = this._coll.get(msg.tid); if (c) c.results[msg.src] = String(msg.body.text ?? '')
      } else if (msg.type === Msg.VOTE) {
        const c = this._coll.get(msg.tid)
        if (c) c.votes[msg.src] = [String(msg.body.candidate ?? ''), Number(msg.body.score ?? 0)]
      }
    })
    conn.onClose(() => {
      for (const [id, cn] of this.conns) if (cn === conn) this.conns.delete(id)
    })
  }

  agents() { return [...this.conns.keys()] }

  async infer(prompt, mode = 'single', { timeoutMs = 8000 } = {}) {
    const agent = this.agents()[0]
    if (!agent) throw new Error('no agent joined yet')
    const tid = 't' + Math.random().toString(36).slice(2, 10)
    this._coll.set(tid, { results: {}, votes: {} })
    this.conns.get(agent).send(this.codec.seal(Msg.TASK, { mode, prompt }, tid))
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
    for (const cn of this.conns.values()) cn.close()
  }
}
