// Can this phone actually open a TCP connection to that address?
//
// Asked before a sharded load, because of what the alternative looks like. llama.cpp registers an
// rpc-server by CONNECTING to it (lm_ggml_backend_rpc_add_server), and when that fails it logs
// "Failed to register RPC server" to logcat — where the app never sees it — and carries on. The
// load then succeeds, the context reports itself sharded, and every layer runs locally. On device
// that came out as a 75-93 second load followed by a model that worked, which is the most
// expensive possible way to be told the network is unreachable.
//
// A plain connect takes milliseconds and answers the same question llama.cpp is about to ask. It
// is not a guarantee — the rpc-server could accept TCP and still refuse the protocol — but a
// refused connection is conclusive, and that is the case actually being hit.

interface TcpSocket {
    on(event: 'connect' | 'error' | 'close' | 'timeout', cb: (e?: unknown) => void): void
    destroy(): void
    setTimeout?(ms: number): void
}

interface TcpModule {
    createConnection(opts: { host: string; port: number }, cb?: () => void): TcpSocket
}

function loadTcp(): TcpModule | null {
    try {
        const mod = require('react-native-tcp-socket')
        return (mod.default ?? mod) as TcpModule
    } catch {
        return null
    }
}

export interface ReachResult {
    addr: string
    ok: boolean
    ms: number
    /** Why not, when not. */
    reason: string
}

/** Try to connect to "host:port", giving up after `timeoutMs`. */
export function canReach(addr: string, timeoutMs = 3000): Promise<ReachResult> {
    const started = Date.now()
    const at = addr.lastIndexOf(':')
    const host = at > 0 ? addr.slice(0, at) : addr
    const port = at > 0 ? Number(addr.slice(at + 1)) : NaN

    if (!host || !Number.isInteger(port))
        return Promise.resolve({ addr, ok: false, ms: 0, reason: 'not a host:port address' })

    const tcp = loadTcp()
    if (!tcp)
        // Unknown, not unreachable. Saying "cannot reach" because we could not look would send
        // someone hunting a network fault that does not exist.
        return Promise.resolve({ addr, ok: true, ms: 0, reason: 'no TCP module — not checked' })

    return new Promise<ReachResult>((resolve) => {
        let settled = false
        const done = (ok: boolean, reason: string) => {
            if (settled) return
            settled = true
            try {
                socket.destroy()
            } catch {
                /* already gone */
            }
            resolve({ addr, ok, ms: Date.now() - started, reason })
        }

        // The commonest cause on Android, and the least obvious: with no process network binding
        // an outgoing connection goes out over the DEFAULT network, which is mobile data whenever
        // it is on. The hotspot's subnet is only reachable over the AP interface, so the packet
        // leaves by the wrong door and nothing answers. Worth naming, because "cannot reach" reads
        // as a broken hotspot and the hotspot is fine.
        const timer = setTimeout(
            () =>
                done(
                    false,
                    `no answer within ${timeoutMs}ms — nothing is listening there, the phones are ` +
                        'on different networks, or this phone is routing over mobile data (try ' +
                        'turning mobile data off on BOTH phones and retrying)'
                ),
            timeoutMs
        )

        const socket = tcp.createConnection({ host, port }, () => {
            clearTimeout(timer)
            done(true, 'connected')
        })
        socket.on('error', (e) => {
            clearTimeout(timer)
            done(false, `refused: ${e instanceof Error ? e.message : String(e)}`)
        })
    })
}

/** Check several addresses at once — one slow worker should not delay the others' answers. */
export async function reachAll(addrs: string[], timeoutMs = 3000): Promise<ReachResult[]> {
    return Promise.all(addrs.map((a) => canReach(a, timeoutMs)))
}

export function formatReach(results: ReachResult[]): string {
    if (!results.length) return ''
    return [
        '------ SHARD REACHABILITY -----',
        ...results.map((r) => `${r.ok ? '  ✓' : '  ✗'} ${r.addr} — ${r.reason} (${r.ms}ms)`),
        '------ END SHARD REACHABILITY -----',
    ].join('\n')
}
