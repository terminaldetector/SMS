// HELIX agent worker for ChatterUI (Level 2) — the phone's GGUF model joins a HELIX mesh as a
// Track A agent. Mirrors helix/agent/node.py (worker half), proven cross-language by
// integration/chatterui_llamacpp/js/agent_smoke.mjs. Crypto/frame are pure JS (helixFrame.ts /
// helixCrypto.ts, @noble) — no native module beyond the TCP transport.
//
// Transport: react-native-tcp-socket (a standard autolinked native module — raw TCP to the mesh):
//   npm i react-native-tcp-socket
// The class is transport-agnostic (pass any connect() returning a socket-like object), so it is
// testable without RN.

import { FrameCodec, Msg, StreamFramer, frameOut } from './helixFrame'
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

// Duck-typed socket (react-native-tcp-socket TcpSocket, or a test double).
export interface Sockish {
    write(data: Uint8Array): void
    on(event: 'data', cb: (data: Uint8Array | string) => void): void
    on(event: 'error', cb: (err: Error) => void): void
    on(event: 'close', cb: () => void): void
    destroy(): void
}
export type Connect = (host: string, port: number) => Promise<Sockish>

// Example wiring (react-native-tcp-socket):
//   import TcpSocket from 'react-native-tcp-socket'
//   const connect: Connect = (host, port) => new Promise((res, rej) => {
//     const s = TcpSocket.createConnection({ host, port }, () => res(s as unknown as Sockish))
//     s.on('error', rej)
//   })

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

export class HelixAgentNode {
    private codec: FrameCodec
    private card: AgentCard
    private framer = new StreamFramer()
    private gotHandshake = false
    private sock: Sockish | null = null
    private announceTimer: ReturnType<typeof setInterval> | null = null
    tasksServed = 0

    constructor(
        private readonly nodeId: string,
        clusterSecret: string,
        private readonly runner: AgentRunner,
        private readonly announceIntervalMs = 500
    ) {
        this.codec = new FrameCodec(nodeId, sealerKey(clusterSecret))
        this.card = runner.card()
    }

    async connect(connect: Connect, host: string, port: number): Promise<void> {
        const sock = await connect(host, port)
        sock.write(frameOut(enc.encode(this.nodeId))) // handshake: our id first
        sock.on('data', (d) => this.onData(typeof d === 'string' ? enc.encode(d) : d))
        sock.on('error', () => this.close())
        sock.on('close', () => this.close())
        this.sock = sock
        this.announceTimer = setInterval(() => this.announce(), this.announceIntervalMs)
        this.announce()
    }

    private write(frame: Uint8Array) {
        this.sock?.write(frameOut(frame))
    }

    private announce() {
        const c = this.card
        this.write(this.codec.seal(Msg.AGENT_ANNOUNCE, {
            agent_id: c.agent_id, models: c.models ?? [], skills: c.skills ?? [],
            task_types: c.task_types ?? [], mem: c.mem ?? 0, tps: c.tps ?? 0, batt: c.batt ?? 1.0,
        }))
        this.write(this.codec.seal(Msg.STATUS, { busy: false }))
    }

    private onData(chunk: Uint8Array) {
        for (const item of this.framer.push(chunk)) {
            if ('bad' in item) {
                this.close()
                return
            }
            if (!this.gotHandshake) {
                this.gotHandshake = true
                continue
            }
            const msg = this.codec.open(item.frame)
            if (msg && msg.type === Msg.TASK) void this.handleTask(msg.src, msg.tid, msg.body)
        }
    }

    private async handleTask(coord: string, tid: string, body: Record<string, unknown>) {
        const mode = String(body.mode ?? 'single')
        const prompt = String(body.prompt ?? '')
        const context = String(body.context ?? '')
        const parts: string[] = []
        for await (const chunk of this.runner.infer(prompt, context)) {
            parts.push(chunk)
            this.write(this.codec.seal(Msg.PARTIAL, { chunk }, tid))
        }
        const result = parts.join('').trim()
        if (mode === 'voting') {
            this.write(this.codec.seal(Msg.VOTE, { candidate: result, score: this.runner.score(prompt, result) }, tid))
        } else {
            this.write(this.codec.seal(Msg.RESULT, { text: result }, tid))
        }
        this.tasksServed += 1
    }

    close() {
        if (this.announceTimer) {
            clearInterval(this.announceTimer)
            this.announceTimer = null
        }
        if (this.sock) {
            this.sock.destroy()
            this.sock = null
        }
    }
}
