// LiteRT (Google AI Edge) — TriangleUI's second inference engine.
//
// Loaded lazily, exactly like the other native modules here, so an APK built without it fails with
// a sentence rather than at import time. `isAvailable()` is what the engine registry gates on.

import { requireOptionalNativeModule } from 'expo'

export interface EdgeLiteRtNative {
    isAvailable(): boolean
    activate(kind: 'litert'): Promise<boolean>
    load(
        modelPath: string,
        modelId: string,
        supportImage: boolean,
        supportAudio: boolean
    ): Promise<boolean>
    /** Streams via the onToken event and resolves with the whole answer. */
    generate(prompt: string): Promise<string>
    stop(): void
    unload(): Promise<boolean>
    activeKind(): string | null
    isLoaded(): boolean
    addListener(event: 'onToken', cb: (e: { text: string; done: boolean }) => void): { remove(): void }
    addListener(event: 'onError', cb: (e: { message: string }) => void): { remove(): void }
}

let cached: EdgeLiteRtNative | null | undefined

/** The native module, or null when this build does not contain it. */
export function EdgeLiteRt(): EdgeLiteRtNative | null {
    if (cached === undefined)
        cached = requireOptionalNativeModule<EdgeLiteRtNative>('EdgeLiteRt') ?? null
    return cached
}

export function edgeLiteRtAvailable(): boolean {
    try {
        return EdgeLiteRt()?.isAvailable() === true
    } catch {
        return false
    }
}
