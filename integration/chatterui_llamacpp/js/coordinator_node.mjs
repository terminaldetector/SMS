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
// How often the host is expected to poll agents(); a longer gap is time it could not listen at all.
const LIVENESS_TICK_MS = 1000

/** Highest-scoring candidate; ties keep the earliest, so a decision is stable, not random. */
function bestVote(votes) {
  let best = votes[0]
  for (const v of votes) if (v[1] > best[1]) best = v
  return best[0]
}

export class Coordinator {
  constructor(nodeId, clusterSecret, { agentTimeoutMs = AGENT_TIMEOUT_MS } = {}) {
    this.nodeId = nodeId
    this.codec = FrameCodec.fromClusterSecret(nodeId, Buffer.from(clusterSecret))
    this.server = null
    this.conns = new Map()  // agentId -> { conn, lastSeen }
    this._coll = new Map()  // tid -> { results:{}, votes:{} }
    this._agentTimeoutMs = agentTimeoutMs
    this._rr = -1        // round-robin cursor for `single`
    this._lastTick = 0   // when agents() last ran, to tell a blocked thread from a quiet agent
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
        // mem/rpc are what Level 3 sharding places layers by (helixPlacement.ts); rpc is absent
        // for an agent that only answers prompts and isn't offering to hold layers.
        this.conns.set(msg.src, {
          conn, lastSeen: Date.now(),
          mem: Number(msg.body.mem ?? 0), rpc: String(msg.body.rpc ?? ''),
        })
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
    // Silence only means "gone" if we were listening. On a phone, loading a sharded model blocks
    // the JS thread for many seconds and no announce can be read however healthily it is sent —
    // pruning on wall-clock alone dropped every worker at exactly the moment a shard was set up.
    // Whatever this tick spent beyond the normal cadence was our deafness, not their silence.
    const now = Date.now()
    const gap = this._lastTick ? now - this._lastTick : 0
    this._lastTick = now
    const cutoff = now - this._agentTimeoutMs - Math.max(0, gap - LIVENESS_TICK_MS)
    for (const [id, a] of this.conns) {
      if (a.lastSeen >= cutoff) continue
      this.conns.delete(id)  // gone quiet — drop it so routing stays truthful
      try { a.conn.close() } catch {}
    }
    return [...this.conns.keys()]
  }

  // Live agents plus what sharding needs to place layers on them (mirrors helixCoordinator.ts).
  agentInfo() {
    return this.agents().map((id) => {
      const a = this.conns.get(id)
      return { id, mem: a.mem, rpc: a.rpc }
    })
  }

  async infer(prompt, mode = 'single', { timeoutMs = 8000 } = {}) {
    const live = this.agents()
    if (!live.length) throw new Error('no agent joined yet')

    // Which phones get the task is the whole difference between the modes, and it used to be
    // neither: everything went to agents()[0]. Extra phones sat idle, and "voting" waited on votes
    // from agents that had never been asked.
    let targets
    if (mode === 'voting') {
      targets = live                          // everyone, so there is something to decide between
    } else {
      this._rr = (this._rr + 1) % live.length // round-robin, so several agents share the load
      targets = [live[this._rr]]
    }

    const tid = 't' + Math.random().toString(36).slice(2, 10)
    this._coll.set(tid, { results: {}, votes: {} })
    const sealed = this.codec.seal(Msg.TASK, { mode, prompt }, tid)
    for (const id of targets) {
      try { this.conns.get(id)?.conn.send(sealed) } catch {}
    }

    const deadline = Date.now() + timeoutMs
    try {
      while (Date.now() < deadline) {
        const c = this._coll.get(tid)
        if (mode === 'voting') {
          const v = Object.values(c.votes)
          if (v.length >= targets.length) return bestVote(v)
        } else if (Object.keys(c.results).length) {
          return Object.values(c.results)[0]
        }
        await new Promise((r) => setTimeout(r, 10))
      }
      // A partial vote still decides between real candidates — better than throwing away answers
      // that did arrive because one phone never replied.
      const partial = Object.values(this._coll.get(tid)?.votes ?? {})
      if (mode === 'voting' && partial.length) return bestVote(partial)
      const any = Object.values(this._coll.get(tid)?.results ?? {})
      if (any.length) return any[0]
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
