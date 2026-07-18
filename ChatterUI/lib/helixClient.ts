// HELIX mesh client for ChatterUI (Level 1 / mesh mod). Talks to a HELIX node's HTTP control
// adapter (helix/host/http_control.py) using the built-in fetch — NO native module, drop-in.
//
// One JSON command per POST /cmd -> one JSON response. Semantics match the tested control server
// (helix/host/control_server.py): ping/status/nodes/infer/super. Proven end-to-end against the
// real Python mesh by integration/chatterui_llamacpp/js/http_smoke.mjs.

export type InferMode = 'single' | 'parallel' | 'voting'
export type SuperStrategy = 'hierarchical' | 'ensemble'

export interface HelixResponse {
    ok: boolean
    error?: string
    [k: string]: unknown
}

// Accepts "192.168.1.10:8799", "http://host:8799", or "host" (defaults port 8799 / http).
export function normalizeBaseUrl(input: string): string {
    let s = (input || '').trim().replace(/\/+$/, '')
    if (!s) return ''
    if (!/^https?:\/\//i.test(s)) s = 'http://' + s
    if (!/:\d+/.test(s.replace(/^https?:\/\//i, ''))) s = s + ':8799'
    return s
}

export class HelixClient {
    private readonly baseUrl: string
    private readonly timeoutMs: number

    constructor(baseUrl: string, timeoutMs: number = 30000) {
        this.baseUrl = baseUrl
        this.timeoutMs = timeoutMs
    }

    private async request(path: string, body?: object): Promise<HelixResponse> {
        const controller = new AbortController()
        const timer = setTimeout(() => controller.abort(), this.timeoutMs)
        try {
            const res = await fetch(this.baseUrl + path, {
                method: body ? 'POST' : 'GET',
                headers: body ? { 'Content-Type': 'application/json' } : undefined,
                body: body ? JSON.stringify(body) : undefined,
                signal: controller.signal,
            })
            const json = (await res.json()) as HelixResponse
            return json
        } finally {
            clearTimeout(timer)
        }
    }

    private async cmd(obj: object): Promise<HelixResponse> {
        const r = await this.request('/cmd', obj)
        if (!r.ok) throw new Error(r.error || 'HELIX command failed')
        return r
    }

    async health(): Promise<boolean> {
        const r = await this.request('/health')
        return r.ok === true
    }

    async nodes(): Promise<string[]> {
        const r = await this.cmd({ cmd: 'nodes' })
        return (r.agents as string[]) ?? []
    }

    async infer(prompt: string, mode: InferMode = 'single', skill = 'chat'): Promise<string> {
        const r = await this.cmd({ cmd: 'infer', prompt, mode, skill })
        return String(r.result ?? '')
    }

    async superRun(prompt: string, strategy: SuperStrategy = 'ensemble'): Promise<string> {
        const r = await this.cmd({ cmd: 'super', prompt, strategy })
        return String(r.result ?? '')
    }

    // Level 3 / Option A: ask HELIX to place a big model and return the llama.cpp RPC topology.
    // The coordinator must be a ControlNode (agent_dispatch(control=...)). See helixRpc.ts.
    async rpcPlan(model: { model_id: string; n_layers: number; model_bytes: number }): Promise<RpcClusterPlan> {
        const r = await this.cmd({ cmd: 'rpc_plan', ...model })
        return r as unknown as RpcClusterPlan
    }
}

export interface RpcClusterPlan {
    ok: boolean
    ring: string[]
    endpoints: { node: string; addr: string; band: [number, number]; role: 'main' | 'worker' }[]
    main: string
    rpc_arg: string // "h1:p1,h2:p2" -> llama.cpp --rpc (workers)
    tensor_split: number[] // -> --tensor-split, spans [main-local, worker0, ...]
}
