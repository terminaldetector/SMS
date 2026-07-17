// SCAFFOLD (not compiled here). ChatterUI *Level 1* HELIX control client for React Native.
//
// Level 1 = ChatterUI as a UI over a HELIX node: NO protocol port, NO crypto. Just a TCP socket
// speaking newline-delimited JSON to helix/host/control_server.py — one JSON request per line,
// one JSON response per line. This is the fastest way to demo ChatterUI <-> HELIX mesh; the
// server already exists and is tested, and the exact framing here is verified end-to-end against
// the real Python server by js/control_smoke.mjs (11/11).
//
// RN transport: `react-native-tcp-socket` (TcpSocket.createConnection). The class below is
// transport-agnostic — pass any object exposing write(str) + 'data'/'error'/'close' events.

import { HelixControlClient as IHelixControlClient } from "./helix";

// Minimal duck-typed socket so this compiles against react-native-tcp-socket without importing it.
interface Sockish {
  write(data: string): void;
  on(event: "data", cb: (data: string | Buffer) => void): void;
  on(event: "error", cb: (err: Error) => void): void;
  on(event: "close", cb: () => void): void;
  destroy(): void;
}
type Connect = (host: string, port: number) => Promise<Sockish>;

// Example wiring with react-native-tcp-socket:
//   import TcpSocket from "react-native-tcp-socket";
//   const connect: Connect = (host, port) => new Promise((res, rej) => {
//     const s = TcpSocket.createConnection({ host, port }, () => res(s as unknown as Sockish));
//     s.on("error", rej);
//   });

interface Waiter { resolve: (v: any) => void; reject: (e: Error) => void; }

export class RnHelixControlClient implements IHelixControlClient {
  private sock: Sockish | null = null;
  private buf = "";
  private waiters: Waiter[] = []; // server answers in request order per connection

  constructor(private readonly connectFn: Connect) {}

  async connect(host: string, port: number): Promise<void> {
    const sock = await this.connectFn(host, port);
    sock.on("data", (d) => this.onData(typeof d === "string" ? d : d.toString("utf8")));
    sock.on("error", (err) => { const w = this.waiters.shift(); if (w) w.reject(err); });
    sock.on("close", () => { while (this.waiters.length) this.waiters.shift()!.reject(new Error("closed")); });
    this.sock = sock;
  }

  private onData(chunk: string): void {
    this.buf += chunk;
    let nl: number;
    while ((nl = this.buf.indexOf("\n")) >= 0) {
      const line = this.buf.slice(0, nl);
      this.buf = this.buf.slice(nl + 1);
      if (!line) continue;
      const w = this.waiters.shift();
      if (!w) continue;
      try { w.resolve(JSON.parse(line)); } catch (e) { w.reject(e as Error); }
    }
  }

  request(obj: Record<string, unknown>): Promise<any> {
    return new Promise((resolve, reject) => {
      if (!this.sock) return reject(new Error("not connected"));
      this.waiters.push({ resolve, reject });
      this.sock.write(JSON.stringify(obj) + "\n");
    });
  }

  async infer(prompt: string, mode = "single", skill?: string): Promise<string> {
    const req: Record<string, unknown> = { cmd: "infer", prompt, mode };
    if (skill) req.skill = skill;
    const r = await this.request(req);
    if (!r.ok) throw new Error(r.error || "infer failed");
    return r.result;
  }

  async super(prompt: string, strategy?: "hierarchical" | "ensemble"): Promise<string> {
    const r = await this.request({ cmd: "super", prompt, strategy });
    if (!r.ok) throw new Error(r.error || "super failed");
    return r.result;
  }

  async nodes(): Promise<string[]> {
    const r = await this.request({ cmd: "nodes" });
    if (!r.ok) throw new Error(r.error || "nodes failed");
    return r.agents;
  }

  close(): void {
    if (this.sock) { this.sock.destroy(); this.sock = null; }
  }
}
