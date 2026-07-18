// HELIX control-shard node — JS runtime for the ChatterUI *Level 3 / Option B* healing proof.
//
// This is a shard-ring worker that also speaks the control plane (helix/control.py): it ANNOUNCEs
// its capacity + HEARTBEATs liveness, accepts a LEASE (its layer band for a placed model) and
// replies LEASE_ACK, then runs its band (FEED/ACTIVATION -> SHARD_TOKEN). Crucially, it can be told
// to GO SILENT mid-generation ("die") after N activations, so the Python Orchestrator on the other
// end must detect the loss (heartbeat prune), re-place over the survivors, and RESUME from the last
// token -- HELIX (2) self-healing -- completing the generation with the same output as fault-free.
//
// The band/ring come from the LEASE (not hardcoded), so this also exercises lease-driven pipeline
// (re)build. Framing/crypto reuse the same FrameCodec as Levels 2-3.

import net from "node:net";
import { FrameCodec, StreamFramer, frameOut, Msg } from "./frame_codec.mjs";
import { RawActivationCodec } from "./activation.mjs";
import { NumericShardRunner } from "./shard_node.mjs";

export class ControlShardNode {
  constructor(nodeId, clusterSecret, {
    memBytes = 4 * 2 ** 30, announceIntervalMs = 100, heartbeatIntervalMs = 80,
    dieAfterActivations = Infinity, runner, codec,
  } = {}) {
    this.nodeId = nodeId;
    this.codec = FrameCodec.fromClusterSecret(nodeId, Buffer.from(clusterSecret));
    this.memBytes = memBytes;
    this._announceMs = announceIntervalMs;
    this._heartbeatMs = heartbeatIntervalMs;
    this._dieAfter = dieAfterActivations;
    this.runner = runner || new NumericShardRunner();
    this.actCodec = codec || new RawActivationCodec();

    this._sock = null;
    this._framer = new StreamFramer();
    this._gotHandshake = false;
    this._annTimer = null;
    this._hbTimer = null;
    this._dead = false;

    // lease-driven ring config (set on LEASE)
    this.ring = null; this.band = null; this.rank = -1;
    this.isFirst = false; this.isLast = false; this.coordinator = null; this.term = -1;
    this._lastStep = new Map();
    this.activations = 0; // observable proof of ring work
  }

  connect(host, port) {
    return new Promise((resolve, reject) => {
      const sock = net.createConnection({ host, port }, () => {
        sock.write(frameOut(Buffer.from(this.nodeId, "utf8"))); // handshake: our id first
        this._annTimer = setInterval(() => this._announce(), this._announceMs);
        this._hbTimer = setInterval(() => this._heartbeat(), this._heartbeatMs);
        this._announce();
        resolve();
      });
      sock.on("data", (chunk) => this._onData(chunk));
      sock.on("error", reject);
      this._sock = sock;
    });
  }

  _write(frame) { if (this._sock && !this._dead) this._sock.write(frameOut(frame)); }
  _announce() { this._write(this.codec.seal(Msg.ANNOUNCE, { mem: this.memBytes })); }
  _heartbeat() { this._write(this.codec.seal(Msg.HEARTBEAT, {})); }

  // Go silent: stop announcing/heartbeating and ignore all frames. The coordinator's roster prunes
  // us within ttl, marks the placement stale, and heals. We keep the socket open (a silent peer,
  // not a clean disconnect) so the loss is detected by liveness, exactly like a crashed phone.
  die() {
    this._dead = true;
    if (this._annTimer) { clearInterval(this._annTimer); this._annTimer = null; }
    if (this._hbTimer) { clearInterval(this._hbTimer); this._hbTimer = null; }
  }

  _onData(chunk) {
    for (const item of this._framer.push(chunk)) {
      if (item.bad) { this.close(); return; }
      if (!this._gotHandshake) { this._gotHandshake = true; continue; }
      if (this._dead) continue; // silent: drop everything
      const msg = this.codec.open(item.frame);
      if (!msg) continue;
      if (msg.type === Msg.LEASE) this._onLease(msg);
      else if (msg.type === Msg.FEED) this._onFeed(msg);
      else if (msg.type === Msg.ACTIVATION) this._onActivation(msg);
    }
  }

  _onLease(msg) {
    const b = msg.body;
    const term = Number(b.term);
    if (term < this.term) return; // superseded
    this.term = term;
    this.ring = b.ring.map(String);
    this.band = [Number(b.band[0]), Number(b.band[1])];
    this.rank = this.ring.indexOf(this.nodeId);
    this.isFirst = this.rank === 0;
    this.isLast = this.rank === this.ring.length - 1;
    this.coordinator = msg.src; // authenticated identity, not a body field
    this.runner.load(this.band[1] - this.band[0]);
    this._write(this.codec.seal(Msg.LEASE_ACK, { term, band: [this.band[0], this.band[1]] }));
  }

  _next() { return this.ring[(this.rank + 1) % this.ring.length]; }
  _fresh(tid, step) {
    if (step <= (this._lastStep.get(tid) ?? -1)) return false;
    this._lastStep.set(tid, step);
    return true;
  }

  _onFeed(msg) {
    if (!this.isFirst || this.ring === null) return;
    const tid = msg.tid, step = Number(msg.body.step);
    if (!this._fresh(tid, step)) return;
    this._advance(tid, step, this.runner.forward(this.runner.embed(Number(msg.body.token))));
  }

  _onActivation(msg) {
    if (this.ring === null) return;
    const tid = msg.tid, step = Number(msg.body.step);
    if (!this._fresh(tid, step)) return;
    this._advance(tid, step, this.runner.forward(this.actCodec.decode(msg.body.act)));
  }

  _advance(tid, step, hidden) {
    this.activations += 1;
    if (!this.isLast) {
      this._write(this.codec.seal(Msg.ACTIVATION, { step, act: this.actCodec.encode(hidden) }, tid));
    } else {
      const token = this.runner.sample(hidden);
      this._write(this.codec.seal(Msg.SHARD_TOKEN, {
        step, token, text: this.runner.detok(token), eos: token === this.runner.eosId(),
      }, tid));
    }
    if (this.activations >= this._dieAfter && !this._dead) this.die();
  }

  close() {
    this.die();
    if (this._sock) { this._sock.destroy(); this._sock = null; }
  }
}
