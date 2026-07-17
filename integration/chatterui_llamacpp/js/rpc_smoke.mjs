// End-to-end proof of the ChatterUI *Level 3* control plane (Track B / Option A): a JS client asks
// the REAL Python HELIX coordinator to place a big model and return the llama.cpp RPC cluster
// topology it should drive.
//
//   node integration/chatterui_llamacpp/js/rpc_smoke.mjs
//
// Spawns helix/host/rpc_plan_demo.py (a coordinator + members announcing capacity + rpc addrs),
// then calls rpc_plan and asserts the plan: the ring covers the members, --rpc lists the workers,
// --tensor-split sums to 1 and spans the ring, and the main node is first. The tensor math and RPC
// transport are llama.cpp's (native, off-device); HELIX supplies discovery/placement/topology.

import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { HelixControlClient } from "./control_client.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const PY = process.env.PYTHON || "python3";
const GB = 2 ** 30;

function startServer() {
  return new Promise((resolve, reject) => {
    const proc = spawn(PY, ["-m", "helix.host.rpc_plan_demo", "--port", "0"],
                       { cwd: repo, env: { ...process.env, PYTHONPATH: repo } });
    let buf = "";
    const timer = setTimeout(() => reject(new Error("server did not print PORT in time")), 10000);
    proc.stdout.setEncoding("utf8");
    proc.stdout.on("data", (d) => {
      buf += d;
      const m = buf.match(/PORT (\d+)/);
      if (m) { clearTimeout(timer); resolve({ proc, port: Number(m[1]) }); }
    });
    proc.stderr.setEncoding("utf8");
    proc.stderr.on("data", (d) => process.stderr.write("[py:err] " + d));
    proc.on("error", reject);
  });
}

let pass = 0;
function check(cond, what) {
  if (!cond) throw new Error("FAIL: " + what);
  pass++;
  console.log("  ok  " + what);
}

async function main() {
  const { proc, port } = await startServer();
  const cli = new HelixControlClient();
  try {
    await cli.connect("127.0.0.1", port);

    // Place a 6-layer, 3 GB model across the 3 demo phones (mem-weighted 8:4:4).
    const plan = await cli.rpcPlan({ model_id: "big-16b", n_layers: 6, model_bytes: 3 * GB,
                                     order: ["hlxA", "hlxB", "hlxC"] });

    check(JSON.stringify(plan.ring) === JSON.stringify(["hlxA", "hlxB", "hlxC"]),
          `ring covers all members: ${plan.ring.join(",")}`);
    check(plan.main === "hlxA", `main (driver) is the strongest node: ${plan.main}`);
    check(plan.endpoints.length === 3 && plan.endpoints[0].role === "main"
          && plan.endpoints.slice(1).every((e) => e.role === "worker"),
          "endpoints: 1 main + 2 workers, each with an addr + band");

    // --rpc lists exactly the worker rpc-servers (not the main driver).
    check(plan.rpc_arg === "10.0.0.2:50052,10.0.0.3:50052", `--rpc = ${plan.rpc_arg}`);

    // --tensor-split spans the ring (main first) and covers all layers.
    const sum = plan.tensor_split.reduce((a, b) => a + b, 0);
    check(plan.tensor_split.length === 3 && Math.abs(sum - 1) < 1e-9,
          `--tensor-split sums to 1 over the ring: [${plan.tensor_split.map((x) => x.toFixed(3))}]`);

    // Bands are contiguous and cover [0, n_layers).
    const bands = plan.endpoints.map((e) => e.band);
    let ok = bands[0][0] === 0 && bands[bands.length - 1][1] === 6;
    for (let i = 1; i < bands.length; i++) ok = ok && bands[i][0] === bands[i - 1][1];
    check(ok, "layer bands are contiguous and cover [0,6)");

    // Insufficient capacity is a clean error, not a crash.
    const bad = await cli.request({ cmd: "rpc_plan", model_id: "huge", n_layers: 6,
                                    model_bytes: 999 * GB, order: ["hlxA", "hlxB", "hlxC"] });
    check(bad.ok === false && /placement|memory|capacity/i.test(bad.error),
          "oversized model -> clean InsufficientCapacity error");

    console.log(`\nALL PASSED (${pass} checks) — JS drives the real HELIX Level 3 control plane (Option A).`);
  } finally {
    cli.close();
    proc.kill("SIGTERM");
  }
}

main().catch((err) => { console.error(err); process.exit(1); });
