// Proves speed-weighted layer placement against the real app source.
//
// The planner used to weigh memory alone, which answers the wrong question: layers run in sequence,
// so a token costs sum(layers_i / speed_i), and the phone with the most free RAM getting the biggest
// band means the slowest phone can set the pace for every token the mesh produces.
//
// This is arithmetic that fails silently — a bad split still loads, still answers, and only shows up
// as "the mesh is slow", which is indistinguishable from the network being slow. So the properties
// are pinned here rather than trusted.
//
//   node integration/chatterui_llamacpp/js/placement_speed_smoke.mjs

import { allocateLayersCapped, planPlacement, planRpcCluster } from '../../../ChatterUI/lib/helixPlacement.ts'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }
const sum = (a) => a.reduce((s, x) => s + x, 0)
const GB = 1024 ** 3

// --- layers follow speed when memory is not binding ---
{
  const alloc = allocateLayersCapped(30, [3, 1], [30, 30])
  check(sum(alloc) === 30, 'every layer is placed')
  check(alloc[0] > alloc[1], 'the faster phone gets more layers')
  check(
    Math.abs(alloc[0] / alloc[1] - 3) < 0.35,
    `and roughly in proportion to speed (${alloc[0]}:${alloc[1]} for a 3:1 split)`
  )
}

// --- memory is a hard cap, not a preference ---
{
  // The fast phone can only hold 5 layers; the rest must go somewhere regardless of speed.
  const alloc = allocateLayersCapped(30, [10, 1], [5, 30])
  check(sum(alloc) === 30, 'every layer is still placed when the fast phone is capped')
  check(alloc[0] === 5, 'the fast phone is pinned at exactly what it can hold, not what it deserves')
  check(alloc[1] === 25, 'and the overflow goes to the phone with room')
}

// --- pinning cascades: capping one node can push the next over its own cap ---
{
  const alloc = allocateLayersCapped(30, [10, 10, 1], [4, 6, 30])
  check(sum(alloc) === 30, 'every layer is placed after two nodes pin')
  check(alloc[0] === 4 && alloc[1] === 6, 'both capped nodes sit at their caps')
  check(alloc[2] === 20, 'the slow node absorbs what neither fast node could hold')
}

// --- a mesh that cannot hold the model says so, in layers ---
{
  let threw = ''
  try { allocateLayersCapped(30, [1, 1], [5, 5]) } catch (e) { threw = e.message }
  check(/can hold 10 of the model's 30 layers/.test(threw), `refused with the shortfall named (${threw})`)
}

// --- nobody in the ring runs zero layers ---
{
  // A very slow third phone would round to nothing on proportion alone.
  const alloc = allocateLayersCapped(20, [100, 100, 0.001], [20, 20, 20])
  check(alloc.every((x) => x >= 1), `every node in the ring runs at least one layer (${alloc})`)
  check(sum(alloc) === 20, 'and the total is still right after the correction')
}

// --- unmeasured phones fall back to an even split rather than dividing by zero ---
{
  const alloc = allocateLayersCapped(9, [0, 0, 0], [9, 9, 9])
  check(sum(alloc) === 9, 'all layers placed with no speed data at all')
  check(alloc.every((x) => x === 3), `and split evenly rather than arbitrarily (${alloc})`)
}

// --- planPlacement: speed weighting is OPT-IN ---
{
  // Same memory, very different speed. Without `cpu` the split must stay memory-proportional —
  // this is what keeps shard_plan_smoke's parity with the Python planner meaningful.
  const mem = { a: { mem_bytes: 4 * GB }, b: { mem_bytes: 4 * GB } }
  const flat = planPlacement(mem, 32, 4 * GB, ['a', 'b'])
  const aFlat = flat.bands.a.end - flat.bands.a.start
  check(aFlat === 16, `no cpu reported: the memory-weighted split is unchanged (${aFlat}/32)`)

  const fast = {
    a: { mem_bytes: 4 * GB, cpu: 300 },
    b: { mem_bytes: 4 * GB, cpu: 100 },
  }
  const tilted = planPlacement(fast, 32, 4 * GB, ['a', 'b'])
  const aFast = tilted.bands.a.end - tilted.bands.a.start
  check(aFast > aFlat, `cpu reported: the faster phone takes more (${aFast} vs ${aFlat})`)
  check(
    tilted.bands.a.start === 0 && tilted.bands.b.end === 32,
    'bands still cover every layer with no gap'
  )
}

// --- the case this was built for: big slow phone, small fast phone ---
{
  // Memory alone would hand the 6 GB phone most of the model even though it is a third the speed.
  const members = {
    slowBig: { mem_bytes: 6 * GB, cpu: 100 },
    fastSmall: { mem_bytes: 3 * GB, cpu: 300 },
  }
  const p = planPlacement(members, 32, 4 * GB, ['slowBig', 'fastSmall'])
  const slow = p.bands.slowBig.end - p.bands.slowBig.start
  const fast = p.bands.fastSmall.end - p.bands.fastSmall.start
  check(fast > slow, `the fast small phone out-earns the slow big one (${fast} vs ${slow})`)
  // And it still has to fit: 4 GB over 32 layers is 0.125 GB/layer, so 3 GB caps it at 24.
  check(fast <= 24, `without being given more than it can hold (${fast} <= 24)`)
}

// --- end to end through planRpcCluster, which is what the app actually calls ---
{
  const members = {
    host: { mem_bytes: 4 * GB, cpu: 250 },
    w1: { mem_bytes: 4 * GB, cpu: 50 },
  }
  const addrs = { host: '192.168.1.10:50052', w1: '192.168.1.11:50052' }
  const plan = planRpcCluster(members, addrs, 'm', 32, 3 * GB, ['host', 'w1'])
  check(plan.ring.length === 2, 'the ring is intact')
  check(Math.abs(sum(plan.tensor_split) - 1) < 1e-6, 'tensor_split still sums to 1')
  check(
    plan.tensor_split[0] > plan.tensor_split[1],
    `the ratios follow speed too (${plan.tensor_split.map((x) => x.toFixed(3))})`
  )
  const covered = plan.endpoints.reduce((n, e) => n + (e.band[1] - e.band[0]), 0)
  check(covered === 32, 'and the bands cover every layer')
}

console.log(`\nALL PASSED (${pass} checks) — layers follow speed, capped by memory.`)
