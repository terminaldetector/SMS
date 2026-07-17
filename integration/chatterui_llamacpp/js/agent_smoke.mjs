// End-to-end proof of the ChatterUI *Level 2* integration: a JS-implemented HELIX agent joins the
// REAL Python coordinator over TCP and does real work through the actual coordinate path.
//
//   node integration/chatterui_llamacpp/js/agent_smoke.mjs
//
// Spawns helix/host/agent_bridge_demo.py (the coordinator), connects the JS AgentNode, which
// announces a `chat` card and answers TASK frames (PARTIAL stream -> RESULT/VOTE). The Python
// coordinator verifies single/parallel/voting/pipeline and exits 0 on success; this script
// propagates that, and also confirms the JS agent actually served tasks.
//
// This is the crux of Level 2 made real: not a scaffold, but a JS agent speaking the full HELIX
// agent protocol (frame codec + stream transport + worker) against the reference implementation.

import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { AgentNode, makeUppercaseRunner } from "./agent_node.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, "../../..");
const PY = process.env.PYTHON || "python3";
const SECRET = "helix-agent-bridge-demo"; // must match agent_bridge_demo.py

function startCoordinator() {
  return new Promise((resolve, reject) => {
    const proc = spawn(PY, ["-m", "helix.host.agent_bridge_demo", "--port", "0"],
                       { cwd: repo, env: { ...process.env, PYTHONPATH: repo } });
    let out = "";
    const lines = [];
    const timer = setTimeout(() => reject(new Error("coordinator did not print PORT")), 10000);
    proc.stdout.setEncoding("utf8");
    proc.stdout.on("data", (d) => {
      out += d;
      let nl;
      while ((nl = out.indexOf("\n")) >= 0) {
        const line = out.slice(0, nl); out = out.slice(nl + 1);
        lines.push(line);
        const m = line.match(/^PORT (\d+)/);
        if (m) { clearTimeout(timer); resolve({ proc, port: Number(m[1]), lines }); }
        else console.log("[py] " + line);
      }
    });
    proc.stderr.setEncoding("utf8");
    proc.stderr.on("data", (d) => process.stderr.write("[py:err] " + d));
    proc.on("error", reject);
  });
}

function waitExit(proc) {
  return new Promise((resolve) => proc.on("exit", (code) => resolve(code)));
}

async function main() {
  const { proc, port } = await startCoordinator();
  proc.stdout.on("data", () => {}); // keep flowing (handler above already logs)
  const agent = new AgentNode("hlxjsagent", SECRET, makeUppercaseRunner("hlxjsagent"));
  await agent.connect("127.0.0.1", port);

  const code = await waitExit(proc);
  agent.close();

  if (code !== 0) {
    console.error(`\nFAILED — coordinator exited ${code}`);
    process.exit(1);
  }
  if (agent.tasksServed < 4) {
    console.error(`\nFAILED — JS agent served only ${agent.tasksServed} tasks (expected >= 4)`);
    process.exit(1);
  }
  console.log(`\nALL PASSED — JS agent joined the real HELIX mesh and served ${agent.tasksServed} tasks (Level 2).`);
}

main().catch((err) => { console.error(err); process.exit(1); });
