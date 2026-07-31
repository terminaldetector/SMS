// One place that knows how each mesh mode actually produces an answer.
//
// It was inline in the mesh screen, which was fine while it was two buttons and stopped being fine
// the moment a second screen needed the same thing. The two modes are genuinely different calls,
// not one call with a flag — Pointer hands a prompt to another phone and waits for a whole answer;
// Sharder runs this phone's own context, which happens to have most of its layers somewhere else.

import { Llama } from './engine/Local/LlamaLocal'
import { ChatMessage, MESH_STOP_SEQUENCES, stripReasoning, trimAtStopSequence } from './helixPrompt'
import { meshSession, MeshMode } from './helixSession'
import { helixReasoning } from './helixSettings'

export interface MeshRunOptions {
    images?: string[]
    nPredict?: number
    onToken?: (chunk: string) => void
    /**
     * Sharder path only. When present these are sent instead of `prompt`, and llama.cpp applies
     * the model's own chat template from the GGUF — the only formatting the model was trained on.
     * Pointer cannot use it: the wire carries a prompt string, and the far phone templates it.
     */
    messages?: ChatMessage[]
}

export async function runMeshTurn(
    mode: MeshMode,
    prompt: string,
    { images = [], nPredict = 256, onToken, messages }: MeshRunOptions = {}
): Promise<string> {
    if (mode === 'pointer') {
        const coord = meshSession.coord
        if (!coord) throw new Error('not hosting a mesh')
        if (coord.agents().length === 0) throw new Error('no phone has joined the mesh')
        // No stream: an agent returns its answer whole. Rather than fake a stream by chopping the
        // finished text, the caller simply gets one chunk — a progress bar made of an answer that
        // already arrived would be a lie about where the waiting happened.
        // Trimmed here rather than trusted: the answer was produced by another phone's runner,
        // which may not have honoured the stop sequences at all.
        const answer = clean(await coord.infer(prompt, meshSession.answerMode))
        onToken?.(answer)
        return answer
    }

    const store = Llama.useLlamaModelStore.getState()
    if (!store.context) throw new Error('no model is loaded on this phone')
    let out = ''
    await store.completion(
        {
            // Messages win when we have them; `prompt` stays as the fallback for a build whose
            // model has no chat template in its metadata, where llama.cpp cannot format anything.
            ...(messages?.length ? { messages } : { prompt }),
            n_predict: nPredict,
            // llama.cpp's own switch, and better than asking in the prompt: it drives the
            // template's thinking section rather than hoping the model reads an instruction.
            enable_thinking: helixReasoning(),
            // The rule the preamble only asks for. Without it the model carries on writing the
            // user's next turn and its own answer to it, which is what a bare transcript invites.
            stop: MESH_STOP_SEQUENCES,
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
    // Streamed tokens can already carry the start of a leaked turn before the stop sequence fires,
    // so the final text is cleaned too — the transcript keeps this, not the stream.
    return clean(out)
}

// The two things an answer needs before it is worth showing: no continuation of the conversation,
// and no thinking-out-loud when that was switched off. The /no_think hint is only a request, and a
// model that ignored it should not leave its reasoning in the transcript regardless.
function clean(text: string): string {
    const trimmed = trimAtStopSequence(text)
    return helixReasoning() ? trimmed : stripReasoning(trimmed)
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
