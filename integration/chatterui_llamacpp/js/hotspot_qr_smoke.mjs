// Proves the QR encode/decode half of the "direct Wi-Fi hotspot" fast-connect path — the only half
// of it that can run without a real Android device. The native half (starting/joining the actual
// hotspot) lives in modules/wifi-hotspot and is untested here; see its own file header for why.
//
//   node integration/chatterui_llamacpp/js/hotspot_qr_smoke.mjs

import { encodeHotspotQuery, parseScannedAddress, withHost } from '../../../ChatterUI/lib/helixHotspot.ts'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }

// --- plain address, no hotspot (today's format — must keep working unchanged) ---
{
  const { base, hotspot } = parseScannedAddress('ws://192.168.1.10:8790')
  check(base === 'ws://192.168.1.10:8790', 'a plain address round-trips with no query string')
  check(hotspot === undefined, 'no hotspot credentials are invented for a plain address')
}

// --- round trip through the actual encoder ---
{
  const creds = { ssid: 'DIRECT-helix-7f3a', passphrase: 'p4ssw0rd!23' }
  const scanned = `ws://192.168.49.1:8790${encodeHotspotQuery(creds)}`
  const { base, hotspot } = parseScannedAddress(scanned)
  check(base === 'ws://192.168.49.1:8790', 'the base address survives alongside the hotspot query')
  check(!!hotspot, 'hotspot credentials are recovered from the encoded query')
  check(hotspot.ssid === creds.ssid, `ssid round-trips (${hotspot.ssid})`)
  check(hotspot.passphrase === creds.passphrase, `passphrase round-trips (${hotspot.passphrase})`)
}

// --- values with characters that need percent-encoding ---
{
  const creds = { ssid: 'a b&c=d?e', passphrase: 'p@ss/w+ord=1&2' }
  const scanned = `ws://10.0.0.1:8790${encodeHotspotQuery(creds)}`
  const { hotspot } = parseScannedAddress(scanned)
  check(hotspot.ssid === creds.ssid, 'an SSID containing &, =, ? survives encoding')
  check(hotspot.passphrase === creds.passphrase, 'a passphrase containing &, =, +, / survives encoding')
}

// --- only one of the two fields present is not enough to call it a hotspot code ---
{
  const { hotspot } = parseScannedAddress('ws://192.168.49.1:8790?ssid=only-one-field')
  check(hotspot === undefined, 'ssid without a passphrase is not treated as hotspot credentials')
}

// --- malformed percent-encoding must not crash the scan ---
{
  const { base, hotspot } = parseScannedAddress('ws://192.168.49.1:8790?ssid=%&pass=ok')
  check(base === 'ws://192.168.49.1:8790', 'malformed encoding still yields the base address')
  check(hotspot === undefined, 'a field that fails to decode is dropped rather than thrown')
}

// --- whitespace from a sloppy QR render ---
{
  const { base } = parseScannedAddress('  ws://192.168.1.10:8790  ')
  check(base === 'ws://192.168.1.10:8790', 'surrounding whitespace is trimmed')
}

// --- withHost: the gateway overrides whatever address the host put in its own QR code ---
// This is the fix for a host that advertised a hardcoded 192.168.49.1 while actually running on
// 192.168.43.1 — joining phones associated fine, then failed every dial to the coordinator.
{
  check(withHost('ws://192.168.49.1:8790', '192.168.43.1') === 'ws://192.168.43.1:8790',
    'a wrong host address is replaced by the real gateway, port kept')
  check(withHost('ws://192.168.49.1', '192.168.43.1') === 'ws://192.168.43.1',
    'an address with no port stays without one')
  check(withHost('wss://10.0.0.5:9000', '10.0.0.1') === 'wss://10.0.0.1:9000',
    'wss:// keeps its scheme')
  check(withHost('ws://192.168.49.1:8790', '') === 'ws://192.168.49.1:8790',
    'an empty gateway leaves the scanned address untouched')
  check(withHost('not-a-url', '10.0.0.1') === 'not-a-url',
    'an unparseable address is returned untouched rather than corrupted')
  check(withHost('ws://[fe80::1]:8790', '192.168.43.1') === 'ws://192.168.43.1:8790',
    "an IPv6 literal's inner colons are not mistaken for a port separator")
}

// --- the two compose: scan, then correct the host from the gateway ---
{
  const creds = { ssid: 'DIRECT-x', passphrase: 'secret123' }
  const scanned = `ws://192.168.49.1:8790${encodeHotspotQuery(creds)}`
  const { base, hotspot } = parseScannedAddress(scanned)
  check(!!hotspot, 'credentials still parse off the code that carries a wrong host')
  check(withHost(base, '192.168.43.1') === 'ws://192.168.43.1:8790',
    'scan -> join -> dial the gateway recovers from a host that advertised the wrong address')
}

console.log(`\nALL PASSED (${pass} checks) — the hotspot QR payload encodes and decodes correctly, stays backward compatible with a plain address, and a wrong advertised host is recoverable from the gateway.`)
