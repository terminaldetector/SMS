// Serves the HOST phone's GGUF to a joining phone over the coordinator's own port.
//
// Why this exists: before it, getting the same model onto a second phone meant exporting the file
// and passing it around by Share — for a multi-gigabyte GGUF that is slow, and on some devices it
// simply fails. The host already listens on :8790 for agents, so the same socket answers a plain
// HTTP GET and hands over the bytes; the joining phone downloads it natively (expo-file-system),
// straight to disk, resumable.
//
// File access is behind ServedModel rather than calling expo-file-system here, so the whole path is
// exercised by integration/chatterui_llamacpp/js/model_transfer_smoke.mjs under Node.

import type { HttpRequest, HttpResponder } from './helixWsServer'

// 256 KiB per read: big enough that a gigabyte-scale file doesn't turn into tens of thousands of
// round trips, small enough that the base64 string of one chunk stays cheap to hold.
export const CHUNK_BYTES = 256 * 1024

export interface ServedModel {
    /** File name as it should land on the other phone, e.g. "Qwen3.5-4B-Q4_K_M.gguf". */
    name: string
    size: number
    /** Bytes [offset, offset+length) as base64 — the form the socket writes without re-encoding. */
    readBase64(offset: number, length: number): Promise<string>
}

export interface ModelOffer {
    name: string
    size: number
}

// `Range: bytes=start-` / `bytes=start-end`. Only the single-range form matters: it is what a
// resumed download sends. Anything else is treated as "no range".
function parseRange(header: string | undefined, size: number): { start: number; end: number } | null {
    if (!header) return null
    const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim())
    if (!m) return null
    const [, rawStart, rawEnd] = m
    if (!rawStart && !rawEnd) return null
    // "bytes=-N" means the LAST n bytes.
    const start = rawStart ? Number(rawStart) : Math.max(0, size - Number(rawEnd))
    const end = rawStart && rawEnd ? Math.min(Number(rawEnd), size - 1) : size - 1
    if (start > end || start >= size) return null
    return { start, end }
}

/**
 * Handles the two model routes on the host's port. Returns false when the request is for something
 * else, so the caller can answer 404 (or add its own routes later).
 *
 * - `GET /model/info` → `{"name":…,"size":…}`, so the other phone can skip a file it already has.
 * - `GET /model`      → the bytes, with Range support so an interrupted download resumes.
 */
export async function handleModelRequest(
    req: HttpRequest,
    res: HttpResponder,
    model: ServedModel | null
): Promise<boolean> {
    const path = req.path.split('?')[0]
    if (path !== '/model' && path !== '/model/info') return false

    if (!model) {
        res.send(404, { 'Content-Type': 'application/json' }, JSON.stringify({ error: 'no model offered' }))
        return true
    }
    if (path === '/model/info') {
        const offer: ModelOffer = { name: model.name, size: model.size }
        res.send(200, { 'Content-Type': 'application/json' }, JSON.stringify(offer))
        return true
    }

    const range = parseRange(req.headers['range'], model.size)
    if (req.headers['range'] && !range) {
        res.send(416, { 'Content-Range': `bytes */${model.size}` })
        return true
    }
    const start = range ? range.start : 0
    const end = range ? range.end : model.size - 1
    const length = end - start + 1

    res.beginStream(range ? 206 : 200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': String(length),
        'Accept-Ranges': 'bytes',
        'Content-Disposition': `attachment; filename="${model.name}"`,
        ...(range ? { 'Content-Range': `bytes ${start}-${end}/${model.size}` } : {}),
    })

    // HEAD is not worth a branch: no client here sends one, and a body on a HEAD would be worse
    // than not answering it at all.
    for (let off = start; off <= end; off += CHUNK_BYTES) {
        if (res.closed) return true // peer walked away mid-transfer; stop reading the file
        const n = Math.min(CHUNK_BYTES, end - off + 1)
        const chunk = await model.readBase64(off, n)
        await res.writeBase64(chunk)
    }
    res.end()
    return true
}
