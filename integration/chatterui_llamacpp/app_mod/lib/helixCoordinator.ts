// In-app HELIX coordinator for ChatterUI — the SERVER role a HOST phone runs so another ChatterUI
// phone (agent) joins directly, NO PC. TS port of integration/chatterui_llamacpp/js/coordinator_node.mjs,
// proven cross-language by js/p2p_ws_smoke.mjs. The host accepts a WebSocket agent (helixWsServer.ts),
// learns it from AGENT_ANNOUNCE, and routes a prompt to it (single / voting).
//
// The ONLY native module in the whole mesh lives here: react-native-tcp-socket's server socket, and it
// is loaded LAZILY (only when the host taps Start) so it can never affect app startup. The agent phone
// stays native-free (built-in WebSocket). Crypto is pure JS @noble; the frame nonce is injected from
// expo-crypto (New-Architecture-safe) exactly like helixAgent.ts.

import { FrameCodec, Msg, RandomBytes } from './helixFrame'
import { sealerKey } from './helixCrypto'
import { StreamSock, WsServerConnection } from './helixWsServer'

const td = new TextDecoder()

// Minimal shape of react-native-tcp-socket we depend on (server + per-connection socket).
interface TcpServer {
    listen(opts: { port: number; host: string }, cb?: () => void): void
    close(): void
    on(event: 'error', cb: (e: unknown) => void): void
}
interface TcpModule {
    createServer(onConnection: (socket: StreamSock) => void): TcpServer
}

// Lazily require react-native-tcp-socket so its native code is touched only when hosting starts.
function loadTcp(): TcpModule {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const mod = require('react-native-tcp-socket')
    return (mod.default ?? mod) as TcpModule
}

type Collected = { results: Record<string, string>; votes: Record<string, [string, number]> }
type Agent = { conn: WsServerConnection; lastSeen: number }

// An agent announces every ~500ms (helixAgent.ts). If a phone drops off the Wi-Fi mid-session the
// TCP connection can sit half-open — no 'close' event ever arrives — so silence is what actually
// tells us it's gone. Generous enough to survive a brief stall, short enough to stay honest.
const AGENT_TIMEOUT_MS = 15000

export class HelixCoordinator {
    private codec: FrameCodec
    private server: TcpServer | null = null
    private conns = new Map<string, Agent>() // agentId -> connection + liveness
    private coll = new Map<string, Collected>() // tid -> collected results/votes

    constructor(
        private readonly nodeId: string,
        clusterSecret: string,
        opts: { randomBytes?: RandomBytes } = {}
    ) {
        this.codec = new FrameCodec(nodeId, sealerKey(clusterSecret), 0, true, opts.randomBytes)
    }

    listen(port = 8790, host = '0.0.0.0'): Promise<void> {
        return new Promise((resolve, reject) => {
            const tcp = loadTcp()
            const server = tcp.createServer((socket) => this.onSocket(socket))
            server.on('error', (e) => reject(e instanceof Error ? e : new Error(String(e))))
            server.listen({ port, host }, () => resolve())
            this.server = server
        })
    }

    private onSocket(socket: StreamSock) {
        const conn = new WsServerConnection(socket)
        const state = { remoteId: null as string | null }
        conn.onBinary((payload) => {
            if (state.remoteId === null) {
                state.remoteId = td.decode(payload) // handshake: first binary message is the agent's node id
                return
            }
            const msg = this.codec.open(payload)
            if (!msg) return
            const known = this.conns.get(msg.src)
            if (known) known.lastSeen = Date.now() // any traffic counts as liveness
            if (msg.type === Msg.AGENT_ANNOUNCE) {
                this.conns.set(msg.src, { conn, lastSeen: Date.now() })
            } else if (msg.type === Msg.RESULT) {
                const c = this.coll.get(msg.tid)
                if (c) c.results[msg.src] = String(msg.body.text ?? '')
            } else if (msg.type === Msg.VOTE) {
                const c = this.coll.get(msg.tid)
                if (c) c.votes[msg.src] = [String(msg.body.candidate ?? ''), Number(msg.body.score ?? 0)]
            }
        })
        conn.onClose(() => {
            for (const [id, a] of this.conns) if (a.conn === conn) this.conns.delete(id)
        })
    }

    agents(): string[] {
        const cutoff = Date.now() - AGENT_TIMEOUT_MS
        for (const [id, a] of this.conns) {
            if (a.lastSeen >= cutoff) continue
            this.conns.delete(id) // gone quiet — drop it so the UI and routing stay truthful
            try {
                a.conn.close()
            } catch {
                /* ignore */
            }
        }
        return [...this.conns.keys()]
    }

    async infer(prompt: string, mode: 'single' | 'voting' = 'single', timeoutMs = 60000): Promise<string> {
        const agent = this.agents()[0]
        if (!agent) throw new Error('no agent joined yet')
        const tid = 't' + Math.random().toString(36).slice(2, 10)
        this.coll.set(tid, { results: {}, votes: {} })
        this.conns.get(agent)!.conn.send(this.codec.seal(Msg.TASK, { mode, prompt }, tid))
        const deadline = Date.now() + timeoutMs
        try {
            while (Date.now() < deadline) {
                const c = this.coll.get(tid)!
                if (mode === 'voting') {
                    const v = Object.values(c.votes)
                    if (v.length) return v[0][0] // single-agent: its candidate is the decision
                } else if (Object.keys(c.results).length) {
                    return Object.values(c.results)[0]
                }
                await new Promise((r) => setTimeout(r, 20))
            }
            throw new Error('infer timeout (agent did not answer)')
        } finally {
            this.coll.delete(tid)
        }
    }

    close() {
        try {
            this.server?.close()
        } catch {
            /* ignore */
        }
        this.server = null
        for (const a of this.conns.values()) a.conn.close()
        this.conns.clear()
    }
}
