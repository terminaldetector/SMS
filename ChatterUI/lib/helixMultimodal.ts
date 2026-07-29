// Can this conversation take an image?
//
// Asked of the thing that will actually run the prompt, not of the app in general — the two mesh
// modes answer differently for reasons that are structural, not incidental:
//
//   Sharder — the split model IS this phone's context, so it can take images exactly when a
//             projector is loaded and reports vision. The layers living on other phones changes
//             nothing about that: llama.cpp's RPC moves tensors, and the projector runs before
//             any of them.
//
//   Pointer — cannot. The TASK frame carries `{mode, prompt}`, both text, and the agent's runner
//             signature is (prompt: string, context: string). There is nowhere for an image to
//             go. Worth stating as a fact rather than a missing feature: even with a frame that
//             carried one, the answering phone's model is its own choice and need not have a
//             projector at all, so the capability would have to be announced before it could be
//             relied on. That belongs in the protocol, not in a picker.
//
// The probe is live rather than remembered, because loading and unloading a projector is a thing
// someone does between one message and the next.

import { MeshMode } from './helixSession'

export interface MultimodalStatus {
    /** Whether an image may be attached at all. */
    canAttach: boolean
    vision: boolean
    audio: boolean
    /** Shown to the user — why not, when not. */
    reason: string
}

interface LlamaCtx {
    isMultimodalEnabled?: () => Promise<boolean>
    getMultimodalSupport?: () => Promise<{ vision: boolean; audio: boolean }>
}

export async function probeMultimodal(
    mode: MeshMode,
    context: LlamaCtx | undefined,
    hasMmproj: boolean
): Promise<MultimodalStatus> {
    if (mode === 'pointer')
        return {
            canAttach: false,
            vision: false,
            audio: false,
            reason: 'Pointer sends text only — the task frame carries a prompt, and the phone answering chose its own model. Images work in Sharder, where this phone runs the model.',
        }

    if (!context)
        return {
            canAttach: false,
            vision: false,
            audio: false,
            reason: 'No model is loaded on this phone yet.',
        }

    if (!hasMmproj)
        return {
            canAttach: false,
            vision: false,
            audio: false,
            reason: 'This model has no projector loaded. Load an mmproj file for it in Models to send images.',
        }

    try {
        const enabled = (await context.isMultimodalEnabled?.()) ?? false
        if (!enabled)
            return {
                canAttach: false,
                vision: false,
                audio: false,
                reason: 'The projector did not initialise — the model and mmproj may not be a matching pair.',
            }
        const support = (await context.getMultimodalSupport?.()) ?? { vision: false, audio: false }
        return {
            canAttach: !!support.vision,
            vision: !!support.vision,
            audio: !!support.audio,
            reason: support.vision
                ? support.audio
                    ? 'Images and audio accepted.'
                    : 'Images accepted.'
                : 'The projector loaded but reports no vision support.',
        }
    } catch (e) {
        return {
            canAttach: false,
            vision: false,
            audio: false,
            reason: `Could not ask the model what it supports: ${e instanceof Error ? e.message : String(e)}`,
        }
    }
}
