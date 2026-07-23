// End-to-end proof of the NO-PC android<->android mesh: a JS coordinator (the host phone's role,
// WebSocket server) + a WebSocket agent (the other phone's role) — both roles pure JS, exactly what
// the two ChatterUI phones run. The host phone uses react-native-tcp-socket only for the server
// socket; the agent phone uses the built-in WebSocket (no native module).
//
//   node integration/chatterui_llamacpp/js/p2p_ws_smoke.mjs
//
// Coordinator listens -> agent joins over WebSocket -> coordinator routes a prompt to it -> the
// agent's runner answers. Proves the handshake + framing + coordinator/agent protocol.

import { Coordinator } from './coordinator_node.mjs'
import { WsAgentNode } from './ws_agent_node.mjs'
import { makeUppercaseRunner } from './agent_node.mjs'

const SECRET = 'helix-p2p-ws-demo' // both phones share this cluster secret

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }

async function waitFor(fn, ms = 5000) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (fn()) return true; await new Promise((r) => setTimeout(r, 20)) }
  return false
}

async function main() {
  const coord = new Coordinator('hosthone', SECRET)     // host phone (coordinator)
  const port = await coord.listen(0, '127.0.0.1')
  const agent = new WsAgentNode('hlxphone2', SECRET, makeUppercaseRunner('hlxphone2')) // other phone
  try {
    await agent.connect(`ws://127.0.0.1:${port}`)
    check(await waitFor(() => coord.agents().includes('hlxphone2')), 'agent phone joined the host over WebSocket')

    const single = await coord.infer('hello no pc', 'single')
    check(single === 'HELLO NO PC', `host routed prompt -> agent answered "${single}"`)

    const voting = await coord.infer('vote no pc', 'voting')
    check(voting === 'VOTE NO PC', 'voting routed to the agent phone')

    console.log(`\nALL PASSED (${pass} checks) — two phones mesh directly, NO PC (host=WS server, agent=built-in WebSocket).`)
  } finally {
    agent.close()
    coord.close()
  }
}

main().catch((err) => { console.error(err); process.exit(1) })
