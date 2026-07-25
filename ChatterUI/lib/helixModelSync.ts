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
    return {
        name,
        size,
        readBase64: async (offset, length) =>
            legacyFs().readAsStringAsync(filePath, { encoding: 'base64', position: offset, length }),
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

export async function fetchOffer(httpBase: string, timeoutMs = 8000): Promise<ModelOffer | null> {
    const ctrl = new AbortController()
    const t = setTimeout(() => ctrl.abort(), timeoutMs)
    try {
        const r = await fetch(`${httpBase}/model/info`, { signal: ctrl.signal })
        if (!r.ok) return null
        const j = (await r.json()) as ModelOffer
        return j && j.name && j.size > 0 ? { name: j.name, size: Number(j.size) } : null
    } catch {
        return null
    } finally {
        clearTimeout(t)
    }
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
    const offer = await fetchOffer(httpBase)
    if (!offer) return null

    const fs = legacyFs()
    const dest = `${AppDirectory.ModelPath}${offer.name}`

    // Same name AND same size means we already have it — name alone isn't enough, since a previous
    // transfer could have been interrupted and left a short file behind.
    const existing = await fs.getInfoAsync(dest, { size: true })
    if (existing.exists && Number(existing.size ?? 0) === offer.size)
        return { downloaded: '', offer }

    // A partial file from an earlier attempt can't be handed to createDownloadResumable without its
    // resume token, and we don't keep one across app launches — so start clean rather than append
    // to bytes we can't account for.
    if (existing.exists) await fs.deleteAsync(dest, { idempotent: true })

    const task = fs.createDownloadResumable(
        `${httpBase}/model`,
        dest,
        {},
        (d: { totalBytesWritten: number; totalBytesExpectedToWrite: number }) =>
            onProgress({
                received: d.totalBytesWritten,
                total: d.totalBytesExpectedToWrite > 0 ? d.totalBytesExpectedToWrite : offer.size,
            })
    )
    const result = await task.downloadAsync()
    if (!result) throw new Error('transfer was cancelled')

    const got = await fs.getInfoAsync(dest, { size: true })
    if (!got.exists || Number(got.size ?? 0) !== offer.size) {
        // A truncated GGUF is worse than none: it imports, then fails deep inside llama.cpp.
        await fs.deleteAsync(dest, { idempotent: true })
        throw new Error(`transfer incomplete (${Number(got.size ?? 0)} of ${offer.size} bytes)`)
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
