// HELIX control-plane client — JS reference (Node `net`), the ChatterUI *Level 1* integration.
//
// Level 1 makes ChatterUI a UI over a HELIX node: no protocol port, no crypto — just a TCP
// socket speaking newline-delimited JSON to helix/host/control_server.py. One JSON request per
// line -> one JSON response per line. This file mirrors, in Node, the exact framing a React
// Native client (over react-native-tcp-socket) must use; see ../control_client.ts for the RN shape.
//
// Verified end-to-end against the real Python server by ./control_smoke.mjs.

import net from "node:net";

export class HelixControlClient {
  constructor() {
    this._sock = null;
    this._buf = "";
    this._waiters = []; // FIFO of {resolve,reject} — server answers in request order per connection
  }

  connect(host, port) {
    return new Promise((resolve, reject) => {
      const sock = net.createConnection({ host, port }, () => resolve());
      sock.setEncoding("utf8");
      sock.on("data", (chunk) => this._onData(chunk));
      sock.on("error", (err) => {
        const w = this._waiters.shift();
        if (w) w.reject(err);
        else reject(err);
      });
      sock.on("close", () => {
        while (this._waiters.length) this._waiters.shift().reject(new Error("connection closed"));
      });
      this._sock = sock;
    });
  }

  _onData(chunk) {
    this._buf += chunk;
    let nl;
    while ((nl = this._buf.indexOf("\n")) >= 0) {
      const line = this._buf.slice(0, nl);
      this._buf = this._buf.slice(nl + 1);
      if (!line) continue;
      const w = this._waiters.shift();
      if (!w) continue;
      try {
        w.resolve(JSON.parse(line));
      } catch (err) {
        w.reject(err);
      }
    }
  }

  // one JSON line out -> one JSON line back
  request(obj) {
    return new Promise((resolve, reject) => {
      if (!this._sock) return reject(new Error("not connected"));
      this._waiters.push({ resolve, reject });
      this._sock.write(JSON.stringify(obj) + "\n");
    });
  }

  async ping() {
    return (await this.request({ cmd: "ping" })).pong === true;
  }

  async status() {
    return this.request({ cmd: "status" });
  }

  async nodes() {
    const r = await this.request({ cmd: "nodes" });
    if (!r.ok) throw new Error(r.error || "nodes failed");
    return r.agents;
  }

  async context(role, content) {
    const r = await this.request({ cmd: "context", role, content });
    if (!r.ok) throw new Error(r.error || "context failed");
  }

  // mode: single | parallel | voting | pipeline
  async infer(prompt, mode = "single", extra = {}) {
    const r = await this.request({ cmd: "infer", prompt, mode, ...extra });
    if (!r.ok) throw new Error(r.error || "infer failed");
    return r.result;
  }

  // strategy: hierarchical | ensemble (needs a superagent-configured server)
  async superRun(prompt, strategy) {
    const r = await this.request({ cmd: "super", prompt, strategy });
    if (!r.ok) throw new Error(r.error || "super failed");
    return r; // {ok, result, strategy, status}
  }

  close() {
    if (this._sock) {
      this._sock.end();
      this._sock = null;
    }
  }
}
