// HELIX agent worker for ChatterUI (Level 2) — the phone's GGUF model joins a HELIX mesh as a
// Track A agent, over the built-in **WebSocket** (NO native module: React Native and Node both have
// a global WebSocket). Mirrors helix/agent/node.py (worker half); proven end-to-end by
// integration/chatterui_llamacpp/js/l2_ws_smoke.mjs. Crypto is pure JS (@noble); the nonce comes
// from expo-crypto (already a ChatterUI dep) — see the makeExpoRandomBytes note below.
//
// One binary WS message = one HELIX frame (WebSocket is message-framed, no length prefix).

import { FrameCodec, Msg, RandomBytes } from './helixFrame'
import { sealerKey } from './helixCrypto'

export interface AgentCard {
    agent_id: string
    models?: string[]
    skills?: string[]
    task_types?: string[]
    mem?: number
    tps?: number
    batt?: number
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
                .completion({ prompt: full, n_predict: nPredict }, (t) => queue.push(t), () => {})
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
    tasksServed = 0

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

    // url: "ws://<coordinator-ip>:<port>"
    connect(url: string): Promise<void> {
        return new Promise((resolve, reject) => {
            const ws = new WebSocket(url)
            ws.binaryType = 'arraybuffer'
            ws.onopen = () => {
                ws.send(enc.encode(this.nodeId)) // handshake: our id first
                this.announceTimer = setInterval(() => this.announce(), this.announceMs)
                this.announce()
                resolve()
            }
            ws.onmessage = (ev: MessageEvent) => this.onMessage(new Uint8Array(ev.data as ArrayBuffer))
            ws.onerror = () => reject(new Error('websocket error'))
            ws.onclose = () => this.close()
            this.ws = ws
        })
    }

    private send(frame: Uint8Array) {
        if (this.ws && this.ws.readyState === 1) this.ws.send(frame)
    }

    private announce() {
        const c = this.card
        this.send(this.codec.seal(Msg.AGENT_ANNOUNCE, {
            agent_id: c.agent_id, models: c.models ?? [], skills: c.skills ?? [],
            task_types: c.task_types ?? [], mem: c.mem ?? 0, tps: c.tps ?? 0, batt: c.batt ?? 1.0,
        }))
        this.send(this.codec.seal(Msg.STATUS, { busy: false }))
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

    close() {
        if (this.announceTimer) {
            clearInterval(this.announceTimer)
            this.announceTimer = null
        }
        if (this.ws) {
            try {
                this.ws.close()
            } catch {
                /* ignore */
            }
            this.ws = null
        }
    }
}
