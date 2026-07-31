// One log for the whole mesh, on the host.
//
// Debugging two phones has meant reading two logs, on two screens, and lining up timestamps that
// come from two clocks. Most of the questions worth asking are about the gap between them — the
// host planned a shard, did the worker ever hear about it? the worker started its rpc-server, did
// the host see the announce? — and neither log alone can answer that.
//
// So agents send their notable lines to the host over the mesh, and the host interleaves them with
// its own. It is diagnostic only: nothing depends on it, and a phone that never sends anything
// still works exactly as before.
//
// Deliberately NOT the whole log. Every line an agent prints, streamed continuously, would put a
// message on the wire several times a second forever — the mesh's own traffic would become the
// thing most likely to break it. Warnings and errors only, rate-limited, and dropped rather than
// queued when the link is busy: a diagnostic that degrades what it is diagnosing is worse than
// none.

export type NetLogLevel = 'info' | 'warn' | 'error'

export interface NetLogEntry {
    /** Which phone it came from; 'host' for our own. */
    from: string
    level: NetLogLevel
    text: string
    /** The SENDER's clock. Two phones do not agree, and pretending they do would mislead. */
    at: number
    /** When the host received it — the only ordering it can actually trust. */
    received: number
}

/** Kept small on purpose: this lives in memory on a phone, and old lines stop being useful fast. */
const MAX_ENTRIES = 300
// An agent that starts failing in a loop would otherwise flood the mesh with reports of it.
const MIN_INTERVAL_MS = 250

const entries: NetLogEntry[] = []
let lastSentAt = 0
const listeners = new Set<() => void>()

function notify() {
    for (const l of listeners) l()
}

/** Subscribe to changes (the mesh screen re-renders from this). Returns an unsubscribe. */
export function onNetLogChange(fn: () => void): () => void {
    listeners.add(fn)
    return () => listeners.delete(fn)
}

export function netLog(): NetLogEntry[] {
    return entries
}

export function clearNetLog() {
    entries.length = 0
    notify()
}

/** Record a line — from an agent over the wire, or from this phone. */
export function addNetLog(entry: Omit<NetLogEntry, 'received'>) {
    entries.push({ ...entry, received: Date.now() })
    if (entries.length > MAX_ENTRIES) entries.splice(0, entries.length - MAX_ENTRIES)
    notify()
}

/**
 * Whether an agent should send this line at all.
 *
 * Info is dropped: it is the bulk of the volume and almost none of the value, and the host already
 * logs the mesh events it cares about from its own side.
 */
export function shouldReport(level: NetLogLevel): boolean {
    if (level === 'info') return false
    const now = Date.now()
    if (now - lastSentAt < MIN_INTERVAL_MS) return false
    lastSentAt = now
    return true
}

/** Longest line an agent may send, so one huge message cannot occupy the link. */
export const MAX_NET_LOG_CHARS = 400

export function formatNetLog(list: NetLogEntry[] = entries): string {
    if (!list.length) return 'Nothing reported yet.'
    return list
        .map((e) => {
            const t = new Date(e.received).toLocaleTimeString()
            return `[${t}] ${e.from}: ${e.level === 'error' ? '! ' : ''}${e.text}`
        })
        .join('\n')
}
