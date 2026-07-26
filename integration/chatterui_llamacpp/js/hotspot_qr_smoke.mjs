// Proves the QR encode/decode half of the "direct Wi-Fi hotspot" fast-connect path — the only half
// of it that can run without a real Android device. The native half (starting/joining the actual
// hotspot) lives in modules/wifi-hotspot and is untested here; see its own file header for why.
//
//   node integration/chatterui_llamacpp/js/hotspot_qr_smoke.mjs

import { encodeHotspotQuery, parseScannedAddress } from '../../../ChatterUI/lib/helixHotspot.ts'

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

console.log(`\nALL PASSED (${pass} checks) — the hotspot QR payload encodes and decodes correctly, and stays backward compatible with a plain address.`)
