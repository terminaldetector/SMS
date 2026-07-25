// Cross-language proof for the IN-APP sharding planner: ChatterUI/lib/helixPlacement.ts must agree
// with the Python reference (helix/rpc_cluster.py + helix/placement.py) on the ring, the per-node
// layer bands, the --rpc list and the --tensor-split ratios.
//
//   node integration/chatterui_llamacpp/js/shard_plan_smoke.mjs
//
// This matters because the no-PC path plans the shard on the HOST PHONE — there is no Python
// coordinator in the loop to fall back on, so the phone's planner has to be the same planner.
// The TS file is imported directly (Node strips the types), so there is no .mjs twin to drift.

import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

import { allocateLayers, planRpcCluster } from '../../../ChatterUI/lib/helixPlacement.ts'

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const PY = process.env.PYTHON || 'python3'
const GB = 2 ** 30

let pass = 0
const check = (c, w) => {
  if (!c) throw new Error('FAIL: ' + w)
  pass++
  console.log('  ok  ' + w)
}

// Ask the real Python planner for the same case and return its plan dict.
function pythonPlan({ members, addrs, modelId, nLayers, modelBytes, order }) {
  const src = `
import json
from helix.placement import Capacity
from helix.rpc_cluster import plan_rpc_cluster
members = {k: Capacity(v) for k, v in json.loads('''${JSON.stringify(members)}''').items()}
addrs = json.loads('''${JSON.stringify(addrs)}''')
order = json.loads('''${JSON.stringify(order ?? null)}''')
plan = plan_rpc_cluster(members, addrs, ${JSON.stringify(modelId)}, ${nLayers}, ${modelBytes}, order=order)
print(json.dumps(plan.to_dict()))
`
  const r = spawnSync(PY, ['-c', src], { cwd: REPO, env: { ...process.env, PYTHONPATH: '.' }, encoding: 'utf8' })
  if (r.status !== 0) throw new Error('python planner failed: ' + (r.stderr || r.stdout))
  return JSON.parse(r.stdout.trim())
}

const close = (a, b) => a.length === b.length && a.every((x, i) => Math.abs(x - b[i]) < 1e-9)

function compare(name, spec) {
  const py = pythonPlan(spec)
  const memberCaps = Object.fromEntries(
    Object.entries(spec.members).map(([k, v]) => [k, { mem_bytes: v }])
  )
  const js = planRpcCluster(memberCaps, spec.addrs, spec.modelId, spec.nLayers, spec.modelBytes, spec.order)

  check(JSON.stringify(js.ring) === JSON.stringify(py.ring), `${name}: ring matches Python ${JSON.stringify(py.ring)}`)
  check(js.main === py.main, `${name}: main node matches (${py.main})`)
  check(js.rpc_arg === py.rpc_arg, `${name}: --rpc list matches ("${py.rpc_arg}")`)
  check(close(js.tensor_split, py.tensor_split), `${name}: --tensor-split matches [${py.tensor_split.map((x) => x.toFixed(3))}]`)
  const jsBands = JSON.stringify(js.endpoints.map((e) => [e.node, e.band, e.role]))
  const pyBands = JSON.stringify(py.endpoints.map((e) => [e.node, e.band, e.role]))
  check(jsBands === pyBands, `${name}: per-node layer bands + roles match`)
}

// 1. Three phones, memory-weighted 8:4:4 — the Python selftest's own case.
compare('3 phones 8:4:4', {
  members: { hlxA: 8 * GB, hlxB: 4 * GB, hlxC: 4 * GB },
  addrs: { hlxA: '10.0.0.1:50052', hlxB: '10.0.0.2:50052', hlxC: '10.0.0.3:50052' },
  modelId: 'big-16b', nLayers: 6, modelBytes: 3 * GB, order: ['hlxA', 'hlxB', 'hlxC'],
})

// 2. The actual no-PC shape: two phones, uneven RAM, a real layer count.
compare('2 phones 12:6, 32 layers', {
  members: { host: 12 * GB, agent: 6 * GB },
  addrs: { host: '192.168.1.5:50052', agent: '192.168.1.9:50052' },
  modelId: 'mid-7b', nLayers: 32, modelBytes: 5 * GB, order: ['host', 'agent'],
})

// 3. Remainders that don't divide evenly — where largest-remainder rounding actually shows.
compare('3 phones 5:3:2, 17 layers', {
  members: { a: 5 * GB, b: 3 * GB, c: 2 * GB },
  addrs: { a: 'a:50052', b: 'b:50052', c: 'c:50052' },
  modelId: 'odd', nLayers: 17, modelBytes: 6 * GB, order: ['a', 'b', 'c'],
})

// 4. Default ordering (no explicit `order`) — Python sorts member ids, JS must too.
compare('default id ordering', {
  members: { zeta: 4 * GB, alpha: 8 * GB, mid: 4 * GB },
  addrs: { zeta: 'z:50052', alpha: 'a:50052', mid: 'm:50052' },
  modelId: 'sorted', nLayers: 8, modelBytes: 4 * GB, order: null,
})

// Guardrails: the planner must refuse rather than hand back an unrunnable plan.
const mustThrow = (fn, what) => {
  try {
    fn()
  } catch {
    check(true, what)
    return
  }
  throw new Error('FAIL (did not throw): ' + what)
}

mustThrow(
  () => planRpcCluster({ a: { mem_bytes: 1 * GB } }, { a: 'a:1' }, 'm', 4, 8 * GB),
  'refuses a ring whose combined memory is too small'
)
mustThrow(
  () =>
    planRpcCluster(
      { a: { mem_bytes: 8 * GB }, b: { mem_bytes: 8 * GB } },
      { a: 'a:1' }, // b has no rpc address
      'm', 4, 4 * GB
    ),
  'refuses when a ring node announced no rpc address'
)
mustThrow(
  () => planRpcCluster({ a: { mem_bytes: 8 * GB }, b: { mem_bytes: 8 * GB } }, { a: 'a:1', b: 'b:1' }, 'm', 1, 1 * GB),
  'refuses more nodes than layers'
)

// Every node gets at least one layer even when its weight rounds to zero.
const alloc = allocateLayers(4, [0.97, 0.01, 0.01, 0.01])
check(alloc.every((x) => x >= 1) && alloc.reduce((s, x) => s + x, 0) === 4,
  `every node keeps >=1 layer under lopsided weights (${alloc.join('/')})`)

console.log(`\nALL PASSED (${pass} checks) — the in-app planner agrees with the Python reference (no-PC sharding).`)
