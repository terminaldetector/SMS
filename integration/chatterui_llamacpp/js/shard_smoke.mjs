// End-to-end proof of the ChatterUI *Level 3 / Option B* ring: a JS-implemented HELIX shard worker
// joins a REAL Python layer-shard ring over TCP and threads activations through its band.
//
//   node integration/chatterui_llamacpp/js/shard_smoke.mjs
//
// Spawns helix/host/shard_bridge_demo.py (coordinator + first shard A[0,4)); the JS ShardNode is
// the last shard B[4,6). A seeds the ring, B receives ACTIVATION frames, runs its band, samples,
// and returns SHARD_TOKEN. The coordinator verifies tokens [7,13,19] (both bands ran) and exits 0.
//
// This proves the Track B data-plane wire (FEED/ACTIVATION/SHARD_TOKEN + activation codec, sealed
// HELIX frames) is reproducible in JS — the crux of Option B. The tensor math here is the numeric
// reference; a real node swaps in a native ggml ShardRunner behind the same seam.

import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ShardNode } from "./shard_node.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const PY = process.env.PYTHON || "python3";
const SECRET = "helix-shard-bridge-demo"; // must match shard_bridge_demo.py

function startCoordinator() {
  return new Promise((resolve, reject) => {
    const proc = spawn(PY, ["-m", "helix.host.shard_bridge_demo", "--port", "0"],
                       { cwd: repo, env: { ...process.env, PYTHONPATH: repo } });
    let out = "";
    const timer = setTimeout(() => reject(new Error("coordinator did not print PORT")), 10000);
    proc.stdout.setEncoding("utf8");
    proc.stdout.on("data", (d) => {
      out += d;
      let nl;
      while ((nl = out.indexOf("\n")) >= 0) {
        const line = out.slice(0, nl); out = out.slice(nl + 1);
        const m = line.match(/^PORT (\d+)/);
        if (m) { clearTimeout(timer); resolve({ proc, port: Number(m[1]) }); }
        else console.log("[py] " + line);
      }
    });
    proc.stderr.setEncoding("utf8");
    proc.stderr.on("data", (d) => process.stderr.write("[py:err] " + d));
    proc.on("error", reject);
  });
}

const waitExit = (proc) => new Promise((res) => proc.on("exit", res));

async function main() {
  const { proc, port } = await startCoordinator();
  // JS is the last shard B, band [4,6) of the 6-layer ring [A,B].
  const shard = new ShardNode("B", SECRET, { ring: ["A", "B"], band: [4, 6], nLayers: 6, coordinator: "A" });
  await shard.connect("127.0.0.1", port);

  const code = await waitExit(proc);
  shard.close();

  if (code !== 0) {
    console.error(`\nFAILED — coordinator exited ${code}`);
    process.exit(1);
  }
  if (shard.hops < 3) {
    console.error(`\nFAILED — JS shard threaded only ${shard.hops} activations (expected >= 3)`);
    process.exit(1);
  }
  console.log(`\nALL PASSED — JS shard joined the real HELIX ring and threaded ${shard.hops} activations (Level 3 / Option B).`);
}

main().catch((err) => { console.error(err); process.exit(1); });
