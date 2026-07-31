// Exact context trimming for Sharder, using the model's own tokenizer.
//
// helixPrompt.ts's estimate (≈4 chars/token) is deliberately approximate, and says so: Pointer's
// answer comes from another phone's model, whose tokenizer this phone has never loaded, so an
// exact count would be exact about the wrong model. Sharder has no such excuse — the split model
// IS this phone's own loaded context, tokenizer included, one call away via tokenLength(). Using
// the guess there was never necessary, and the guess is wrong in a specific, common way: Cyrillic
// (and CJK) text tokenizes far denser than 4 chars/token, so the estimate keeps more history than
// the model can actually hold, and llama.cpp then throws "Context is full" — a failure the
// trimming exists specifically to prevent, undone by measuring the wrong thing.
//
// Not proven in CI like helixPrompt.ts, because it needs a loaded llama.cpp context to mean
// anything — there is no real tokenizer to call outside the app.

import { Llama } from './engine/Local/LlamaLocal'
import { buildBranchPrompt, MeshTurn } from './helixPrompt'

export interface ExactBuiltPrompt {
    prompt: string
    dropped: number
    tokens: number
}

const renderTurn = (t: MeshTurn) => (t.role === 'user' ? `User: ${t.text}` : `Assistant: ${t.text}`)

/**
 * Same drop-the-oldest-first contract as buildBranchPrompt, but measured against the real
 * tokenizer instead of guessed from character count.
 */
export async function buildBranchPromptExact(
    turns: MeshTurn[],
    budgetTokens: number,
    reserveForAnswer = 512,
    opts: { reasoning?: boolean } = {}
): Promise<ExactBuiltPrompt> {
    const store = Llama.useLlamaModelStore.getState()
    const budget = Math.max(256, budgetTokens - reserveForAnswer)
    const lead = buildBranchPrompt([], budgetTokens, reserveForAnswer, opts)
        .prompt.replace(/\nAssistant:$/, '')
        .trim()
    const rendered = turns.map(renderTurn)

    // The character estimate's own cut is the starting guess, not a second opinion to reconcile —
    // it is right whenever the text is ASCII-heavy, so most sends cost exactly one real tokenize
    // call to confirm. It is wrong in only one direction (keeps too much), so widening the drop is
    // the only correction this loop ever needs to make.
    let start = buildBranchPrompt(turns, budgetTokens, reserveForAnswer, opts).dropped

    while (start < rendered.length) {
        const candidate = `${lead}\n\n${rendered.slice(start).join('\n')}\nAssistant:`
        const tokens = await store.tokenLength(candidate)
        if (tokens <= budget) return { prompt: candidate, dropped: start, tokens }
        start++
    }

    // Nothing fit, not even the newest turn alone. Sending it whole and letting the model's own
    // context handling deal with it is still more honest than silently truncating the tail — a
    // half-sent turn reads as the model ignoring part of what it was asked, which it never saw.
    const last = rendered.length ? rendered[rendered.length - 1] : ''
    const prompt = `${lead}\n\n${last}\nAssistant:`.trim()
    return {
        prompt,
        dropped: Math.max(0, rendered.length - 1),
        tokens: await store.tokenLength(prompt),
    }
}
