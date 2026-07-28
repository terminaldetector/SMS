// App side of "the host hands its model to the phone that just joined".
//
// The protocol half lives in helixModelServe.ts and is proven under Node; this file is the part
// that can only run on a phone: reading the host's GGUF off disk in slices, and downloading it on
// the joining phone with progress and resume.
//
// expo-file-system is required lazily throughout, so nothing here runs unless a transfer actually
// starts. The legacy entry point is used deliberately: it is the only one in SDK 55 that offers a
// progress callback and a resumable download, which for a multi-gigabyte GGUF is the difference
// between a usable feature and a black box that may or may not still be running.

import { Logger } from './state/Logger'
import { AppDirectory } from './utils/File'
import type { ModelOffer, ServedModel } from './helixModelServe'

export type { ModelOffer }

// eslint-disable-next-line @typescript-eslint/no-var-requires
const legacyFs = () => require('expo-file-system/legacy')

/** Wraps a GGUF already on this phone as something the coordinator can serve. */
export function servedModelFromFile(filePath: string, name: string, size: number): ServedModel {
    // The size recorded at import can legitimately be 0: statting is deliberately non-fatal there,
    // and it fails for a model linked from outside the app (its path is a content:// URI). Falling
    // back to the real file here keeps the offer honest — the joining phone uses the size to decide
    // whether it already has the file and to verify what arrived.
    let known = Number(size) || 0
    return {
        name,
        get size() {
            return known
        },
        readBase64: async (offset, length) =>
            legacyFs().readAsStringAsync(filePath, { encoding: 'base64', position: offset, length }),
        async resolveSize() {
            if (known > 0) return known
            try {
                const info = await legacyFs().getInfoAsync(filePath, { size: true })
                known = info?.exists ? Number(info.size ?? 0) || 0 : 0
            } catch {
                known = 0
            }
            return known
        },
    }
}

export interface TransferProgress {
    received: number
    total: number
}

export interface ModelTransfer {
    /** '' when nothing was transferred because the phone already had the file. */
    downloaded: string
    offer: ModelOffer
}

/** `http://host:port` for a coordinator address the UI already has as `ws://host:port` or `host:port`. */
export function httpBaseFromHost(hostAddr: string): string {
    return hostAddr.trim().replace(/^wss:\/\//i, 'https://').replace(/^ws:\/\//i, 'http://').replace(/\/+$/, '')
        || ''
}

/**
 * Why an offer could not be used. Three very different faults used to collapse into one silent
 * `null` — "the host is not reachable at all", "the host answered, it just has nothing", and "the
 * host answered with something unusable" are indistinguishable to the user and, worse, to whoever
 * is reading the logs afterwards. They get told apart here so a failure names its own cause.
 */
export interface OfferFailure {
    reason: 'unreachable' | 'declined' | 'malformed'
    detail: string
}

export type OfferResult = { ok: true; offer: ModelOffer } | { ok: false; failure: OfferFailure }

export async function fetchOfferResult(httpBase: string, timeoutMs = 8000): Promise<OfferResult> {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), timeoutMs)
    try {
        const r = await fetch(`${httpBase}/model/info`, { signal: ctrl.signal })
        if (!r.ok) {
            return {
                ok: false,
                failure: {
                    reason: 'declined',
                    detail: `the host answered ${r.status} — it is running, but offering no model`,
                },
            }
        }
        const j = (await r.json()) as ModelOffer
        if (!j || !j.name) {
            return { ok: false, failure: { reason: 'malformed', detail: 'the offer had no file name' } }
        }
        // A size of 0 is not a reason to refuse the file. It only means the host could not stat it —
        // which happens for a model linked from outside the app, whose path is a content:// URI —
        // and it is used solely to skip an identical local copy and to sanity-check the result.
        // Rejecting the offer over it meant a perfectly serveable model looked like no model at all.
        return { ok: true, offer: { name: j.name, size: Math.max(0, Number(j.size) || 0) } }
    } catch (e) {
        const detail = e instanceof Error && e.name === 'AbortError'
            ? `no answer within ${Math.round(timeoutMs / 1000)}s`
            : `could not connect (${e instanceof Error ? e.message : String(e)})`
        return { ok: false, failure: { reason: 'unreachable', detail } }
    } finally {
        clearTimeout(t)
    }
}

export async function fetchOffer(httpBase: string, timeoutMs = 8000): Promise<ModelOffer | null> {
    const r = await fetchOfferResult(httpBase, timeoutMs)
    return r.ok ? r.offer : null
}

/**
 * Pulls the host's model onto this phone unless it is already here, then registers it so it shows
 * up in Models like any other import.
 *
 * `onProgress` is called throughout; the caller decides whether to surface it.
 */
export async function syncModelFromHost(
    httpBase: string,
    onProgress: (p: TransferProgress) => void = () => {}
): Promise<ModelTransfer | null> {
    const offered = await fetchOfferResult(httpBase)
    if (!offered.ok) throw new Error(offered.failure.detail)
    const offer = offered.offer

    const fs = legacyFs()
    const dest = `${AppDirectory.ModelPath}${offer.name}`

    // Same name AND same size means we already have it — name alone isn't enough, since a previous
    // transfer could have been interrupted and left a short file behind. With an unknown size (the
    // host could not stat its own file) the name is all there is to go on, so an existing local
    // file is taken at face value rather than re-downloading gigabytes on every join.
    const existing = await fs.getInfoAsync(dest, { size: true })
    if (existing.exists && (offer.size === 0 || Number(existing.size ?? 0) === offer.size))
        return { downloaded: '', offer }

    // A partial file from an earlier attempt can't be handed to createDownloadResumable without its
    // resume token, and we don't keep one across app launches — so start clean rather than append
    // to bytes we can't account for.
    if (existing.exists) await fs.deleteAsync(dest, { idempotent: true })

    Logger.info(
        `Transferring ${offer.name}` +
            `${offer.size > 0 ? ` (${(offer.size / 1e9).toFixed(2)} GB)` : ' (size unknown)'} from the host`
    )

    // Retried, because this is gigabytes over a phone's own hotspot and a single blip anywhere
    // along the way ends the whole thing. Each attempt restarts rather than resumes: the native
    // downloader only hands back a resume token when a transfer is PAUSED, and there is none to be
    // had after it has failed. Retrying is therefore expensive, which is why it is bounded and each
    // attempt reports how far the last one reached instead of failing silently.
    let lastReached = 0
    let lastError: unknown = null
    for (let attempt = 1; attempt <= 3; attempt++) {
        if (attempt > 1) {
            Logger.warn(
                `Transfer of ${offer.name} stopped after ${lastReached} bytes` +
                    `${offer.size > 0 ? ` of ${offer.size}` : ''} — retrying (${attempt}/3)`
            )
            await fs.deleteAsync(dest, { idempotent: true })
        }
        const task = fs.createDownloadResumable(
            `${httpBase}/model`,
            dest,
            {},
            (d: { totalBytesWritten: number; totalBytesExpectedToWrite: number }) => {
                lastReached = d.totalBytesWritten
                onProgress({
                    received: d.totalBytesWritten,
                    total: d.totalBytesExpectedToWrite > 0 ? d.totalBytesExpectedToWrite : offer.size,
                })
            }
        )
        try {
            const result = await task.downloadAsync()
            if (result) {
                lastError = null
                break
            }
            lastError = new Error('transfer was cancelled')
        } catch (e) {
            lastError = e
        }
    }
    if (lastError) {
        await fs.deleteAsync(dest, { idempotent: true })
        const why = lastError instanceof Error ? lastError.message : String(lastError)
        throw new Error(`transfer failed after ${lastReached} bytes: ${why}`)
    }

    const got = await fs.getInfoAsync(dest, { size: true })
    const landed = Number(got.size ?? 0)
    // Verified against the announced size when there is one. When the host could not stat its own
    // file the only check left is that something substantial arrived — still worth doing, since a
    // truncated GGUF is worse than none: it imports, then fails deep inside llama.cpp.
    const short = offer.size > 0 ? landed !== offer.size : landed === 0
    if (!got.exists || short) {
        await fs.deleteAsync(dest, { idempotent: true })
        throw new Error(
            offer.size > 0
                ? `transfer incomplete (${landed} of ${offer.size} bytes)`
                : 'transfer produced an empty file'
        )
    }

    // Registering is what makes it appear in Models. Deliberately not fatal: the file is on disk
    // either way, and the next Models refresh picks up an unregistered file anyway.
    try {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const { Model } = require('./engine/Local/Model')
        await Model.createModelData(offer.name, true)
    } catch (e) {
        Logger.warn(`Transferred ${offer.name} but couldn't register it: ${e}`)
    }
    return { downloaded: offer.name, offer }
}
