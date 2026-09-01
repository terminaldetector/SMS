// How fast is this phone, relative to the others in the mesh?
//
// The planner needed a second number. It has always weighed memory, which decides what a phone CAN
// hold and says nothing about how long it takes — so the phone with the most free RAM got the
// biggest band, and if it was also the slowest it set the pace for every token. Layers run in
// sequence, so that is the whole mesh waiting on the slowest link.
//
// WHAT THIS MEASURES, AND WHAT IT DOES NOT. A short integer loop in JavaScript, run on the same
// engine (Hermes) on every phone, timed. That is not llama.cpp's throughput and should not be read
// as one: no SIMD, no threads, no matmul, and the result in "ops per second" is not comparable to
// anything outside this app. What it IS good for is the only thing the planner needs — the RATIO
// between two phones running identical code. A phone that scores twice another's is, roughly,
// twice the CPU, and that ordering is what decides who gets more layers.
//
// It is deliberately not a llama.cpp benchmark. Measuring the real thing means loading a model,
// which is the expensive operation the planner runs BEFORE. A number that costs a model load to
// obtain cannot be used to decide how to load the model.

/** Milliseconds the measurement is allowed to take. Long enough to be stable, short enough to sit
 *  in a join without being felt. */
const BUDGET_MS = 120

// Fixed batch between clock reads: reading the clock per iteration would measure the clock.
const BATCH = 200_000

export interface BenchResult {
    /** Arbitrary units — meaningful only against another phone running this same code. */
    opsPerSecond: number
    cores: number
    /** opsPerSecond × cores: what the planner weighs, since llama.cpp uses several threads. */
    score: number
    measuredMs: number
}

let cached: BenchResult | null = null

/**
 * Measure once per app run and remember.
 *
 * Cached because it is a property of the hardware, and because re-running it while a model is
 * loaded would measure a busy phone rather than a capable one. That does mean a phone measured
 * while something else was hammering it keeps a low score for the session — the alternative,
 * re-measuring under load, produces a number that changes for reasons the planner cannot see.
 */
export function benchmarkDevice(cores = deviceCores()): BenchResult {
    if (cached) return cached

    const started = Date.now()
    let ops = 0
    let sink = 0
    // The work itself: cheap integer arithmetic the optimiser cannot fold away, because `sink`
    // escapes below. Anything floating-point would measure the FPU, which is not what decides
    // quantised inference speed.
    while (Date.now() - started < BUDGET_MS) {
        for (let i = 0; i < BATCH; i++) sink += (i * 3) ^ (i >> 2)
        ops += BATCH
    }
    const measuredMs = Math.max(1, Date.now() - started)

    // Reading it keeps the loop from being optimised out; the value is otherwise meaningless.
    if (sink === Number.MAX_SAFE_INTEGER) throw new Error('unreachable')

    const opsPerSecond = (ops / measuredMs) * 1000
    cached = {
        opsPerSecond,
        cores,
        score: opsPerSecond * Math.max(1, cores),
        measuredMs,
    }
    return cached
}

/** The last measurement, without triggering one. */
export function benchmarkIfMeasured(): BenchResult | null {
    return cached
}

function deviceCores(): number {
    try {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const cores = Number(require('expo-device').cpuCores ?? 0)
        if (cores > 0) return cores
    } catch {
        /* fall through */
    }
    // Unknown core count is treated as one rather than guessed at: overstating it would inflate
    // this phone's share of the layers on the strength of nothing.
    return 1
}

export function formatBench(b: BenchResult): string {
    return (
        `${(b.opsPerSecond / 1e6).toFixed(1)}M ops/s × ${b.cores} cores ` +
        `= ${(b.score / 1e6).toFixed(0)}M (measured in ${b.measuredMs}ms)`
    )
}
