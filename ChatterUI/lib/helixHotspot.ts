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

/**
 * Rewrites the host of a `ws://host:port` address, keeping its port and scheme.
 *
 * Used after joining a hotspot: the gateway of the network we just joined IS the host phone, read
 * from our own DHCP lease, whereas the address in the QR code is whatever the host *believed* its
 * address to be — and a host that gets that wrong produces a code that associates to Wi-Fi fine and
 * then fails every dial. The port still has to come from the code, since that is the coordinator's,
 * not something DHCP knows about.
 *
 * Returns `base` untouched if it can't be parsed or `host` is empty, so a failure here degrades to
 * the old behaviour rather than producing a broken address.
 */
export function withHost(base: string, host: string): string {
    if (!host) return base
    const m = /^(wss?:\/\/)([^/?#]*)(.*)$/i.exec((base ?? '').trim())
    if (!m) return base
    const [, scheme, authority, rest] = m
    // Keep the port if there is one. Only the last colon can start a port; an IPv6 literal in
    // brackets has colons inside it that must not be mistaken for one.
    const bracketed = /^\[.*\]$/.test(authority) ? '' : authority
    const colon = bracketed.lastIndexOf(':')
    const port = colon >= 0 && /^\d+$/.test(bracketed.slice(colon + 1)) ? bracketed.slice(colon) : ''
    return `${scheme}${host}${port}${rest}`
}
