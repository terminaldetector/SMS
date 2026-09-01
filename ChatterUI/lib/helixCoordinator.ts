// In-app HELIX coordinator for ChatterUI — the SERVER role a HOST phone runs so another ChatterUI
// phone (agent) joins directly, NO PC. TS port of integration/chatterui_llamacpp/js/coordinator_node.mjs,
// proven cross-language by js/p2p_ws_smoke.mjs. The host accepts a WebSocket agent (helixWsServer.ts),
// learns it from AGENT_ANNOUNCE, and routes a prompt to it (single / voting).
//
// The ONLY native module in the whole mesh lives here: react-native-tcp-socket's server socket, and it
// is loaded LAZILY (only when the host taps Start) so it can never affect app startup. The agent phone
// stays native-free (built-in WebSocket). Crypto is pure JS @noble; the frame nonce is injected from
// expo-crypto (New-Architecture-safe) exactly like helixAgent.ts.

import { sealerKey } from './helixCrypto'
import { FrameCodec, Msg, RandomBytes } from './helixFrame'
import { addNetLog, MAX_NET_LOG_CHARS } from './helixNetLog'
import { handleModelRequest, ServedModel } from './helixModelServe'
import { HttpRequest, HttpResponder, StreamSock, WsServerConnection } from './helixWsServer'

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
    const mod = require('react-native-tcp-socket')
    return (mod.default ?? mod) as TcpModule
}

type Collected = { results: Record<string, string>; votes: Record<string, [string, number]> }
type Agent = {
    conn: WsServerConnection
    lastSeen: number
    mem: number // bytes the agent announced — what this phone CAN hold
    tps: number // measured throughput (helixBench score) — how FAST it holds it; 0 = never measured
    rpc: string // "host:port" of its llama.cpp rpc-server, '' if it isn't offering to hold layers
}

// What the host needs about each joined phone to plan a shard (see helixPlacement.ts).
export interface AgentInfo {
    id: string
    mem: number
    /** Measured compute score; 0 when the phone never ran the benchmark. */
    tps: number
    rpc: string
}

// An agent announces every ~500ms (helixAgent.ts). If a phone drops off the Wi-Fi mid-session the
// TCP connection can sit half-open — no 'close' event ever arrives — so silence is what actually
// tells us it's gone. Generous enough to survive a brief stall, short enough to stay honest.
const AGENT_TIMEOUT_MS = 15000
// How often the host is expected to poll agents(). Anything longer than this between two ticks is
// time this phone spent unable to read anything, not time an agent spent silent.
const LIVENESS_TICK_MS = 1000

/** Highest-scoring candidate; ties keep the earliest, so the result is stable rather than random. */
function bestVote(votes: [string, number][]): string {
    let best = votes[0]
    for (const v of votes) if (v[1] > best[1]) best = v
    return best[0]
}

export class HelixCoordinator {
    private codec: FrameCodec
    private server: TcpServer | null = null
    private conns = new Map<string, Agent>() // agentId -> connection + liveness
    private coll = new Map<string, Collected>() // tid -> collected results/votes
    // The GGUF this host hands to a joining phone that doesn't have it yet. Null = offer nothing,
    // which is what the "don't send my model" setting leaves it as.
    private served: ServedModel | null = null
    // Round-robin cursor for `single` — which agent answered last.
    private rr = -1
    // When agents() last ran, so a blocked JS thread can be told apart from a quiet agent.
    private lastTick = 0

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

    // Offer (or stop offering) this host's model to joining phones. Safe to call while hosting.
    offerModel(model: ServedModel | null) {
        this.served = model
    }

    modelOffered(): string {
        return this.served?.name ?? ''
    }

    private onHttp = (req: HttpRequest, res: HttpResponder) => {
        void handleModelRequest(req, res, this.served)
            .then((handled) => {
                if (!handled) res.send(404, { 'Content-Type': 'text/plain' }, 'not found')
            })
            .catch(() => {
                // A read failure mid-stream can't be turned into a status code any more — the
                // headers are long gone — so all that's left is to drop the connection and let the
                // downloader retry with a Range request.
                try {
                    res.end()
                } catch {
                    /* ignore */
                }
            })
    }

    private onSocket(socket: StreamSock) {
        const conn = new WsServerConnection(socket, { onHttp: this.onHttp })
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
                this.conns.set(msg.src, {
                    conn,
                    lastSeen: Date.now(),
                    mem: Number(msg.body.mem ?? 0),
                    // Already part of ANNOUNCE and already meaning throughput, so weighing layers
                    // by speed needs no protocol change and no older phone drops a frame over it.
                    tps: Number(msg.body.tps ?? 0),
                    rpc: String(msg.body.rpc ?? ''),
                })
            } else if (msg.type === Msg.STATUS && typeof msg.body.log === 'string') {
                // An agent reporting something worth seeing on the host's screen. Bounded here as
                // well as at the sender: the sender's limit is a courtesy, this one is the rule.
                const level = msg.body.level === 'error' ? 'error' : 'warn'
                addNetLog({
                    from: msg.src,
                    level,
                    text: String(msg.body.log).slice(0, MAX_NET_LOG_CHARS),
                    at: Number(msg.body.at) || Date.now(),
                })
            } else if (msg.type === Msg.RESULT) {
                const c = this.coll.get(msg.tid)
                if (c) c.results[msg.src] = String(msg.body.text ?? '')
            } else if (msg.type === Msg.VOTE) {
                const c = this.coll.get(msg.tid)
                if (c)
                    c.votes[msg.src] = [
                        String(msg.body.candidate ?? ''),
                        Number(msg.body.score ?? 0),
                    ]
            }
        })
        conn.onClose(() => {
            for (const [id, a] of this.conns) if (a.conn === conn) this.conns.delete(id)
        })
    }

    agents(): string[] {
        // Silence only means "gone" if we were listening. Loading a sharded model blocks this
        // phone's JS thread for as long as llama.cpp takes — 13s was seen on a 4B, and a bigger
        // model is worse — during which no announce can be read no matter how healthily the other
        // phones are sending them. Pruning on wall-clock alone therefore dropped and CLOSED every
        // worker at exactly the moment a shard was being set up, and the mesh appeared to lose
        // devices for reasons no log explained.
        //
        // So the deaf interval is subtracted. This tick's own gap is however long we were away;
        // anything beyond the normal polling cadence was not the agent's silence, it was ours.
        const now = Date.now()
        const gap = this.lastTick ? now - this.lastTick : 0
        this.lastTick = now
        const deafFor = Math.max(0, gap - LIVENESS_TICK_MS)
        const cutoff = now - AGENT_TIMEOUT_MS - deafFor

        for (const [id, a] of this.conns) {
            if (a.lastSeen >= cutoff) continue
            this.conns.delete(id) // genuinely gone quiet — drop it so the UI and routing stay truthful
            try {
                a.conn.close()
            } catch {
                /* ignore */
            }
        }
        return [...this.conns.keys()]
    }

    // Live agents with what sharding needs to place layers on them. agents() first, so stale ones
    // are pruned here too rather than being planned into a ring that no longer exists.
    agentInfo(): AgentInfo[] {
        return this.agents().map((id) => {
            const a = this.conns.get(id)!
            return { id, mem: a.mem, tps: a.tps, rpc: a.rpc }
        })
    }

    async infer(
        prompt: string,
        mode: 'single' | 'voting' = 'single',
        timeoutMs = 60000
    ): Promise<string> {
        const live = this.agents()
        if (!live.length) throw new Error('no agent joined yet')

        // Which phones get the task is the whole difference between the two modes, and it used to
        // be neither: every task went to agents()[0] regardless. Three phones joined and two of
        // them sat idle, while "voting" waited for votes from agents that had never been asked —
        // returning the one answer it did get, dressed up as a decision.
        //
        // voting — every agent, so there is something to decide between.
        // single — one agent, but not always the SAME one. Round-robin is what makes several
        //   agents worth joining in Pointer at all: consecutive prompts land on different phones
        //   instead of queueing behind whichever happened to connect first.
        let targets: string[]
        if (mode === 'voting') {
            targets = live
        } else {
            this.rr = (this.rr + 1) % live.length
            targets = [live[this.rr]]
        }

        const tid = 't' + Math.random().toString(36).slice(2, 10)
        this.coll.set(tid, { results: {}, votes: {} })
        const sealed = this.codec.seal(Msg.TASK, { mode, prompt }, tid)
        for (const id of targets) {
            // One agent going away between agents() and here must not cost the others their task.
            try {
                this.conns.get(id)?.conn.send(sealed)
            } catch {
                /* it will simply not be among the answers */
            }
        }

        const deadline = Date.now() + timeoutMs
        // Voting waits for everyone asked, but not past the deadline — one slow phone should cost
        // the answer some latency, never the whole thing. Whatever arrived by then is what gets
        // decided between.
        const wantVotes = targets.length
        try {
            while (Date.now() < deadline) {
                const c = this.coll.get(tid)!
                if (mode === 'voting') {
                    const v = Object.values(c.votes)
                    if (v.length >= wantVotes) return bestVote(v)
                } else if (Object.keys(c.results).length) {
                    return Object.values(c.results)[0]
                }
                await new Promise((r) => setTimeout(r, 20))
            }
            // Deadline reached. A partial vote is still a decision between real candidates, and
            // far better than discarding answers that did arrive because one phone never replied.
            const partial = Object.values(this.coll.get(tid)?.votes ?? {})
            if (mode === 'voting' && partial.length) return bestVote(partial)
            const anyResult = Object.values(this.coll.get(tid)?.results ?? {})
            if (anyResult.length) return anyResult[0]
            // Worth spelling out, because the commonest way to see this is not a fault at all: a
            // phone that joined only to lend its RAM for sharding is a shard worker, not a model
            // that answers prompts. A sharded model is used from an ordinary chat on the host,
            // not through here.
            throw new Error(
                'the joined phone did not answer — it can only answer if it joined with its own ' +
                    'model loaded. A phone lending RAM for sharding does not; chat normally instead.'
            )
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
