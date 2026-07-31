// Turning a branch of turns into one prompt, and nothing else.
//
// Split out from helixChat because that file reaches MMKV and zustand, and this is arithmetic over
// strings: the moment it depends on React Native it stops being runnable outside a phone, and the
// trimming rule — the thing most likely to be quietly wrong — stops being testable. It is proved
// in CI against this exact source.

export interface MeshTurn {
    role: 'user' | 'assistant'
    text: string
    /** Local file URIs, only ever set when the model actually accepts images. */
    images?: string[]
    at: number
}

// Roughly four characters to a token across the tokenisers this app meets. Deliberately an
// estimate: the remote model in Pointer is another phone's, with a tokeniser this one has never
// loaded, so an exact local count would be exact about the wrong model. Trimming rounds against
// itself anyway — the cost of overestimating is one turn fewer of history, and of underestimating
// is an overflowing context.
export const CHARS_PER_TOKEN = 4

export function estimateTokens(text: string): number {
    return Math.ceil(text.length / CHARS_PER_TOKEN)
}

// The lead-in. Without one, "User: …\nAssistant:" is not a question put to a model — it is the
// opening of a transcript, and a base model does the only sensible thing with a transcript: it
// writes more of it. That is exactly what happened on device, with answers that ran straight on
// into "User: <the same question>\nAssistant: <the same answer>" for as long as n_predict allowed.
//
// Two things stop it, and both are needed. This says what the text is; the stop sequences below
// enforce it, because instructions are a request and stop sequences are a rule.
export const MESH_PREAMBLE =
    'The following is a conversation between a user and a helpful assistant. The assistant replies ' +
    "once, directly, and then stops — it never writes the user's next message."

// Where an answer ends. `\nUser:` is the real one; the others catch a model that starts a fresh
// turn without a newline, or announces itself before answering.
export const MESH_STOP_SEQUENCES = ['\nUser:', '\nAssistant:', 'User:', 'Assistant:']

// Qwen3-family models think out loud in <think>…</think> before answering, and the tag is part of
// the generated text — it arrives in the stream like any other token. Two levers, because either
// alone is unreliable: this hint asks the model not to think (Qwen reads it; other families ignore
// it harmlessly), and stripReasoning removes the block if it thought anyway.
export const REASONING_OFF_HINT = '/no_think'

const THINK_BLOCK = /<think>[\s\S]*?<\/think>/g
// An answer cut short by n_predict can leave <think> open forever. Dropping from the tag to the end
// is right: everything after it was reasoning that never reached a conclusion.
const THINK_UNCLOSED = /<think>[\s\S]*$/

/** Remove a model's visible reasoning, leaving only what it actually answered. */
export function stripReasoning(text: string): string {
    return text.replace(THINK_BLOCK, '').replace(THINK_UNCLOSED, '').trim()
}

/**
 * Cut an answer at the first place it starts writing somebody else's turn.
 *
 * Belt and braces over the stop sequences, and not redundant: Pointer's answer comes back whole
 * from another phone, whose runner may or may not have honoured them, and an answer that visibly
 * continues the conversation by itself is the single most confusing thing a mesh chat can show.
 */
export function trimAtStopSequence(text: string): string {
    let cut = text.length
    for (const stop of MESH_STOP_SEQUENCES) {
        const at = text.indexOf(stop)
        if (at >= 0 && at < cut) cut = at
    }
    return text.slice(0, cut).trim()
}

export interface BuiltPrompt {
    prompt: string
    /** Turns left out of the front because the budget ran out. */
    dropped: number
    estimatedTokens: number
}

export interface ChatMessage {
    role: 'system' | 'user' | 'assistant'
    content: string
}

export interface BuiltMessages {
    messages: ChatMessage[]
    dropped: number
    estimatedTokens: number
}

/**
 * The same trimmed history, as MESSAGES rather than one flat transcript.
 *
 * This is what an instruct-tuned model actually expects. Handed "User: …\nAssistant:" as plain
 * text, Qwen3.5 did not answer the question — it commented on the input, in English, mid-answer:
 * "I am confused by the structure of the input provided in the `user` message block". It was being
 * shown a transcript in a slot where its template puts a single message, so it treated the whole
 * conversation as one confusing user turn, repeated an earlier answer, and reasoned about the
 * formatting instead of the question.
 *
 * Messages go to llama.cpp, which applies the model's OWN chat template out of the GGUF metadata.
 * That is the only formatting guaranteed to match what the model was trained on, and it is already
 * in the file — inventing our own was the mistake.
 *
 * The flat form above is still used for Pointer: the wire carries a prompt string, and the
 * answering phone applies its own model's template to it at the far end.
 */
export function buildBranchMessages(
    turns: MeshTurn[],
    budgetTokens: number,
    reserveForAnswer = 512
): BuiltMessages {
    const budget = Math.max(256, budgetTokens - reserveForAnswer)

    // Same walk as buildBranchPrompt: newest first, oldest dropped when the budget runs out.
    let start = 0
    let used = estimateTokens(MESH_PREAMBLE)
    for (let i = turns.length - 1; i >= 0; i--) {
        const cost = estimateTokens(turns[i].text) + 8 // ~role/template overhead per message
        if (used + cost > budget) {
            start = i + 1
            break
        }
        used += cost
    }
    let kept = turns.slice(start)
    if (!kept.length && turns.length) kept = turns.slice(-1)

    // A conversation must not open on an assistant turn — a template that alternates strictly
    // would either reject it or silently pair it with an empty user message.
    while (kept.length && kept[0].role === 'assistant') kept = kept.slice(1)

    const messages: ChatMessage[] = [
        { role: 'system', content: MESH_PREAMBLE },
        ...kept.map((t) => ({ role: t.role, content: t.text }) as ChatMessage),
    ]
    return {
        messages,
        dropped: turns.length - kept.length,
        estimatedTokens: used,
    }
}

/**
 * Assemble a branch into one prompt, oldest turns dropped first when the budget runs out.
 *
 * Dropping from the front rather than summarising is the honest choice for a test chat: a summary
 * would quietly change what the model was asked, and the whole point here is to see what the mesh
 * does with the context it was actually given. How much was dropped is returned so the screen can
 * say so — context silently falling off the back is exactly the failure this is meant to expose.
 */
export function buildBranchPrompt(
    turns: MeshTurn[],
    budgetTokens: number,
    reserveForAnswer = 512,
    opts: { reasoning?: boolean } = {}
): BuiltPrompt {
    // Reasoning defaults to on — it is the model's own behaviour, and silently suppressing it
    // would hide what a model is actually doing from someone testing a mesh.
    const lead = opts.reasoning === false ? `${MESH_PREAMBLE} ${REASONING_OFF_HINT}` : MESH_PREAMBLE
    const budget = Math.max(256, budgetTokens - reserveForAnswer)
    const rendered = turns.map((t) =>
        t.role === 'user' ? `User: ${t.text}` : `Assistant: ${t.text}`
    )

    // Walk backwards so the most recent turns are the ones that survive. The preamble is counted
    // as part of what has to fit: it is sent every time, and leaving it out of the budget is how a
    // prompt comes out slightly over the limit no matter how carefully the turns were measured.
    let start = 0
    let used = estimateTokens(lead) + estimateTokens('Assistant:')
    for (let i = rendered.length - 1; i >= 0; i--) {
        const cost = estimateTokens(rendered[i]) + 1
        if (used + cost > budget) {
            start = i + 1
            break
        }
        used += cost
    }
    const kept = rendered.slice(start)
    // A single turn larger than the whole budget would otherwise send nothing at all; sending the
    // tail of it is worse than useless, so it goes whole and the model's own context shift deals
    // with it. Saying it was dropped when it was not would be a lie to the screen above.
    if (!kept.length && rendered.length) kept.push(rendered[rendered.length - 1])

    const prompt = `${lead}\n\n${kept.join('\n')}\nAssistant:`
    return { prompt, dropped: turns.length - kept.length, estimatedTokens: estimateTokens(prompt) }
}
