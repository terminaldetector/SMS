// SCAFFOLD (not compiled here). ChatterUI (React Native + cui-llama.rn) integration for HELIX.
//
// ChatterUI runs GGUF models on-device via cui-llama.rn (a fork of llama.rn). Python-in-RN is
// unnatural, so on the ChatterUI side HELIX is native/TS. There are THREE integration levels,
// increasing in effort — start at the top:
//
//   1. CLIENT   — ChatterUI is a UI over a HELIX node: connect to helix/host/control_server
//                 (JSON over TCP), issue infer/super. NO protocol port. Fastest.
//   2. AGENT    — ChatterUI's whole model joins the mesh as a Track A agent (llama.rn
//                 completion -> AgentRunner). Needs a small TS HELIX client (codec+transport+
//                 worker) verified against helix/spec/vectors.json. Medium.
//   3. SHARDING — ChatterUI contributes layers to a big Track B model. Needs cui-llama.rn
//                 NATIVE changes (llama.cpp RPC or a ShardRunner over ggml). Hard.
//
// NOTE: ChatterUI is AGPL-3.0 — integrating HELIX makes the combined app AGPL. Confirm licensing.

// =====================================================================================
// LEVEL 1 — CLIENT (fastest MVP, no protocol port)
// =====================================================================================
// ChatterUI opens a TCP socket to helix/host/control_server.py and sends newline-delimited
// JSON. The server already exists and is tested. ChatterUI's own local model still works;
// this just lets the UI drive a HELIX mesh (infer / super) running on a PC or a Python phone.
export interface HelixControlClient {
  connect(host: string, port: number): Promise<void>;
  request(obj: Record<string, unknown>): Promise<any>;     // one JSON line -> one JSON line
  infer(prompt: string, mode?: string, skill?: string): Promise<string>;
  super(prompt: string, strategy?: "hierarchical" | "ensemble"): Promise<string>;
  nodes(): Promise<string[]>;
}

// =====================================================================================
// LEVEL 2 — AGENT (recommended for real participation): ChatterUI's model = a Track A agent
// =====================================================================================
// Map cui-llama.rn's streaming completion onto the HELIX AgentRunner contract (helix/agent/runner.py).
// Then a TS HELIX client (frame codec + transport + agent worker) makes it a mesh node.

export interface AgentCard {
  agent_id: string; models: string[]; skills: string[]; task_types: string[];
  mem?: number; tps?: number; batt?: number;
}

export interface AgentRunner {
  card(): AgentCard;
  infer(prompt: string, context: string): AsyncIterable<string>;  // stream chunks
  score(prompt: string, result: string): number;
}

// `ctx` is a cui-llama.rn LlamaContext from initLlama({ model: '<gguf path>', ... }).
export function makeLlamaAgentRunner(ctx: any, card: AgentCard): AgentRunner {
  return {
    card: () => card,
    async *infer(prompt: string, context: string): AsyncIterable<string> {
      const full = context ? `${context}\n\n${prompt}` : prompt;
      const queue: string[] = [];
      let done = false;
      // cui-llama.rn streams tokens via the partial callback; adapt to an async iterable.
      const completion = ctx
        .completion({ prompt: full, n_predict: 256 }, (tk: { token: string }) => queue.push(tk.token))
        .then(() => { done = true; });
      while (!done || queue.length) {
        if (queue.length) yield queue.shift() as string;
        else await new Promise((r) => setTimeout(r, 5));
      }
      await completion;
    },
    score: (_prompt: string, result: string) => result.length,  // app-defined; used for voting
  };
}

// The TS HELIX client surface is PROVEN in JS (js/agent_smoke.mjs: a JS agent joins the real
// Python coordinator over TCP and serves single/parallel/voting/pipeline). It comprises:
//   - frame codec (helix/frame.py + session.py FrameCodec): magic|flags|epoch|nonce|sealed
//     -> js/frame_codec.mjs (seal with seq + open with epoch/replay), pinned to vectors.json
//   - message JSON (helix/message.py): {v,t,seq,src,tid,b}          -> js/helix_codec.mjs
//   - AgentNode worker: on TASK -> run AgentRunner -> stream PARTIAL -> RESULT/VOTE
//     -> js/agent_node.mjs
// To ship in ChatterUI: use makeLlamaAgentRunner as the runner, swap node:net for
// react-native-tcp-socket, and route crypto through a native SecurityBridge (below).
// Coordinator role optional (a PC can coordinate).

// =====================================================================================
// LEVEL 3 — SHARDING (Track B): ChatterUI contributes layers. NATIVE cui-llama.rn work.
// =====================================================================================
// Option A (fast): llama.cpp's own RPC splits layers; HELIX is only control plane (discovery/
// placement/identity/attestation/health) and hands llama.cpp the ring's host:port list.
export interface LlamaRpcCluster {
  startWorker(port: number): Promise<void>;                // wraps llama.cpp rpc-server (GGML_RPC build)
  runMain(prompt: string, rpcEndpoints: string[]): AsyncIterable<string>; // --rpc a:p,b:p
}
// Option B (full HELIX ring): a fine-grained ShardRunner over a layer band (ggml hooks +
// hidden-state I/O). Mirrors helix/shard.py. Activations then ride HELIX frames.
export interface ShardRunner {
  load(startLayer: number, endLayer: number, nLayers: number, modelId: string, modelPath: string): void;
  embed(tokenId: number): Float32Array;
  forward(hidden: Float32Array): Float32Array;
  sample(hidden: Float32Array): number;
  detok(tokenId: number): string;
  eosId(): number;
}

// =====================================================================================
// SHARED CONTRACTS (levels 2-3)
// =====================================================================================
export interface Transport {                               // RN TCP/UDP (Wi-Fi/USB)
  send(nodeId: string, frame: Uint8Array): Promise<void>;
  broadcast(frame: Uint8Array): Promise<void>;
  onFrame(handler: (frame: Uint8Array) => void): void;     // no from_node — src is inside the frame
  peers(): Promise<string[]>;
}

// Native module (JSI/TurboModule): ChaCha20-Poly1305 + Ed25519 + HKDF.
// MUST reproduce helix/spec/vectors.json byte-for-byte.
export interface SecurityBridge {
  hkdf(secret: Uint8Array, info: Uint8Array, length: number): Uint8Array;
  seal(key: Uint8Array, nonce: Uint8Array, pt: Uint8Array, aad: Uint8Array): Uint8Array;
  open(key: Uint8Array, nonce: Uint8Array, sealed: Uint8Array, aad: Uint8Array): Uint8Array | null;
  sign(seed: Uint8Array, msg: Uint8Array): Uint8Array;
  verify(pub: Uint8Array, msg: Uint8Array, sig: Uint8Array): boolean;
  deriveNodeId(pub: Uint8Array): string;                   // "hlx1" + sha256(pub)[:20 hex]
}

// Bring-up gate: the native/TS core must reproduce helix/spec/vectors.json before anything else.
export async function assertConformance(sec: SecurityBridge, vectors: any): Promise<void> {
  const secret = new TextEncoder().encode(vectors.hkdf.secret_utf8);
  const aeadKey = sec.hkdf(secret, new TextEncoder().encode("helix/1 aead key"), 32);
  assertHex(aeadKey, vectors.hkdf.aead_key, "hkdf aead_key");
  // ...repeat for the sealed frame, message encodings, node_id, signed_claim, RFC anchors.
}

function assertHex(got: Uint8Array, wantHex: string, what: string): void {
  const gotHex = Array.from(got).map((b) => b.toString(16).padStart(2, "0")).join("");
  if (gotHex !== wantHex) throw new Error(`conformance ${what}: ${gotHex} != ${wantHex}`);
}
