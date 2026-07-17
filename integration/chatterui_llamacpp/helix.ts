// SCAFFOLD (not compiled here). ChatterUI (React Native + llama.cpp) integration for HELIX.
//
// Python-in-RN is unnatural, so the protocol core here is native/TS. This file declares the
// contracts the app implements; the thin orchestration (coordinator/session/context) is a TS
// port verified against helix/spec/vectors.json, and the security primitives come from a
// native module (SecurityBridge). llama.cpp (llama.rn) provides the model.
//
// Track B has two options — pick per §3 of INTEGRATION_BRIEF.md.

// ---- Track B runner: two ways to use llama.cpp ----------------------------------

// Option A (fast start): let llama.cpp's own RPC split layers. HELIX is only the control
// plane; it hands llama.cpp the ring's node:port list and does discovery/placement/identity/
// attestation/health. The HELIX L4 ring (encryption/healing/int8) is NOT used.
export interface LlamaRpcCluster {
  startWorker(port: number): Promise<void>;            // wraps llama.cpp rpc-server
  runMain(prompt: string, rpcEndpoints: string[]): AsyncIterable<string>; // --rpc a:p,b:p,...
}

// Option B (full HELIX ring): implement the fine-grained ShardRunner over a layer band so
// activations ride HELIX frames. Needs ggml hooks to run a subgraph and expose hidden state.
// Mirrors helix/shard.py.
export interface ShardRunner {
  load(startLayer: number, endLayer: number, nLayers: number, modelId: string, modelPath: string): void;
  embed(tokenId: number): Float32Array;    // first shard
  forward(hidden: Float32Array): Float32Array; // every shard (its band)
  sample(hidden: Float32Array): number;     // last shard
  detok(tokenId: number): string;           // last shard
  eosId(): number;
}

// ---- Shared contracts (same as the edge side) -----------------------------------

export interface Transport {
  send(nodeId: string, frame: Uint8Array): Promise<void>;
  broadcast(frame: Uint8Array): Promise<void>;
  onFrame(handler: (frame: Uint8Array) => void): void; // no from_node — src is inside the frame
  peers(): Promise<string[]>;
}

// Native module (JSI/TurboModule): ChaCha20-Poly1305 + Ed25519 + HKDF.
// MUST reproduce helix/spec/vectors.json byte-for-byte (see conformance check below).
export interface SecurityBridge {
  hkdf(secret: Uint8Array, info: Uint8Array, length: number): Uint8Array;
  seal(key: Uint8Array, nonce: Uint8Array, pt: Uint8Array, aad: Uint8Array): Uint8Array;
  open(key: Uint8Array, nonce: Uint8Array, sealed: Uint8Array, aad: Uint8Array): Uint8Array | null;
  sign(seed: Uint8Array, msg: Uint8Array): Uint8Array;
  verify(pub: Uint8Array, msg: Uint8Array, sig: Uint8Array): boolean;
  deriveNodeId(pub: Uint8Array): string; // "hlx1" + sha256(pub)[:20 hex]
}

// ---- Bring-up gate: prove wire-compatibility before anything else ---------------
// Load helix/spec/vectors.json and assert the native/TS core reproduces every field.
export async function assertConformance(sec: SecurityBridge, vectors: any): Promise<void> {
  const secret = new TextEncoder().encode(vectors.hkdf.secret_utf8);
  const aeadKey = sec.hkdf(secret, new TextEncoder().encode("helix/1 aead key"), 32);
  assertHex(aeadKey, vectors.hkdf.aead_key, "hkdf aead_key");
  // ...repeat for the sealed frame, message encodings, node_id, signed_claim, and the
  //    RFC 8439 / RFC 8032 anchors. Any mismatch => not wire-compatible; stop.
}

function assertHex(got: Uint8Array, wantHex: string, what: string): void {
  const gotHex = Array.from(got).map((b) => b.toString(16).padStart(2, "0")).join("");
  if (gotHex !== wantHex) throw new Error(`conformance ${what}: ${gotHex} != ${wantHex}`);
}
