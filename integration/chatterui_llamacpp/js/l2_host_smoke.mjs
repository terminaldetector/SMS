// End-to-end proof of the ChatterUI *Level 2* first-experiment loop: an agent joins the mesh over
// TCP, then a prompt POSTed over HTTP is routed to that agent, whose runner produces the answer.
//
//   node integration/chatterui_llamacpp/js/l2_host_smoke.mjs
//
// Spawns helix/host/agent_host_demo.py (coordinator + HTTP trigger). The JS AgentNode (the stand-in
// for the phone's model) joins via TCP; then a fetch POST /cmd {infer} routes the task to it and we
// assert the agent's transform came back. On-device this is: phone runs HelixAgentNode with
// makeLlamaAgentRunner; you POST a prompt; the phone's GGUF model answers via the mesh.

import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { AgentNode, makeUppercaseRunner } from './agent_node.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const repo = path.resolve(here, '../../..')
const PY = process.env.PYTHON || 'python3'
const SECRET = 'helix-agent-host-demo' // must match agent_host_demo.py

function start() {
  return new Promise((resolve, reject) => {
    const proc = spawn(PY, ['-m', 'helix.host.agent_host_demo', '--host', '127.0.0.1',
      '--tcp-port', '0', '--http-port', '0'], { cwd: repo, env: { ...process.env, PYTHONPATH: repo } })
    const ports = {}
    let out = ''
    const timer = setTimeout(() => reject(new Error('demo did not report ports')), 20000)
    const resolveMaybe = () => {
      if (ports.tcp !== undefined && ports.http !== undefined) {
        clearTimeout(timer)
        resolve({ proc, ...ports })
      }
    }
    proc.stdout.setEncoding('utf8')
    proc.stdout.on('data', (d) => {
      out += d
      let nl
      while ((nl = out.indexOf('\n')) >= 0) {
        const line = out.slice(0, nl); out = out.slice(nl + 1)
        const t = line.match(/^TCP_PORT (\d+)/)
        const hp = line.match(/^HTTP_PORT (\d+)/)
        if (t) { ports.tcp = Number(t[1]); global.__onTcp?.(ports.tcp) }
        else if (hp) { ports.http = Number(hp[1]) }
        else console.log('[py] ' + line)
        resolveMaybe()
      }
    })
    proc.stderr.setEncoding('utf8')
    proc.stderr.on('data', (d) => process.stderr.write('[py:err] ' + d))
    proc.on('error', reject)
    // connect the agent as soon as the TCP port is known (before HTTP comes up)
    global.__onTcp = (tcpPort) => {
      const agent = new AgentNode('hlxphone', SECRET, makeUppercaseRunner('hlxphone'))
      ports._agent = agent
      agent.connect('127.0.0.1', tcpPort).catch(reject)
    }
  })
}

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }
const cmd = async (base, obj) =>
  (await fetch(base + '/cmd', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(obj) })).json()

async function main() {
  const { proc, tcp, http, _agent } = await start()
  const base = `http://127.0.0.1:${http}`
  try {
    check(tcp > 0 && http > 0, `coordinator up (TCP ${tcp}, HTTP ${http}) with agent joined`)

    const nodes = await cmd(base, { cmd: 'nodes' })
    check(nodes.ok && nodes.agents.includes('hlxphone'), 'phone agent visible in the mesh')

    const single = await cmd(base, { cmd: 'infer', prompt: 'hello mesh', mode: 'single', skill: 'chat' })
    check(single.ok && single.result === 'HELLO MESH', `infer routed to phone agent -> "${single.result}"`)

    const voting = await cmd(base, { cmd: 'infer', prompt: 'vote please', mode: 'voting', skill: 'chat', n: 1 })
    check(voting.ok && voting.result === 'VOTE PLEASE', 'voting routed to phone agent')

    check(_agent.tasksServed >= 2, `phone agent served ${_agent.tasksServed} task(s)`)
    console.log(`\nALL PASSED (${pass} checks) — phone agent joined the mesh; HTTP prompts routed to it (Level 2 loop).`)
  } finally {
    _agent?.close()
    proc.kill('SIGTERM')
  }
}

main().catch((err) => { console.error(err); process.exit(1) })
