// Conversations for the mesh's own chats, with branches.
//
// The mesh test panels were single-shot: one prompt, one answer, nothing kept. That answers "did
// anything come back" and nothing else — and the question that actually matters about a mesh is
// whether CONTEXT survives it. A phone that answers one prompt correctly and has forgotten it by
// the next is not running a conversation, it is running a demo.
//
// So: real turns, kept, sent back as history. And branches, for the same reason ChatterUI has
// them — the only honest way to test whether context is doing anything is to take one conversation
// two ways from the same point and see the answers diverge. A branch is a full copy of the turns
// up to the fork, not a pointer into a shared list: they are meant to be edited apart, and sharing
// structure between them is how one branch silently rewrites another.
//
// Per mode, because Pointer and Sharder are different machines answering. Comparing them means
// asking each the same thing, which is impossible if they share one thread.

import { create } from 'zustand'
import { persist } from 'zustand/middleware'

import { MeshMode } from './helixSession'
import { MeshTurn } from './helixPrompt'
import { createMMKVStorage } from './storage/MMKV'


export interface MeshBranch {
    id: string
    name: string
    turns: MeshTurn[]
    createdAt: number
}

/** One independent set of branches per mode — the answers come from different machines. */
export type ModeChats = Record<string, { branches: MeshBranch[]; activeId: string }>

interface HelixChatState {
    chats: ModeChats
    branches: (mode: MeshMode) => MeshBranch[]
    active: (mode: MeshMode) => MeshBranch
    setActive: (mode: MeshMode, id: string) => void
    addTurn: (mode: MeshMode, turn: MeshTurn) => void
    /** Replaces the last assistant turn's text, for streaming into the transcript. */
    updateLastAssistant: (mode: MeshMode, text: string) => void
    newBranch: (mode: MeshMode, name?: string) => string
    /** Copies the active branch's turns up to and including `upto`, then switches to the copy. */
    forkBranch: (mode: MeshMode, upto: number, name?: string) => string
    renameBranch: (mode: MeshMode, id: string, name: string) => void
    deleteBranch: (mode: MeshMode, id: string) => void
    clearBranch: (mode: MeshMode) => void
}

const newId = () => 'b' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6)

const emptyBranch = (name = 'Main'): MeshBranch => ({
    id: newId(),
    name,
    turns: [],
    createdAt: Date.now(),
})

// The first branch of a mode has a derived id, not a random one, so that reading a mode nobody has
// opened yet gives the same answer every time. A random id here would be regenerated on every
// render, and "which branch is active" would change underfoot while the screen was drawing it.
const firstBranch = (mode: MeshMode): MeshBranch => ({
    id: `${mode}-main`,
    name: 'Main',
    turns: [],
    createdAt: 0,
})

function ensure(chats: ModeChats, mode: MeshMode) {
    const existing = chats[mode]
    if (existing && existing.branches.length) return existing
    const b = firstBranch(mode)
    return { branches: [b], activeId: b.id }
}

// Pure reads over a snapshot, for components. They take the state rather than reaching into the
// store so that a screen can subscribe to `chats` — one stable reference — and derive from it,
// instead of selecting a freshly built array that zustand would see change on every comparison.
export function branchesOf(chats: ModeChats, mode: MeshMode): MeshBranch[] {
    return ensure(chats, mode).branches
}

export function activeOf(chats: ModeChats, mode: MeshMode): MeshBranch {
    const c = ensure(chats, mode)
    return c.branches.find((b) => b.id === c.activeId) ?? c.branches[0]
}

export const useHelixChat = create<HelixChatState>()(
    persist(
        (set, get) => ({
            chats: {},

            branches: (mode) => ensure(get().chats, mode).branches,

            active: (mode) => {
                const c = ensure(get().chats, mode)
                return c.branches.find((b) => b.id === c.activeId) ?? c.branches[0]
            },

            setActive: (mode, id) =>
                set((s) => {
                    const c = ensure(s.chats, mode)
                    if (!c.branches.some((b) => b.id === id)) return s
                    return { chats: { ...s.chats, [mode]: { ...c, activeId: id } } }
                }),

            addTurn: (mode, turn) =>
                set((s) => {
                    const c = ensure(s.chats, mode)
                    return {
                        chats: {
                            ...s.chats,
                            [mode]: {
                                ...c,
                                branches: c.branches.map((b) =>
                                    b.id === c.activeId ? { ...b, turns: [...b.turns, turn] } : b
                                ),
                            },
                        },
                    }
                }),

            updateLastAssistant: (mode, text) =>
                set((s) => {
                    const c = ensure(s.chats, mode)
                    return {
                        chats: {
                            ...s.chats,
                            [mode]: {
                                ...c,
                                branches: c.branches.map((b) => {
                                    if (b.id !== c.activeId) return b
                                    const turns = [...b.turns]
                                    const last = turns[turns.length - 1]
                                    // Only ever rewrites an assistant turn it is already streaming
                                    // into; a stray call cannot overwrite what someone typed.
                                    if (!last || last.role !== 'assistant') return b
                                    turns[turns.length - 1] = { ...last, text }
                                    return { ...b, turns }
                                }),
                            },
                        },
                    }
                }),

            newBranch: (mode, name) => {
                const b = emptyBranch(name ?? `Branch ${get().branches(mode).length + 1}`)
                set((s) => {
                    const c = ensure(s.chats, mode)
                    return {
                        chats: {
                            ...s.chats,
                            [mode]: { branches: [...c.branches, b], activeId: b.id },
                        },
                    }
                })
                return b.id
            },

            forkBranch: (mode, upto, name) => {
                const from = get().active(mode)
                const b: MeshBranch = {
                    id: newId(),
                    name: name ?? `${from.name} ✳`,
                    turns: from.turns.slice(0, upto + 1).map((t) => ({ ...t })),
                    createdAt: Date.now(),
                }
                set((s) => {
                    const c = ensure(s.chats, mode)
                    return {
                        chats: {
                            ...s.chats,
                            [mode]: { branches: [...c.branches, b], activeId: b.id },
                        },
                    }
                })
                return b.id
            },

            renameBranch: (mode, id, name) =>
                set((s) => {
                    const c = ensure(s.chats, mode)
                    return {
                        chats: {
                            ...s.chats,
                            [mode]: {
                                ...c,
                                branches: c.branches.map((b) => (b.id === id ? { ...b, name } : b)),
                            },
                        },
                    }
                }),

            deleteBranch: (mode, id) =>
                set((s) => {
                    const c = ensure(s.chats, mode)
                    const left = c.branches.filter((b) => b.id !== id)
                    // Never leave the mode with nothing to talk into — deleting the last branch
                    // means starting a fresh one, not landing on a screen with no conversation.
                    const branches = left.length ? left : [emptyBranch()]
                    const activeId = branches.some((b) => b.id === c.activeId)
                        ? c.activeId
                        : branches[0].id
                    return { chats: { ...s.chats, [mode]: { branches, activeId } } }
                }),

            clearBranch: (mode) =>
                set((s) => {
                    const c = ensure(s.chats, mode)
                    return {
                        chats: {
                            ...s.chats,
                            [mode]: {
                                ...c,
                                branches: c.branches.map((b) =>
                                    b.id === c.activeId ? { ...b, turns: [] } : b
                                ),
                            },
                        },
                    }
                }),
        }),
        { name: 'helix-mesh-chats', storage: createMMKVStorage(), version: 1 }
    )
)

// Re-exported so a screen needs one import for a conversation and its turns.
export type { MeshTurn } from './helixPrompt'
export { buildBranchMessages, buildBranchPrompt, estimateTokens } from './helixPrompt'
