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

// The instruction the mesh chat opens with, for models that have a chat template to put it in.
//
// A test chat with no system turn at all is what produced answers that restated the question and
// then carried on inventing both sides of the conversation: nothing had ever told the model what it
// was doing or when to stop. Short on purpose — this window exists to show whether a split model
// still holds a conversation, and a long persona would be the thing under test instead.
export const MESH_SYSTEM_PROMPT =
    'You are a helpful assistant answering over a mesh of phones. Answer the last user message ' +
    'directly and concisely, then stop. Do not write the user\'s next message.'

export interface MeshMessage {
    role: 'system' | 'user' | 'assistant'
    content: string
}

/** A branch as chat-template messages — the form a model with a template expects. */
export function messagesFromTurns(turns: MeshTurn[], system = MESH_SYSTEM_PROMPT): MeshMessage[] {
    const messages: MeshMessage[] = system ? [{ role: 'system', content: system }] : []
    for (const t of turns) messages.push({ role: t.role, content: t.text })
    return messages
}

/**
 * Stop sequences for a model with NO chat template, where the prompt is bare `User:`/`Assistant:`
 * lines.
 *
 * Without them the model does exactly what the prompt suggests: it answers, writes `User:` and keeps
 * going, playing both parts until the token budget runs out. Every mesh answer looked like a model
 * stuck in a loop, and the loop was in the prompt format.
 */
export const MESH_PLAIN_STOPS = ['\nUser:', 'User:', '\nAssistant:', '\nYou:']

const ROLE_ECHO = /\n?\s*(?:User|Assistant|You|Human)\s*:/

/**
 * Tidy one mesh answer for display.
 *
 * Two things leak through even with stops in place: a reasoning block, when a thinking model decides
 * to think out loud in the visible channel, and the beginning of a role turn the model started
 * writing before a stop sequence could match. Both are noise about the harness rather than anything
 * the mesh did, and both were on screen.
 */
export function cleanMeshAnswer(text: string, formatting: 'template' | 'plain' = 'template'): string {
    let out = text.replace(/<think>[\s\S]*?<\/think>/g, '')
    // An unclosed block means generation stopped mid-thought: there is no answer in it to keep.
    const openThink = out.indexOf('<think>')
    if (openThink !== -1) out = out.slice(0, openThink)
    if (formatting === 'plain') {
        const echo = out.search(ROLE_ECHO)
        if (echo !== -1) out = out.slice(0, echo)
    }
    return out.trim()
}
