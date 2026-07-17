// End-to-end proof of the ChatterUI *Level 1* integration: the JS control client drives the REAL
// Python HELIX mesh over TCP. Spawns helix/host/control_demo.py (coordinator + echo agents),
// reads its PORT, then runs ping / status / nodes / context / infer(single,parallel,voting) /
// super over newline-delimited JSON and asserts the responses.
//
//   node integration/chatterui_llamacpp/js/control_smoke.mjs
//
// This is what Level 1 needs to be real: not a scaffold, but a client that speaks the server's
// wire. A React Native client uses the same framing over react-native-tcp-socket.

import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { HelixControlClient } from "./control_client.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const PY = process.env.PYTHON || "python3";

function startServer() {
  return new Promise((resolve, reject) => {
    const proc = spawn(PY, ["-m", "helix.host.control_demo", "--port", "0"],
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
    proc.on("error", reject);
    proc.on("exit", (c) => { if (c !== null && c !== 0) reject(new Error(`server exited ${c}`)); });
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

    check(await cli.ping(), "ping -> pong");

    const st = await cli.status();
    check(st.ok && st.node_id === "coord", "status -> coordinator node_id");
    check(st.agents >= 2, "status -> agents registered");

    const agents = await cli.nodes();
    check(agents.includes("up") && agents.includes("lo"), "nodes -> up & lo present");

    await cli.context("user", "remember: sky is blue");
    check(true, "context append accepted");

    const single = await cli.infer("Hello", "single", { skill: "chat" });
    check(typeof single === "string" && single.length > 0, `infer single -> "${single}"`);

    const parallel = await cli.infer("Hello", "parallel", { skill: "chat" });
    check(typeof parallel === "string" && parallel.length > 0, "infer parallel -> result");

    const voting = await cli.infer("Hello", "voting", { skill: "chat", n: 2 });
    check(typeof voting === "string" && voting.length > 0, "infer voting -> result");

    const sup = await cli.superRun("Hello", "ensemble");
    check(sup.ok && sup.result.includes("BIG(") && /HELLO|hello/.test(sup.result),
          "super ensemble -> Track B + Track A fused");

    const bad = await cli.request({ cmd: "nope" });
    check(bad.ok === false && /unknown cmd/.test(bad.error), "unknown cmd -> error, connection kept");

    // connection survived the error: a follow-up still works
    check(await cli.ping(), "ping after error -> pong (connection alive)");

    console.log(`\nALL PASSED (${pass} checks) — JS control client drives the real HELIX mesh (Level 1).`);
  } finally {
    cli.close();
    proc.kill("SIGTERM");
  }
}

main().catch((err) => { console.error(err); process.exit(1); });
