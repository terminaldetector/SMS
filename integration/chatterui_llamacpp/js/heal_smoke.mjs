// End-to-end proof of HELIX self-healing (②) with a JS shard, for ChatterUI *Level 3 / Option B*.
//
//   node integration/chatterui_llamacpp/js/heal_smoke.mjs
//
// Spawns helix/host/heal_bridge_demo.py (a full Orchestrator on A = coordinator + first shard).
// The JS ControlShardNode B joins the control plane (ANNOUNCE/HEARTBEAT), is leased a band, runs it
// as the last shard for a few tokens, then GOES SILENT (a crashed phone). A must detect the loss,
// re-place over the survivors, and resume from the checkpoint token -- the session completes with
// fault-free tokens and >=1 heal. This proves HELIX's ring survives a live participant dying, with
// the JS side as a genuine ring member.

import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ControlShardNode } from "./control_shard_node.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const PY = process.env.PYTHON || "python3";
const SECRET = "helix-heal-bridge-demo"; // must match heal_bridge_demo.py
const GB = 2 ** 30;

function startCoordinator() {
  return new Promise((resolve, reject) => {
    const proc = spawn(PY, ["-m", "helix.host.heal_bridge_demo", "--port", "0"],
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
  // JS is the last shard; it dies after threading 3 activations, forcing A to heal + resume.
  const shard = new ControlShardNode("B", SECRET, { memBytes: 4 * GB, dieAfterActivations: 3 });
  await shard.connect("127.0.0.1", port);

  const code = await waitExit(proc);
  shard.close();

  if (code !== 0) {
    console.error(`\nFAILED — coordinator exited ${code}`);
    process.exit(1);
  }
  if (shard.activations < 3) {
    console.error(`\nFAILED — JS shard contributed only ${shard.activations} activations before dying`);
    process.exit(1);
  }
  console.log(`\nALL PASSED — JS shard contributed ${shard.activations} activations then died; the real HELIX ring healed + resumed (Level 3 / Option B).`);
}

main().catch((err) => { console.error(err); process.exit(1); });
