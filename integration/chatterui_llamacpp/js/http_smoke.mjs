// End-to-end proof of the ChatterUI *Level 1 mesh mod over HTTP*: a fetch client (exactly what RN
// uses, no native module) drives the REAL Python HELIX mesh via the HTTP control adapter.
//
//   node integration/chatterui_llamacpp/js/http_smoke.mjs
//
// Spawns helix/host/http_control.py (a demo mesh behind POST /cmd, GET /health), then uses global
// fetch to run health / nodes / infer(single,parallel,voting) / super and asserts the responses.
// This is the backend for the HelixMeshScreen mesh mod, proven in-environment.

import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const PY = process.env.PYTHON || "python3";

function startServer() {
  return new Promise((resolve, reject) => {
    const proc = spawn(PY, ["-m", "helix.host.http_control", "--host", "127.0.0.1", "--port", "0"],
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

// one HELIX command over HTTP — the exact shape helixClient.ts uses in the app.
async function cmd(base, obj) {
  const r = await fetch(base + "/cmd", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(obj),
  });
  return r.json();
}

async function main() {
  const { proc, port } = await startServer();
  const base = `http://127.0.0.1:${port}`;
  try {
    const health = await (await fetch(base + "/health")).json();
    check(health.ok === true, "GET /health -> ok");

    const nodes = await cmd(base, { cmd: "nodes" });
    check(nodes.ok && nodes.agents.includes("up") && nodes.agents.includes("lo"),
          `nodes -> ${nodes.agents.join(",")}`);

    const single = await cmd(base, { cmd: "infer", prompt: "Hello", mode: "single", skill: "chat" });
    check(single.ok && typeof single.result === "string" && single.result.length > 0,
          `infer single -> "${single.result}"`);

    const parallel = await cmd(base, { cmd: "infer", prompt: "Hello", mode: "parallel", skill: "chat" });
    check(parallel.ok && parallel.result.length > 0, "infer parallel -> result");

    const voting = await cmd(base, { cmd: "infer", prompt: "Hello", mode: "voting", skill: "chat", n: 2 });
    check(voting.ok && voting.result.length > 0, "infer voting -> result");

    const sup = await cmd(base, { cmd: "super", prompt: "Hello", strategy: "ensemble" });
    check(sup.ok && sup.result.includes("BIG(") && /HELLO|hello/.test(sup.result),
          "super ensemble -> Track B + Track A fused");

    const bad = await cmd(base, { cmd: "nope" });
    check(bad.ok === false && /unknown cmd/.test(bad.error), "unknown cmd -> clean error");

    console.log(`\nALL PASSED (${pass} checks) — fetch client drives the real HELIX mesh over HTTP (Level 1 mesh mod).`);
  } finally {
    proc.kill("SIGTERM");
  }
}

main().catch((err) => { console.error(err); process.exit(1); });
