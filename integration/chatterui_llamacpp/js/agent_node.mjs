// HELIX AgentNode — JS runtime for the ChatterUI *Level 2* agent (helix/agent/node.py worker half).
//
// A JS device joins a HELIX Track-A mesh: it announces an AgentCard, then answers TASK frames by
// running its model (an AgentRunner: prompt -> streamed chunks), emitting PARTIAL per chunk and a
// final RESULT (or a VOTE with a self-score in voting mode). In ChatterUI the runner wraps
// cui-llama.rn's completion (see makeLlamaAgentRunner in ../helix.ts); here a deterministic runner
// makes the round-trip verifiable against the real Python coordinator (./agent_smoke.mjs).
//
// Transport is one TCP socket to the coordinator (StreamTransport is point-to-point); every frame
// is written to that socket and the coordinator routes by the authenticated src inside the frame.

import net from "node:net";
import { FrameCodec, StreamFramer, frameOut, Msg } from "./frame_codec.mjs";

// A reference runner: uppercases the prompt, streamed word-by-word (mirrors EchoAgentRunner).
export function makeUppercaseRunner(agentId) {
  return {
    card: () => ({ agent_id: agentId, models: [], skills: ["chat"], task_types: ["chat"] }),
    *infer(prompt /*, context */) { for (const w of prompt.toUpperCase().split(" ")) yield w + " "; },
    score: (_p, r) => r.length,
  };
}

function cardBody(card) {
  return {
    agent_id: card.agent_id, models: card.models ?? [], skills: card.skills ?? [],
    task_types: card.task_types ?? [], mem: card.mem ?? 0, tps: card.tps ?? 0, batt: card.batt ?? 1.0,
  };
}

export class AgentNode {
  constructor(nodeId, clusterSecret, runner, { announceIntervalMs = 100 } = {}) {
    this.nodeId = nodeId;
    this.codec = FrameCodec.fromClusterSecret(nodeId, Buffer.from(clusterSecret));
    this.runner = runner;
    this.card = runner.card();
    this._announceIntervalMs = announceIntervalMs;
    this._sock = null;
    this._framer = new StreamFramer();
    this._gotHandshake = false;
    this._announceTimer = null;
    this.tasksServed = 0; // observable proof the agent did work
  }

  connect(host, port) {
    return new Promise((resolve, reject) => {
      const sock = net.createConnection({ host, port }, () => {
        sock.write(frameOut(Buffer.from(this.nodeId, "utf8"))); // handshake: our id first
        this._announceTimer = setInterval(() => this._announce(), this._announceIntervalMs);
        this._announce();
        resolve();
      });
      sock.on("data", (chunk) => this._onData(chunk));
      sock.on("error", reject);
      this._sock = sock;
    });
  }

  _write(frame) { if (this._sock) this._sock.write(frameOut(frame)); }

  _announce() {
    this._write(this.codec.seal(Msg.AGENT_ANNOUNCE, cardBody(this.card)));
    this._write(this.codec.seal(Msg.STATUS, { busy: false }));
  }

  _onData(chunk) {
    for (const item of this._framer.push(chunk)) {
      if (item.bad) { this.close(); return; }
      if (!this._gotHandshake) { this._gotHandshake = true; continue; } // first frame = coord id
      const msg = this.codec.open(item.frame);
      if (msg && msg.type === Msg.TASK) this._handleTask(msg);
    }
  }

  _handleTask(msg) {
    const coord = msg.src, tid = msg.tid;
    const mode = String(msg.body.mode ?? "single");
    const prompt = String(msg.body.prompt ?? "");
    const context = String(msg.body.context ?? "");
    const parts = [];
    for (const chunk of this.runner.infer(prompt, context)) {
      parts.push(chunk);
      this._write(this.codec.seal(Msg.PARTIAL, { chunk }, tid)); // stream partials to the coordinator
    }
    const result = parts.join("").trim();
    if (mode === "voting") {
      this._write(this.codec.seal(Msg.VOTE, { candidate: result, score: this.runner.score(prompt, result) }, tid));
    } else {
      this._write(this.codec.seal(Msg.RESULT, { text: result }, tid));
    }
    this.tasksServed += 1;
  }

  close() {
    if (this._announceTimer) { clearInterval(this._announceTimer); this._announceTimer = null; }
    if (this._sock) { this._sock.destroy(); this._sock = null; }
  }
}
