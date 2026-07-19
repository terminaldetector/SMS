// End-to-end proof of the ChatterUI *Level 2 over WebSocket* loop (NO native module): a WebSocket
// agent joins the mesh, then a prompt POSTed over HTTP is routed to it and its runner answers.
//
//   node integration/chatterui_llamacpp/js/l2_ws_smoke.mjs
//
// Spawns helix/host/agent_host_ws_demo.py (coordinator: WebSocket agent link + HTTP trigger). The
// Node WsAgentNode (stand-in for the phone) connects with the built-in WebSocket; then fetch POST
// /cmd {infer} routes the task to it. On-device this is identical: ChatterUI's WebSocketAgentTransport
// + makeLlamaAgentRunner — the phone's GGUF model answers via the mesh, no native module.

import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { WsAgentNode } from './ws_agent_node.mjs'
import { makeUppercaseRunner } from './agent_node.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(here, '../../..')
const PY = process.env.PYTHON || 'python3'
const SECRET = 'helix-agent-host-ws-demo' // must match agent_host_ws_demo.py

function start() {
  return new Promise((resolve, reject) => {
    const proc = spawn(PY, ['-m', 'helix.host.agent_host_ws_demo', '--host', '127.0.0.1',
      '--ws-port', '0', '--http-port', '0'], { cwd: repo, env: { ...process.env, PYTHONPATH: repo } })
    const ports = {}
    let out = ''
    const timer = setTimeout(() => reject(new Error('demo did not report ports')), 20000)
    const done = () => { if (ports.ws !== undefined && ports.http !== undefined) { clearTimeout(timer); resolve({ proc, ...ports }) } }
    proc.stdout.setEncoding('utf8')
    proc.stdout.on('data', (d) => {
      out += d
      let nl
      while ((nl = out.indexOf('\n')) >= 0) {
        const line = out.slice(0, nl); out = out.slice(nl + 1)
        const w = line.match(/^WS_PORT (\d+)/)
        const h = line.match(/^HTTP_PORT (\d+)/)
        if (w) { ports.ws = Number(w[1]); global.__onWs?.(ports.ws) }
        else if (h) ports.http = Number(h[1])
        else console.log('[py] ' + line)
        done()
      }
    })
    proc.stderr.setEncoding('utf8')
    proc.stderr.on('data', (d) => process.stderr.write('[py:err] ' + d))
    proc.on('error', reject)
    // connect the agent as soon as the WS port is known (before the HTTP port comes up)
    global.__onWs = (wsPort) => {
      const agent = new WsAgentNode('hlxphone', SECRET, makeUppercaseRunner('hlxphone'))
      ports._agent = agent
      agent.connect(`ws://127.0.0.1:${wsPort}`).catch(reject)
    }
  })
}

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }
const cmd = async (base, obj) =>
  (await fetch(base + '/cmd', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(obj) })).json()

async function main() {
  const { proc, ws, http, _agent } = await start()
  const base = `http://127.0.0.1:${http}`
  try {
    check(ws > 0 && http > 0, `coordinator up (WS ${ws}, HTTP ${http}) with agent joined`)

    const nodes = await cmd(base, { cmd: 'nodes' })
    check(nodes.ok && nodes.agents.includes('hlxphone'), 'phone agent visible in the mesh (over WebSocket)')

    const single = await cmd(base, { cmd: 'infer', prompt: 'hello ws mesh', mode: 'single', skill: 'chat' })
    check(single.ok && single.result === 'HELLO WS MESH', `infer routed over WS -> "${single.result}"`)

    const voting = await cmd(base, { cmd: 'infer', prompt: 'vote ws', mode: 'voting', skill: 'chat', n: 1 })
    check(voting.ok && voting.result === 'VOTE WS', 'voting routed over WS')

    check(_agent.tasksServed >= 2, `phone agent served ${_agent.tasksServed} task(s)`)
    console.log(`\nALL PASSED (${pass} checks) — WebSocket agent joined the mesh; HTTP prompts routed to it (Level 2, no native module).`)
  } finally {
    _agent?.close()
    proc.kill('SIGTERM')
  }
}

main().catch((err) => { console.error(err); process.exit(1) })
