// The engine seam, and the shape Hybrid needs from it.
//
// Everything the mesh runs today goes through one engine: llama.cpp via cui-llama.rn, GGUF, with
// RPC for sharding. Hybrid is a second engine alongside it — a shell of small, fast agents (LiteRT,
// and low-level GGUF) doing lookup and synchronous side-work while a core device handles the heavy
// thinking. Two engines, two roles, one mesh.
//
// This file is the seam that makes that possible without pretending it exists yet. It defines what
// an engine has to provide and registers the one that does; the LiteRT engine is declared as
// unavailable rather than stubbed, because a registry entry that answers "yes I can run that" and
// then cannot is worse than an empty registry — it turns a missing feature into a runtime failure
// somewhere far away from the cause.
//
// The reason to write the seam before the engine: `runMeshTurn` currently reaches straight into
// Llama.useLlamaModelStore. Every place that does is a place that has to change when a second
// engine arrives, and they are cheaper to find now than mid-port.

import { edgeLiteRtAvailable } from '../modules/edge-litert'
import { MeshMode } from './helixSession'

export type EngineId = 'gguf' | 'litert'

/** What a device is doing in a Hybrid mesh. */
export type MeshRole =
    /** Holds the heavy model and does the thinking. GGUF, possibly sharded across several phones. */
    | 'core'
    /**
     * Fast, small, many. Lookup, extraction, classification, whatever can be answered in a moment
     * and in parallel — the work that would otherwise queue behind the core's slow generation.
     */
    | 'shell'

export interface EngineCapabilities {
    /** False means declared but not built into this APK — the honest state of LiteRT today. */
    available: boolean
    /** Can hold part of one model while other devices hold the rest (llama.cpp RPC). */
    sharding: boolean
    /** Can accept images when a projector is loaded. */
    vision: boolean
    /** Roughly what this engine is for, in a Hybrid mesh. */
    roles: MeshRole[]
    /** Shown wherever an engine is offered but cannot be picked. */
    note: string
}

// Built once per app run. Availability is a property of the APK, not of anything that changes
// while it is running, and re-asking the native module on every render would be pure cost.
export const ENGINES: Record<EngineId, EngineCapabilities> = {
    gguf: {
        available: true,
        sharding: true,
        vision: true,
        // A GGUF model is the core by nature — big, slow, and the thing worth splitting. It can
        // play shell too, badly: a 4B answering lookups is a shell that costs core money.
        roles: ['core', 'shell'],
        note: 'llama.cpp via cui-llama.rn. Runs Pointer and Sharder today.',
    },
    litert: {
        // Asked of the build rather than declared: the module is only present in an APK that
        // actually compiled it, and a registry claiming an engine it cannot reach turns a missing
        // bridge into a crash somewhere unrelated.
        available: edgeLiteRtAvailable(),
        // Not merely unimplemented: sharding is llama.cpp's RPC backend specifically, and LiteRT
        // has no equivalent. A LiteRT device joins a Hybrid mesh as a whole small agent or not at
        // all — a design constraint, not a to-do.
        sharding: false,
        // LiteRTInference takes images and audio when the model declares support; the load call
        // carries those flags. Only meaningful once the module is actually there.
        vision: edgeLiteRtAvailable(),
        roles: ['shell'],
        note: 'LiteRT-LM via modules/edge-litert. The Hybrid shell: small models answering lookups in parallel while the core thinks.',
    },
}

/** Engines this APK can actually run right now. */
export function availableEngines(): EngineId[] {
    return (Object.keys(ENGINES) as EngineId[]).filter((id) => ENGINES[id].available)
}

/**
 * Which engine a mode uses.
 *
 * Hybrid answers 'gguf' rather than throwing, because a mode that cannot name an engine has nothing
 * to fall back to — and Hybrid's own answer only becomes interesting once a second engine is real.
 */
export function engineForMode(mode: MeshMode): EngineId {
    return 'gguf'
}

/**
 * What each device would be doing in a Hybrid mesh, and why the numbers are what they are.
 *
 * Written down because the split is the whole design and it is not obvious from any one file:
 * shell devices are many and cheap, the core is one job that may itself be spread over several
 * phones by Sharder. Three and three is the shape being aimed at, not a hard limit.
 */
export interface HybridPlan {
    core: { devices: number; engine: EngineId; work: string }
    shell: { devices: number; engine: EngineId; work: string }
}

export const HYBRID_TARGET: HybridPlan = {
    core: {
        devices: 3,
        engine: 'gguf',
        work: 'The heavy model, split across these phones by Sharder. One slow, capable answer at a time.',
    },
    shell: {
        devices: 3,
        engine: 'litert',
        work: 'Small agents answering lookups and extraction in parallel, so that work never queues behind the core.',
    },
}

/** Why Hybrid cannot run yet, in a sentence, or undefined once it can. */
export function hybridBlocker(): string | undefined {
    const missing = HYBRID_TARGET.shell.engine
    if (!ENGINES[missing].available)
        return `Hybrid needs the ${missing} engine for its shell devices, which is not in this build. ${ENGINES[missing].note}`
    return undefined
}
