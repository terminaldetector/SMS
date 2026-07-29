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

export interface BuiltPrompt {
    prompt: string
    /** Turns left out of the front because the budget ran out. */
    dropped: number
    estimatedTokens: number
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
    reserveForAnswer = 512
): BuiltPrompt {
    const budget = Math.max(256, budgetTokens - reserveForAnswer)
    const rendered = turns.map((t) =>
        t.role === 'user' ? `User: ${t.text}` : `Assistant: ${t.text}`
    )

    // Walk backwards so the most recent turns are the ones that survive.
    let start = 0
    let used = estimateTokens('Assistant:')
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

    const prompt = kept.join('\n') + '\nAssistant:'
    return { prompt, dropped: turns.length - kept.length, estimatedTokens: estimateTokens(prompt) }
}
