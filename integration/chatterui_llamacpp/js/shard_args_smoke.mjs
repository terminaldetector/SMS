// Cross-language proof of the step where sharding was actually broken: turning a HELIX plan into
// llama.cpp's own arguments.
//
//   node integration/chatterui_llamacpp/js/shard_args_smoke.mjs
//
// The plan was never the problem — shard_plan_smoke.mjs has agreed with the Python reference all
// along. The problem was what the plan meant to llama.cpp:
//
//   * --tensor-split is indexed by llama.cpp's DEVICE list ([rpc devices…, local GPUs…]); the
//     driving phone's CPU is not in it. Feeding it the plan's ring-wide ratios shifted every
//     worker's share by one slot, and with a single worker put every offloaded layer on that phone.
//   * -ngl is how many TRAILING layers may leave the driver, not how many the model has. The block
//     count meant the driver kept nothing and the workers were asked for the whole model.
//
// Both mistakes load and answer, so no test that only checks "a plan came back" can catch either.
// This asserts the translation itself, against the real app source (ChatterUI/lib/helixShardArgs.ts)
// and against the Python reference's own copy of the same arithmetic (helix.rpc_cluster
// llama_rpc_args), so the two cannot drift.

import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

import { planRpcCluster } from '../../../ChatterUI/lib/helixPlacement.ts'
import { shardLlamaArgs, rpcDevicesUsed } from '../../../ChatterUI/lib/helixShardArgs.ts'

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const PY = process.env.PYTHON || 'python3'
const GB = 2 ** 30

let pass = 0
const check = (c, w) => {
  if (!c) throw new Error('FAIL: ' + w)
  pass++
  console.log('  ok  ' + w)
}
const close = (a, b) => a.length === b.length && a.every((x, i) => Math.abs(x - b[i]) < 1e-9)
const mustThrow = (fn, needle, what) => {
  try {
    fn()
  } catch (e) {
    check(String(e.message).includes(needle), what)
    return
  }
  throw new Error('FAIL (did not throw): ' + what)
}

// The same translation from the Python reference, for the cross-language check.
function pythonArgs(plan, devicesPerEndpoint) {
  const src = `
import json
from helix.rpc_cluster import llama_rpc_args
plan = json.loads('''${JSON.stringify(plan)}''')
dpe = json.loads('''${JSON.stringify(devicesPerEndpoint ?? null)}''')
print(json.dumps(llama_rpc_args(plan, devices_per_endpoint=dpe).to_dict()))
`
  const r = spawnSync(PY, ['-c', src], { cwd: REPO, env: { ...process.env, PYTHONPATH: '.' }, encoding: 'utf8' })
  if (r.status !== 0) throw new Error('python translation failed: ' + (r.stderr || r.stdout))
  return JSON.parse(r.stdout.trim())
}

// The shape the logs came from: a 32-layer model, host with 2.80 GB free, worker with 1.30 GB.
const plan = planRpcCluster(
  { host: { mem_bytes: 2.8 * GB }, worker: { mem_bytes: 1.3 * GB } },
  { host: '10.11.193.182:50052', worker: '10.11.193.138:50052' },
  'qwen3-4b',
  32,
  2.52 * GB,
  ['host', 'worker']
)

// --- one worker: the case that used to collapse onto that worker ---
{
  const registered = [{ endpoint: '10.11.193.138:50052', devices: ['RPC0'] }]
  const args = shardLlamaArgs(plan, registered)
  const hostBand = plan.endpoints[0].band
  const workerBand = plan.endpoints[1].band

  check(args.host_layers === hostBand[1] - hostBand[0], `the host keeps its own band (${args.host_layers} layers)`)
  check(
    args.remote_layers === workerBand[1] - workerBand[0],
    `only the worker's band leaves the phone (${args.remote_layers} layers)`
  )
  check(
    args.n_gpu_layers === 32 + 1 - args.host_layers,
    `-ngl is the remote tail plus the output layer, not the block count (${args.n_gpu_layers}, not 32)`
  )
  check(args.n_gpu_layers < 32, '-ngl is smaller than the model — the host is not asked to give up everything')
  check(close(args.tensor_split, [1]), 'a lone worker holds all of the offloaded tail: [1]')
  check(JSON.stringify(args.devices) === JSON.stringify(['RPC0']), 'the split names the remote device it applies to')
  check(
    JSON.stringify(args.rpc_servers) === JSON.stringify(['10.11.193.138:50052']),
    '--rpc lists the workers, in ring order'
  )
  // The bug, stated as a test: the plan's own ratios are NOT these ratios.
  check(
    plan.tensor_split.length !== args.tensor_split.length,
    'the plan spans the ring, the arguments span the devices — different lengths on purpose'
  )
}

// --- two workers: where the off-by-one slot was fatal rather than merely wrong ---
{
  const three = planRpcCluster(
    { host: { mem_bytes: 4 * GB }, w1: { mem_bytes: 3 * GB }, w2: { mem_bytes: 1 * GB } },
    { host: 'h:50052', w1: 'a:50052', w2: 'b:50052' },
    'mid-7b',
    32,
    5 * GB,
    ['host', 'w1', 'w2']
  )
  const args = shardLlamaArgs(three, [
    { endpoint: 'a:50052', devices: ['RPC0'] },
    { endpoint: 'b:50052', devices: ['RPC1'] },
  ])
  const [w1, w2] = three.endpoints.slice(1)
  const remote = args.remote_layers
  check(
    close(args.tensor_split, [(w1.band[1] - w1.band[0]) / remote, (w2.band[1] - w2.band[0]) / remote]),
    'each worker gets its own band as a share of the tail, in ring order'
  )
  check(
    Math.abs(args.tensor_split.reduce((a, b) => a + b, 0) - 1) < 1e-9,
    'the ratios sum to 1 over the workers alone'
  )
  check(
    args.tensor_split[0] !== three.tensor_split[0],
    'the first ratio is the first WORKER, not the driver — the shift that gave a worker the host share'
  )
}

// --- one address, two backends: a phone serving its CPU and its GPU is two split slots ---
{
  const args = shardLlamaArgs(plan, [
    { endpoint: '10.11.193.138:50052', devices: ['RPC0', 'RPC1'] },
  ])
  check(args.devices.length === 2 && args.tensor_split.length === 2, 'two devices behind one address take two slots')
  check(close(args.tensor_split, [0.5, 0.5]), "that phone's band is divided between its own backends")
}

// --- an unreachable worker is refused here, not discovered as a suspiciously local shard ---
mustThrow(
  () => shardLlamaArgs(plan, [{ endpoint: '10.11.193.138:50052', devices: [] }]),
  'offered no device',
  'a worker that answered with no device is an error before the load, with its address in it'
)
mustThrow(
  () => shardLlamaArgs(plan, []),
  'offered no device',
  'a worker that was never registered at all is caught the same way'
)

// --- a plan llama.cpp cannot express is refused rather than mistranslated ---
mustThrow(
  () =>
    shardLlamaArgs(
      { ...plan, endpoints: plan.endpoints.map((e) => (e.role === 'main' ? { ...e, band: [4, 8] } : e)) },
      [{ endpoint: '10.11.193.138:50052', devices: ['RPC0'] }]
    ),
  'first band',
  'a driver that does not hold the first band is refused — llama.cpp can only offload the tail'
)
mustThrow(
  () =>
    shardLlamaArgs(
      { ...plan, endpoints: [plan.endpoints[0]], ring: ['host'] },
      []
    ),
  'no workers',
  'a plan with no workers is called a local load rather than silently shardable'
)

// --- the load-time check: which devices actually held layers ---
{
  check(rpcDevicesUsed(['RPC0', 'CPU']).length === 1, 'a remote device in the loaded list is recognised')
  check(rpcDevicesUsed(['CPU']).length === 0, 'a local-only load reports no remote device — the silent failure')
  check(rpcDevicesUsed(undefined).length === 0, 'a context that reported no devices at all is not mistaken for a shard')
}

// --- and the Python reference agrees, argument for argument ---
{
  const cases = [
    ['one worker', plan, { '10.11.193.138:50052': ['RPC0'] }],
    ['one worker, two backends', plan, { '10.11.193.138:50052': ['RPC0', 'RPC1'] }],
  ]
  for (const [name, p, dpe] of cases) {
    const py = pythonArgs(p, dpe)
    const js = shardLlamaArgs(
      p,
      Object.entries(dpe).map(([endpoint, devices]) => ({ endpoint, devices }))
    )
    check(js.n_gpu_layers === py.n_gpu_layers, `${name}: -ngl matches Python (${py.n_gpu_layers})`)
    check(close(js.tensor_split, py.tensor_split), `${name}: --tensor-split matches Python`)
    check(JSON.stringify(js.devices) === JSON.stringify(py.devices), `${name}: the device list matches Python`)
    check(
      js.host_layers === py.host_layers && js.remote_layers === py.remote_layers,
      `${name}: the same layers stay home (${py.host_layers}) and leave (${py.remote_layers})`
    )
  }
}

console.log(`\nALL PASSED (${pass} checks) — the plan reaches llama.cpp meaning what it says.`)
