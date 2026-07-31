// Proves the shard report's self-checks against the real app source.
//
// The report exists because a bad split is invisible: the model loads, the chat answers, and the
// only symptom is slowness or a phone dying mid-answer. Its value is entirely in catching the
// arithmetic that would otherwise pass unnoticed — so the checks themselves have to be known to
// fire. A checker that silently approves everything is worse than none, because it is reassuring.
//
// Plans here are built by hand, including deliberately broken ones. Well-formed plans come from
// the real planner, so the happy path is proved against what the app actually produces.
//
//   node integration/chatterui_llamacpp/js/shard_log_smoke.mjs

import { checkShardPlan, formatShardMemory, formatShardPlan, formatShardLoaded } from '../../../ChatterUI/lib/helixShardLog.ts'
import { planRpcCluster } from '../../../ChatterUI/lib/helixPlacement.ts'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }
const GB = 1024 ** 3

// --- a plan straight from the real planner passes every check ---
const members = { host: { mem_bytes: 4 * GB }, w1: { mem_bytes: 2 * GB } }
const addrs = { host: '192.168.1.10:50052', w1: '192.168.1.11:50052' }
const good = planRpcCluster(members, addrs, 'qwen3-4b', 36, 3 * GB, ['host', 'w1'])
const nodes = [
  { id: 'host', mem: 4 * GB, rpc: addrs.host },
  { id: 'w1', mem: 2 * GB, rpc: addrs.w1 },
]
{
  const problems = checkShardPlan(good, nodes)
  check(problems.length === 0, `a real plan raises no problems (${JSON.stringify(problems)})`)
}

// --- the report says the things a person is reading it to find out ---
{
  const text = formatShardPlan(good, { name: 'qwen3-4b', bytes: 3 * GB, layers: 36 }, nodes)
  check(text.includes('SHARD PLAN'), 'the report is delimited so it can be found in a long log')
  check(text.includes('host → w1'), 'the ring is printed in pipeline order')
  check(text.includes('--rpc: 192.168.1.11:50052'), 'the exact --rpc argument is shown')
  check(text.includes('--tensor-split:'), 'the exact tensor-split is shown')
  check(/layers \d+–\d+/.test(text), 'each node reports the layer band it was given')
  check(text.includes('36 layers'), "the model's layer count is stated")
  check(!text.includes('PROBLEMS:'), 'a sound plan does not invent problems')
}

// --- a phone given more than it offered is called out ---
{
  // Together these two offer ~1 GB against a 3 GB model, and w1's share alone exceeds what it has.
  const tight = [
    { id: 'host', mem: 1 * GB, rpc: addrs.host },
    { id: 'w1', mem: 64 * 1024 * 1024, rpc: addrs.w1 },
  ]
  const text = formatShardPlan(good, { name: 'qwen3-4b', bytes: 3 * GB, layers: 36 }, tight)
  check(text.includes('MORE THAN IT OFFERED'), 'a node asked to hold more than it offered is flagged')
  check(text.includes('TIGHT'), 'a mesh smaller than the model warns before the load, not after')
}

// --- layers that nobody runs ---
{
  const broken = { ...good, endpoints: good.endpoints.map((e, i) => (i === 0 ? { ...e, band: [0, 1] } : e)) }
  const problems = checkShardPlan(broken, nodes)
  check(problems.some((p) => p.includes('unplaced')), 'bands that do not cover every layer are caught')
  check(problems.some((p) => p.includes('gap or overlap')), 'the gap between two bands is named')
}

// --- ratios that do not sum to one ---
{
  const broken = { ...good, tensor_split: [0.5, 0.2] }
  const problems = checkShardPlan(broken, nodes)
  check(
    problems.some((p) => p.includes('sums to')),
    'a tensor_split that does not sum to 1 is caught — llama.cpp would not divide as planned'
  )
}

// --- a worker in the ring that nothing addresses ---
{
  const broken = { ...good, rpc_arg: '' }
  const problems = checkShardPlan(broken, nodes)
  check(problems.some((p) => p.includes('--rpc lists')), 'a worker missing from --rpc is caught')
}

// --- two phones announcing the same address ---
{
  const clashing = [
    { id: 'host', mem: 4 * GB, rpc: '192.168.1.10:50052' },
    { id: 'w1', mem: 2 * GB, rpc: '192.168.1.10:50052' },
  ]
  const problems = checkShardPlan(good, clashing)
  check(problems.some((p) => p.includes('same rpc address')), 'a duplicate rpc address is caught')
}

// --- a node that reported no memory ---
{
  const empty = [
    { id: 'host', mem: 4 * GB, rpc: addrs.host },
    { id: 'w1', mem: 0, rpc: addrs.w1 },
  ]
  const problems = checkShardPlan(good, empty)
  check(problems.some((p) => p.includes('no free memory')), 'a node with no memory reading is caught')
}

// --- the load report proves offload really happened, or says it did not ---
{
  const rpcDev = [
    { backend: 'CPU', type: 'CPU', deviceName: 'cpu' },
    { backend: 'RPC', type: 'GPU', deviceName: 'RPC[192.168.1.11:50052]' },
  ]
  const connected = formatShardLoaded(good, 36, 4200, true, rpcDev)
  check(
    connected.includes('layers really left this phone'),
    'a registered RPC device is reported as proof the split was taken'
  )
  check(
    connected.includes('Phones in the ring: 2'),
    'the ring size is stated plainly instead of the ambiguous "Workers: 1"'
  )

  // The case the user suspected: plan made, load succeeded, nothing actually offloaded.
  const cpuOnly = formatShardLoaded(good, 36, 4200, true, [
    { backend: 'CPU', type: 'CPU', deviceName: 'cpu' },
  ])
  check(
    cpuOnly.includes('NO RPC DEVICE REGISTERED'),
    'a load with no RPC device is called out — the split was planned and not taken'
  )
  check(
    cpuOnly.includes('192.168.1.11:50052'),
    'and it names the address that was expected to connect'
  )

  const unread = formatShardLoaded(good, 36, 4200, true, [])
  check(unread.includes('UNVERIFIED'), 'an unreadable device list is admitted, not assumed good')
}

// --- the load report catches the failure that cost the most time ---
{
  const text = formatShardLoaded(good, 0, 4200, true)
  check(
    text.includes('NOTHING left this phone'),
    'n_gpu_layers of 0 is called out — the split was planned and then ignored'
  )
  const ok = formatShardLoaded(good, 36, 4200, true)
  check(!ok.includes('NOTHING left this phone'), 'a real split is not accused of being local')
  check(ok.includes('Context marked sharded: true'), 'the report states whether the context took the split')
}

// --- one node is not a mesh ---
{
  const solo = planRpcCluster({ host: { mem_bytes: 4 * GB } }, { host: addrs.host }, 'm', 36, GB, ['host'])
  const text = formatShardLoaded(solo, 36, 100, true)
  check(text.includes('the ring is one node'), 'a one-node ring is reported as a local load, not a shard')
}

// --- the memory report shows its working, not just its conclusion ---
{
  const text = formatShardMemory(
    { total: 8 * GB, available: 3 * GB, threshold: 0.5 * GB, low: false, usable: 1.65 * GB, profile: 'balanced', fraction: 0.66 },
    '192.168.1.11:50052'
  )
  check(text.includes('RAM total: 8.00 GB'), 'the raw total is shown')
  check(text.includes('OS kill threshold'), "Android's own threshold is shown, since it is subtracted")
  check(text.includes('Headroom: 2.50 GB'), 'the headroom is shown as the arithmetic it is')
  check(text.includes('66% of headroom'), 'the profile fraction is shown')
  check(text.includes('Offering to the mesh: 1.65 GB'), 'the announced figure is shown')
  check(text.includes('192.168.1.11:50052'), 'the address other phones will reach is shown')
}

// --- a phone that is not serving says so ---
{
  const text = formatShardMemory(
    { total: 8 * GB, available: 3 * GB, threshold: 0, low: true, usable: 2 * GB, profile: 'greedy', fraction: 0.85 },
    undefined
  )
  check(text.includes('Not serving layers'), 'a phone with no rpc-server is not mistaken for a worker')
  check(text.includes('LOW MEMORY'), "Android's low-memory flag is surfaced")
}

console.log(`\nALL PASSED (${pass} checks)`)
