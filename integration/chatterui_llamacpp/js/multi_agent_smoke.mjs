// Proves that a mesh with SEVERAL joined phones actually uses them.
//
// It did not. Every task went to agents()[0] no matter how many had joined, so a third phone was
// a line in the UI and nothing else — and `voting` was worse than useless: it waited for votes
// from agents it had never sent the task to, then returned the single answer it did get as though
// something had been decided. Both are invisible from one phone, which is why this test exists and
// why p2p_ws_smoke.mjs (one agent) passed throughout.
//
//   node integration/chatterui_llamacpp/js/multi_agent_smoke.mjs

import { Coordinator } from './coordinator_node.mjs'
import { WsAgentNode } from './ws_agent_node.mjs'

const SECRET = 'helix-multi-agent-demo'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }

async function waitFor(fn, ms = 5000) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (fn()) return true; await new Promise((r) => setTimeout(r, 20)) }
  return false
}

// Each agent signs its answer, so who actually did the work is visible in the result rather than
// inferred. `score` rises with the id, giving voting an unambiguous winner to find.
function makeSigningRunner(agentId, score) {
  return {
    card: () => ({ agent_id: agentId, models: [], skills: ['chat'], task_types: ['chat'] }),
    *infer(prompt) { yield `${prompt} from ${agentId}` },
    score: () => score,
    served: 0,
  }
}

async function main() {
  // Short agent timeout so the "a phone left" check below takes a second, not fifteen.
  const coord = new Coordinator('hostphone', SECRET, { agentTimeoutMs: 800 })
  const port = await coord.listen(0, '127.0.0.1')

  const ids = ['phone-aaa', 'phone-bbb', 'phone-ccc']
  const runners = ids.map((id, i) => makeSigningRunner(id, i + 1))
  const agents = ids.map((id, i) => new WsAgentNode(id, SECRET, runners[i]))

  try {
    for (const a of agents) await a.connect(`ws://127.0.0.1:${port}`)
    check(
      await waitFor(() => ids.every((id) => coord.agents().includes(id))),
      'three phones joined one host at the same time'
    )

    // --- single: consecutive prompts must not all land on the same phone ---
    const answered = new Set()
    for (let i = 0; i < 6; i++) {
      const a = await coord.infer(`q${i}`, 'single')
      const who = a.split('from ')[1]
      answered.add(who)
    }
    check(
      answered.size === ids.length,
      `single-mode work went to every phone, not just the first (${[...answered].sort().join(', ')})`
    )

    // --- voting: every phone must be asked, and the best score must win ---
    const decided = await coord.infer('vote', 'voting')
    check(
      decided === 'vote from phone-ccc',
      `voting picked the highest-scoring candidate (${decided})`
    )

    // A phone that drops out must not take the others' answers with it.
    agents[2].close()
    check(
      await waitFor(() => !coord.agents().includes('phone-ccc')),
      'a phone that leaves is dropped from the mesh'
    )
    const afterLoss = await coord.infer('still here', 'voting')
    check(
      afterLoss.startsWith('still here from phone-'),
      `the remaining phones still answer after one leaves (${afterLoss})`
    )
    check(
      !afterLoss.includes('phone-ccc'),
      'and the phone that left is not credited with an answer'
    )
  } finally {
    // close() and not just ws.close(): the announce interval is what keeps Node's event loop alive,
    // and leaving it running makes this smoke hang forever after passing — which looks identical
    // to a failure, since buffered stdout never flushes.
    for (const a of agents) { try { a.close() } catch {} }
    coord.close()
  }

  console.log(`\nALL PASSED (${pass} checks) — several joined phones actually share the work.`)
}

main().catch((e) => { console.error(e.message ?? e); process.exit(1) })
