// Pure helpers for the "direct Wi-Fi hotspot" fast-connect path (SHAREit-style: the host runs its
// own ad-hoc Wi-Fi AP, so two phones can join at full Wi-Fi speed with no shared router at all).
// The actual hotspot creation/join is native (modules/wifi-hotspot) and cannot be exercised here —
// but encoding/decoding the extra credentials carried on the QR connect code is plain string logic,
// so it is kept dependency-free (no URL/URLSearchParams, which may not be polyfilled on Hermes) and
// proven under Node by hotspot_qr_smoke.mjs, unlike the native half.

export interface HotspotCreds {
    ssid: string
    passphrase: string
}

/**
 * Appended to the plain `ws://host:port` QR value when the host is running its own hotspot, so one
 * scan carries both "which Wi-Fi to join" and "which mesh to join after that".
 */
export function encodeHotspotQuery(creds: HotspotCreds): string {
    return `?ssid=${encodeURIComponent(creds.ssid)}&pass=${encodeURIComponent(creds.passphrase)}`
}

/**
 * Splits a scanned connect string into its plain `ws://host:port` part and, if present, the
 * hotspot credentials appended to it. A code from a host that isn't using the hotspot path has no
 * query string at all, so `hotspot` is undefined and the caller joins directly — this is what keeps
 * the feature additive rather than a breaking change to the existing QR format.
 */
export function parseScannedAddress(data: string): { base: string; hotspot?: HotspotCreds } {
    const trimmed = (data ?? '').trim()
    const qIndex = trimmed.indexOf('?')
    if (qIndex < 0) return { base: trimmed }

    const base = trimmed.slice(0, qIndex)
    const query = trimmed.slice(qIndex + 1)
    let ssid: string | undefined
    let passphrase: string | undefined
    for (const pair of query.split('&')) {
        const eq = pair.indexOf('=')
        if (eq < 0) continue
        const key = pair.slice(0, eq)
        // Values only, never re-decode the key: an SSID/passphrase itself may legitimately contain
        // '=' or '&' once percent-encoded, so splitting further on those would corrupt it.
        let value: string
        try {
            value = decodeURIComponent(pair.slice(eq + 1))
        } catch {
            continue // malformed percent-encoding — drop this field rather than throw mid-scan
        }
        if (key === 'ssid') ssid = value
        else if (key === 'pass') passphrase = value
    }
    return { base, hotspot: ssid && passphrase ? { ssid, passphrase } : undefined }
}
