// What Sharder actually sends the split model: the model's own chat format, trimmed by its own
// tokenizer.
//
// Two separate faults lived here, and they looked like one.
//
// The trimming was measured with helixPrompt.ts's ≈4 chars/token estimate. That estimate is right
// to be an estimate for Pointer — the answer comes from another phone's model, whose tokenizer this
// phone has never loaded — but Sharder has no such excuse: the split model IS this phone's context.
// Cyrillic and CJK tokenize far denser than 4 chars/token, so the estimate kept more history than
// the model could hold and llama.cpp answered "Context is full".
//
// The bigger one was the prompt format. History went out as bare `User: …` / `Assistant: …` lines
// ending in `Assistant:`, with no stop sequences — a completion prompt handed to instruction-tuned
// models that have their own chat template and their own end-of-turn token. So the model answered
// and then kept writing: `User:` again, another answer, around and around until the token budget ran
// out, and thinking models spilled their `<think>` block into the reply on the way. None of that was
// the mesh; all of it was on screen as if it were, and every answer cost a full 256 tokens of
// generation to produce.
//
// So: use the model's template when it has one (which is also what the ordinary app chat does, via
// getFormattedChat), fall back to the plain form with stop sequences when it does not, and count
// against the real tokenizer either way.

import { Llama } from './engine/Local/LlamaLocal'
import {
    buildBranchPrompt,
    cleanMeshAnswer,
    MESH_PLAIN_STOPS,
    messagesFromTurns,
    MeshMessage,
    MeshTurn,
} from './helixPrompt'

export interface MeshSend {
    /** Messages for a model with a chat template — native applies it at completion time. */
    messages?: MeshMessage[]
    /** The exact text the model will see. Sent as-is when there is no template. */
    prompt: string
    stop: string[]
    /** Turns left out of the front because the budget ran out. */
    dropped: number
    tokens: number
    formatting: 'template' | 'plain'
}

/** Stops worth keeping even with a template: a model that ignores its own end-of-turn token. */
const TEMPLATE_STOPS = ['\nUser:', '\nAssistant:']

const renderPlain = (turns: MeshTurn[]) =>
    turns.map((t) => (t.role === 'user' ? `User: ${t.text}` : `Assistant: ${t.text}`)).join('\n') +
    '\nAssistant:'

/**
 * Render a branch the way the loaded model expects, dropping the oldest turns first until it fits.
 *
 * The character estimate's own cut is the starting guess, not a second opinion to reconcile — it is
 * right whenever the text is ASCII-heavy, and it is wrong in only one direction (keeps too much), so
 * widening the drop is the only correction this loop ever makes. Most sends therefore cost one real
 * formatting + tokenize pass to confirm.
 */
export async function buildShardSend(
    turns: MeshTurn[],
    budgetTokens: number,
    reserveForAnswer = 512
): Promise<MeshSend> {
    const store = Llama.useLlamaModelStore.getState()
    const context = store.context
    const budget = Math.max(256, budgetTokens - reserveForAnswer)
    const useTemplate = !!context && hasChatTemplate(context)

    const render = async (kept: MeshTurn[]): Promise<{ prompt: string; messages?: MeshMessage[] }> => {
        if (!useTemplate) return { prompt: renderPlain(kept) }
        const messages = messagesFromTurns(kept)
        const prompt = await formatWithTemplate(context, messages)
        // A template that cannot be applied (a metadata template this build's jinja refuses) is not
        // worth failing the send over — the plain form still works, it just needs its stops.
        return prompt ? { prompt, messages } : { prompt: renderPlain(kept) }
    }

    let start = buildBranchPrompt(turns, budgetTokens, reserveForAnswer).dropped
    while (start < turns.length) {
        const kept = turns.slice(start)
        const { prompt, messages } = await render(kept)
        const tokens = await store.tokenLength(prompt)
        if (tokens <= budget) return finish(prompt, messages, start, tokens)
        start++
    }

    // Nothing fit, not even the newest turn alone. Sending it whole and letting the model's own
    // context handling deal with it is still more honest than silently truncating the tail — a
    // half-sent turn reads as the model ignoring part of what it was asked, which it never saw.
    const last = turns.length ? [turns[turns.length - 1]] : []
    const { prompt, messages } = await render(last)
    return finish(prompt, messages, Math.max(0, turns.length - 1), await store.tokenLength(prompt))
}

function finish(
    prompt: string,
    messages: MeshMessage[] | undefined,
    dropped: number,
    tokens: number
): MeshSend {
    return {
        prompt,
        messages,
        stop: messages ? TEMPLATE_STOPS : MESH_PLAIN_STOPS,
        dropped,
        tokens,
        formatting: messages ? 'template' : 'plain',
    }
}

// `any` rather than LlamaContext: this reaches for fields the app's own local path also reaches for
// (chatTemplates / isJinjaSupported), and the mesh chat should not fail to compile against a native
// module version that shapes them slightly differently.
function hasChatTemplate(context: any): boolean {
    try {
        if (typeof context.isJinjaSupported === 'function' && context.isJinjaSupported()) return true
        return !!context?.model?.metadata?.['tokenizer.chat_template']
    } catch {
        return false
    }
}

async function formatWithTemplate(context: any, messages: MeshMessage[]): Promise<string> {
    try {
        const result = await context.getFormattedChat(messages, null, {
            jinja: true,
            // A test chat wants the answer, not the reasoning: with thinking on, a 256-token budget
            // is spent thinking and the reply arrives truncated or as a bare <think> block. The
            // template is the right place to say so — asking for it in the prompt does not stop it.
            enable_thinking: false,
            add_generation_prompt: true,
        })
        if (typeof result === 'string') return result
        return result?.prompt ?? ''
    } catch {
        return ''
    }
}

export { cleanMeshAnswer }
