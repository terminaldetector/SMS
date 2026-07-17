// HELIX shard-ring worker — JS runtime for ChatterUI *Level 3* / Option B (full HELIX ring).
//
// Mirrors the worker half of helix/pipeline.py ShardPipeline + helix/shard.py ShardRunner: a node
// holds a contiguous band of layers, and hidden states cross the wire as HELIX ACTIVATION frames
// (int8/raw codec). Coordinator-driven — the last shard returns each sampled token to the
// coordinator via SHARD_TOKEN.
//
//   FEED (first shard)  -> embed(token) -> forward(band) -> ACTIVATION to next
//   ACTIVATION          -> forward(band) -> ACTIVATION to next  (or, if last) sample -> SHARD_TOKEN
//
// In Option B a real node plugs a native ggml ShardRunner here (band execution + hidden I/O); this
// dependency-free NumericShardRunner proves the *wire + ring threading* against the real Python
// coordinator (./shard_smoke.mjs). The framing/crypto reuse the same FrameCodec as Level 2.

import net from "node:net";
import { FrameCodec, StreamFramer, frameOut, Msg } from "./frame_codec.mjs";
import { RawActivationCodec } from "./activation.mjs";

// Reference runner (helix/shard.py NumericShardRunner): each layer +1 to every element, so a band
// of L layers adds L in forward(); embed(t)=[t,0,0,0]; sample=round(hidden[0]). Threading a token
// through ALL bands yields t + total_layers — only if every shard ran.
export class NumericShardRunner {
  constructor(dim = 4) { this.dim = dim; this.layers = 0; }
  load(bandN) { this.layers = bandN; }
  embed(tokenId) { const h = new Array(this.dim).fill(0); h[0] = tokenId; return h; }
  forward(hidden) { return hidden.map((x) => x + this.layers); }
  sample(hidden) { return Math.round(hidden[0]); }
  detok(tokenId) { return String(tokenId); }
  eosId() { return -1; }
}

export class ShardNode {
  // ring: string[]; band: [start,end); nLayers: total; runner: ShardRunner (defaults to Numeric).
  constructor(nodeId, clusterSecret, { ring, band, nLayers, coordinator, runner, codec } = {}) {
    this.nodeId = nodeId;
    this.codec = FrameCodec.fromClusterSecret(nodeId, Buffer.from(clusterSecret));
    this.ring = ring;
    this.rank = ring.indexOf(nodeId);
    this.isFirst = this.rank === 0;
    this.isLast = this.rank === ring.length - 1;
    this.coordinator = coordinator;
    this.band = band;
    this.runner = runner || new NumericShardRunner();
    this.runner.load(band[1] - band[0]);
    this.actCodec = codec || new RawActivationCodec();
    this._sock = null;
    this._framer = new StreamFramer();
    this._gotHandshake = false;
    this._lastStep = new Map(); // tid -> highest step processed (idempotency)
    this.hops = 0;              // observable proof of ring work
  }

  connect(host, port) {
    return new Promise((resolve, reject) => {
      const sock = net.createConnection({ host, port }, () => {
        sock.write(frameOut(Buffer.from(this.nodeId, "utf8"))); // handshake: our id first
        resolve();
      });
      sock.on("data", (chunk) => this._onData(chunk));
      sock.on("error", reject);
      this._sock = sock;
    });
  }

  _write(frame) { if (this._sock) this._sock.write(frameOut(frame)); }
  _next() { return this.ring[(this.rank + 1) % this.ring.length]; }

  _fresh(tid, step) {
    if (step <= (this._lastStep.get(tid) ?? -1)) return false;
    this._lastStep.set(tid, step);
    return true;
  }

  _onData(chunk) {
    for (const item of this._framer.push(chunk)) {
      if (item.bad) { this.close(); return; }
      if (!this._gotHandshake) { this._gotHandshake = true; continue; } // first frame = peer id
      const msg = this.codec.open(item.frame);
      if (!msg) continue;
      if (msg.type === Msg.FEED) this._onFeed(msg);
      else if (msg.type === Msg.ACTIVATION) this._onActivation(msg);
    }
  }

  _onFeed(msg) {
    if (!this.isFirst) return;
    const tid = msg.tid, step = Number(msg.body.step);
    if (!this._fresh(tid, step)) return;
    const hidden = this.runner.forward(this.runner.embed(Number(msg.body.token)));
    this._advance(tid, step, hidden);
  }

  _onActivation(msg) {
    const tid = msg.tid, step = Number(msg.body.step);
    if (!this._fresh(tid, step)) return;
    const hidden = this.runner.forward(this.actCodec.decode(msg.body.act));
    this._advance(tid, step, hidden);
  }

  _advance(tid, step, hidden) {
    this.hops += 1;
    if (!this.isLast) {
      this._write(this.codec.seal(Msg.ACTIVATION, { step, act: this.actCodec.encode(hidden) }, tid));
      return;
    }
    const token = this.runner.sample(hidden);
    this._write(this.codec.seal(Msg.SHARD_TOKEN, {
      step, token, text: this.runner.detok(token), eos: token === this.runner.eosId(),
    }, tid));
  }

  close() { if (this._sock) { this._sock.destroy(); this._sock = null; } }
}
