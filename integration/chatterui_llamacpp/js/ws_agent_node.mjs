// HELIX agent over WebSocket — the transport for the ChatterUI *Level 2* agent with NO native
// module. Uses the global WebSocket (built into React Native AND Node 22), so the same code runs
// on-device and in this proof. One binary WS message = one HELIX frame (WebSocket is message-framed,
// so no length prefix). Crypto/worker reuse the proven FrameCodec (helix_codec.mjs / @noble in RN).
//
// Mirrors app_mod/lib/helixAgent.ts (WebSocketAgentTransport). Verified by ./l2_ws_smoke.mjs.

import { FrameCodec, Msg } from './frame_codec.mjs'

const enc = new TextEncoder()

function cardBody(c) {
  return {
    agent_id: c.agent_id, models: c.models ?? [], skills: c.skills ?? [],
    task_types: c.task_types ?? [], mem: c.mem ?? 0, tps: c.tps ?? 0, batt: c.batt ?? 1.0,
  }
}

export class WsAgentNode {
  constructor(nodeId, clusterSecret, runner, { announceIntervalMs = 200 } = {}) {
    this.nodeId = nodeId
    this.codec = FrameCodec.fromClusterSecret(nodeId, Buffer.from(clusterSecret))
    this.runner = runner
    this.card = runner.card()
    this._announceMs = announceIntervalMs
    this.ws = null
    this._timer = null
    this.tasksServed = 0
  }

  connect(url) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url)
      ws.binaryType = 'arraybuffer'
      ws.onopen = () => {
        ws.send(enc.encode(this.nodeId)) // handshake: our id first (one binary message)
        this._timer = setInterval(() => this._announce(), this._announceMs)
        this._announce()
        resolve()
      }
      ws.onmessage = (ev) => this._onMessage(new Uint8Array(ev.data))
      ws.onerror = (e) => reject(e?.error || new Error('websocket error'))
      this.ws = ws
    })
  }

  _send(frame) {
    if (this.ws && this.ws.readyState === 1) this.ws.send(frame) // Buffer -> binary message
  }

  _announce() {
    this._send(this.codec.seal(Msg.AGENT_ANNOUNCE, cardBody(this.card)))
    this._send(this.codec.seal(Msg.STATUS, { busy: false }))
  }

  _onMessage(bytes) {
    const msg = this.codec.open(Buffer.from(bytes))
    if (msg && msg.type === Msg.TASK) this._handleTask(msg)
  }

  _handleTask(msg) {
    const tid = msg.tid
    const mode = String(msg.body.mode ?? 'single')
    const prompt = String(msg.body.prompt ?? '')
    const context = String(msg.body.context ?? '')
    const parts = []
    for (const chunk of this.runner.infer(prompt, context)) {
      parts.push(chunk)
      this._send(this.codec.seal(Msg.PARTIAL, { chunk }, tid))
    }
    const result = parts.join('').trim()
    if (mode === 'voting') {
      this._send(this.codec.seal(Msg.VOTE, { candidate: result, score: this.runner.score(prompt, result) }, tid))
    } else {
      this._send(this.codec.seal(Msg.RESULT, { text: result }, tid))
    }
    this.tasksServed += 1
  }

  close() {
    if (this._timer) { clearInterval(this._timer); this._timer = null }
    if (this.ws) { try { this.ws.close() } catch {} this.ws = null }
  }
}
