// HELIX agent worker for ChatterUI (Level 2) — the phone's GGUF model joins a HELIX mesh as a
// Track A agent, over the built-in **WebSocket** (NO native module: React Native and Node both have
// a global WebSocket). Mirrors helix/agent/node.py (worker half); proven end-to-end by
// integration/chatterui_llamacpp/js/l2_ws_smoke.mjs. Crypto is pure JS (@noble); the nonce comes
// from expo-crypto (already a ChatterUI dep) — see the makeExpoRandomBytes note below.
//
// One binary WS message = one HELIX frame (WebSocket is message-framed, no length prefix).

import { FrameCodec, Msg, RandomBytes } from './helixFrame'
import { sealerKey } from './helixCrypto'
import { MESH_PLAIN_STOPS } from './helixPrompt'

export interface AgentCard {
    agent_id: string
    models?: string[]
    skills?: string[]
    task_types?: string[]
    mem?: number
    tps?: number
    batt?: number
    // "host:port" of this phone's llama.cpp rpc-server, when it is offering itself as a shard
    // worker (Level 3). The coordinator needs it to build llama.cpp's --rpc list; absent means
    // "agent only, don't place layers on me". Mirrors the `rpc` field in HELIX ANNOUNCE.
    rpc?: string
}

export interface AgentRunner {
    card(): AgentCard
    infer(prompt: string, context: string): AsyncIterable<string> // stream chunks
    score(prompt: string, result: string): number
}

const enc = new TextEncoder()

// Wrap ChatterUI's local model (cui-llama.rn) as a HELIX agent runner. `llamaStore` is
// Llama.useLlamaModelStore (lib/engine/Local/LlamaLocal.ts): completion(params, onToken, onDone).
export function makeLlamaAgentRunner(
    llamaStore: {
        completion: (
            params: { prompt: string; n_predict?: number; stop?: string[] },
            onToken: (t: string) => void,
            onDone: (t: string) => void
        ) => Promise<unknown>
    },
    card: AgentCard,
    nPredict = 256
): AgentRunner {
    return {
        card: () => card,
        async *infer(prompt: string, context: string): AsyncIterable<string> {
            const full = context ? `${context}\n\n${prompt}` : prompt
            const queue: string[] = []
            let done = false
            const p = llamaStore
                // The prompt arrives as `User:`/`Assistant:` turns ending in `Assistant:` (see
                // helixPrompt.ts), which invites a model with no stop sequence to answer and then
                // carry on writing the user's next line too. The host cannot trim what it never
                // sees streaming, so the stop belongs here, where the tokens are produced.
                .completion(
                    { prompt: full, n_predict: nPredict, stop: MESH_PLAIN_STOPS },
                    (t) => queue.push(t),
                    () => {}
                )
                .then(() => {
                    done = true
                })
            while (!done || queue.length) {
                if (queue.length) yield queue.shift() as string
                else await new Promise((r) => setTimeout(r, 5))
            }
            await p
        },
        score: (_p, r) => r.length,
    }
}

// Deterministic runner for bring-up without a model (uppercase, streamed word-by-word).
export function makeEchoRunner(card: AgentCard): AgentRunner {
    return {
        card: () => card,
        async *infer(prompt: string): AsyncIterable<string> {
            for (const w of prompt.toUpperCase().split(' ')) yield w + ' '
        },
        score: (_p, r) => r.length,
    }
}

// Build a RandomBytes backed by expo-crypto (New-Architecture-safe; no react-native-get-random-values):
//   import * as ExpoCrypto from 'expo-crypto'
//   const rand = makeExpoRandomBytes(ExpoCrypto)
export function makeExpoRandomBytes(expoCrypto: { getRandomValues: (a: Uint8Array) => Uint8Array }): RandomBytes {
    return (n: number) => expoCrypto.getRandomValues(new Uint8Array(n))
}

export class HelixAgentNode {
    private codec: FrameCodec
    private card: AgentCard
    private ws: WebSocket | null = null
    private announceTimer: ReturnType<typeof setInterval> | null = null
    private reconnectTimer: ReturnType<typeof setTimeout> | null = null
    private url = ''
    private connected = false
    private leaving = false
    private retries = 0
    tasksServed = 0
    // Fires on every connect/disconnect so the UI can show what the link is actually doing —
    // otherwise a dropped Wi-Fi leaves the screen claiming "online" indefinitely.
    onStateChange?: (connected: boolean) => void

    constructor(
        private readonly nodeId: string,
        clusterSecret: string,
        private readonly runner: AgentRunner,
        opts: { announceIntervalMs?: number; randomBytes?: RandomBytes } = {}
    ) {
        this.codec = new FrameCodec(nodeId, sealerKey(clusterSecret), 0, true, opts.randomBytes)
        this.card = runner.card()
        this.announceMs = opts.announceIntervalMs ?? 500
    }

    private announceMs: number

    isConnected() {
        return this.connected
    }

    // url: "ws://<coordinator-ip>:<port>"
    connect(url: string): Promise<void> {
        this.url = url
        this.leaving = false
        this.retries = 0
        return this.open()
    }

    private open(): Promise<void> {
        return new Promise((resolve, reject) => {
            let settled = false
            const settle = (err?: Error) => {
                if (settled) return
                settled = true
                if (err) reject(err)
                else resolve()
            }
            const ws = new WebSocket(this.url)
            ws.binaryType = 'arraybuffer'
            ws.onopen = () => {
                this.retries = 0
                this.connected = true
                ws.send(enc.encode(this.nodeId)) // handshake: our id first
                this.announceTimer = setInterval(() => this.announce(), this.announceMs)
                this.announce()
                this.onStateChange?.(true)
                settle()
            }
            ws.onmessage = (ev: MessageEvent) => this.onMessage(new Uint8Array(ev.data as ArrayBuffer))
            ws.onerror = () => settle(new Error('websocket error'))
            ws.onclose = () => {
                const wasConnected = this.connected
                this.connected = false
                this.stopAnnounce()
                this.ws = null
                if (wasConnected) this.onStateChange?.(false)
                settle(new Error('websocket closed'))
                // Only auto-retry a link that was working. A first connect that never opened is
                // usually a wrong address, and quietly retrying it would hide that from the user.
                if (wasConnected) this.scheduleReconnect()
            }
            this.ws = ws
        })
    }

    // Exponential backoff, capped — a phone that walks out of Wi-Fi range rejoins on its own.
    private scheduleReconnect() {
        if (this.leaving || this.reconnectTimer) return
        const delay = Math.min(1000 * 2 ** this.retries, 15000)
        this.retries += 1
        this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null
            if (this.leaving) return
            this.open().catch(() => this.scheduleReconnect())
        }, delay)
    }

    private stopAnnounce() {
        if (this.announceTimer) {
            clearInterval(this.announceTimer)
            this.announceTimer = null
        }
    }

    private send(frame: Uint8Array) {
        if (this.ws && this.ws.readyState === 1) this.ws.send(frame)
    }

    private announce() {
        const c = this.card
        this.send(this.codec.seal(Msg.AGENT_ANNOUNCE, {
            agent_id: c.agent_id, models: c.models ?? [], skills: c.skills ?? [],
            task_types: c.task_types ?? [], mem: c.mem ?? 0, tps: c.tps ?? 0, batt: c.batt ?? 1.0,
            // Only sent when this phone is actually serving as a shard worker, so the coordinator
            // can tell "can hold layers" from "answers prompts only" (see AgentCard.rpc).
            ...(c.rpc ? { rpc: c.rpc } : {}),
        }))
        this.send(this.codec.seal(Msg.STATUS, { busy: false }))
    }

    // Re-announce with an updated card — e.g. once the rpc-server is up and this phone can offer
    // itself as a shard worker, without tearing down and rejoining the mesh.
    updateCard(patch: Partial<AgentCard>) {
        this.card = { ...this.card, ...patch }
        this.announce()
    }

    private onMessage(bytes: Uint8Array) {
        const msg = this.codec.open(bytes)
        if (msg && msg.type === Msg.TASK) void this.handleTask(msg.tid, msg.body)
    }

    private async handleTask(tid: string, body: Record<string, unknown>) {
        const mode = String(body.mode ?? 'single')
        const prompt = String(body.prompt ?? '')
        const context = String(body.context ?? '')
        const parts: string[] = []
        for await (const chunk of this.runner.infer(prompt, context)) {
            parts.push(chunk)
            this.send(this.codec.seal(Msg.PARTIAL, { chunk }, tid))
        }
        const result = parts.join('').trim()
        if (mode === 'voting') {
            this.send(this.codec.seal(Msg.VOTE, { candidate: result, score: this.runner.score(prompt, result) }, tid))
        } else {
            this.send(this.codec.seal(Msg.RESULT, { text: result }, tid))
        }
        this.tasksServed += 1
    }

    // Leave the mesh for good — stops announcing AND cancels any pending reconnect.
    close() {
        this.leaving = true
        this.stopAnnounce()
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer)
            this.reconnectTimer = null
        }
        const wasConnected = this.connected
        this.connected = false
        if (this.ws) {
            try {
                this.ws.close()
            } catch {
                /* ignore */
            }
            this.ws = null
        }
        if (wasConnected) this.onStateChange?.(false)
    }
}
