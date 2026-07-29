// One place that knows how each mesh mode actually produces an answer.
//
// It was inline in the mesh screen, which was fine while it was two buttons and stopped being fine
// the moment a second screen needed the same thing. The two modes are genuinely different calls,
// not one call with a flag — Pointer hands a prompt to another phone and waits for a whole answer;
// Sharder runs this phone's own context, which happens to have most of its layers somewhere else.

import { Llama } from './engine/Local/LlamaLocal'
import type { MeshSend } from './helixChatTokens'
import { cleanMeshAnswer } from './helixPrompt'
import { meshSession, MeshMode } from './helixSession'

export interface MeshRunOptions {
    images?: string[]
    nPredict?: number
    onToken?: (chunk: string) => void
}

export async function runMeshTurn(
    mode: MeshMode,
    send: MeshSend,
    { images = [], nPredict = 512, onToken }: MeshRunOptions = {}
): Promise<string> {
    if (mode === 'pointer') {
        const coord = meshSession.coord
        if (!coord) throw new Error('not hosting a mesh')
        if (coord.agents().length === 0) throw new Error('no phone has joined the mesh')
        // No stream: an agent returns its answer whole. Rather than fake a stream by chopping the
        // finished text, the caller simply gets one chunk — a progress bar made of an answer that
        // already arrived would be a lie about where the waiting happened.
        const answer = await coord.infer(send.prompt, meshSession.answerMode)
        onToken?.(answer)
        return answer
    }

    const store = Llama.useLlamaModelStore.getState()
    if (!store.context) throw new Error('no model is loaded on this phone')
    let out = ''
    await store.completion(
        {
            // `messages` when the model has a chat template: the native side then applies the
            // template, its end-of-turn token and its own extra stops. `prompt` is the same text
            // already formatted — passing both would format twice, so it is one or the other.
            ...(send.messages
                ? { messages: send.messages, jinja: true, enable_thinking: false }
                : { prompt: send.prompt }),
            stop: send.stop,
            n_predict: nPredict,
            // Only when there is a projector — the store warns and drops them otherwise, and a
            // silently ignored image looks exactly like a model that cannot see.
            ...(images.length && store.mmproj
                ? { media_paths: images.map((i) => i.replace('file://', '')) }
                : {}),
        },
        (t: string) => {
            out += t
            onToken?.(t)
        },
        () => {}
    )
    return cleanMeshAnswer(out, send.formatting)
}

/** Why this mode cannot answer right now, or undefined when it can. */
export function meshRunBlocker(mode: MeshMode): string | undefined {
    if (mode === 'hybrid') return 'Hybrid is a placeholder — pick Pointer or Sharder in HELIX Mesh.'
    if (mode === 'pointer') {
        if (!meshSession.coord) return 'Not hosting — start hosting in HELIX Mesh.'
        if (meshSession.coord.agents().length === 0) return 'No phone has joined the mesh yet.'
        return undefined
    }
    const store = Llama.useLlamaModelStore.getState()
    if (!store.context) return 'Nothing is loaded — start the shard in HELIX Mesh first.'
    if (!store.sharded)
        return 'The model here is loaded whole, not split. Start the shard in HELIX Mesh to test the mesh.'
    return undefined
}
