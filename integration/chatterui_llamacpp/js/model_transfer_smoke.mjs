// End-to-end proof that a joining phone can pull the host phone's GGUF off the coordinator's own
// port, instead of the file being exported and passed around by hand.
//
//   node integration/chatterui_llamacpp/js/model_transfer_smoke.mjs
//
// The two pieces that run on the phone are the real app sources — ChatterUI/lib/helixWsServer.ts
// (which now answers a plain HTTP GET on the same socket it accepts WebSocket agents on) and
// ChatterUI/lib/helixModelServe.ts (the /model routes). Only the two ends that cannot exist here
// are substituted: Node's `net` stands in for react-native-tcp-socket, and a Buffer stands in for
// the file expo-file-system would read in slices. A real, non-round chunk count is used so the
// last partial chunk is exercised rather than assumed.

import http from 'node:http'
import net from 'node:net'
import { createHash, randomBytes } from 'node:crypto'

import { WsServerConnection } from '../../../ChatterUI/lib/helixWsServer.ts'
import { CHUNK_BYTES, handleModelRequest } from '../../../ChatterUI/lib/helixModelServe.ts'

let pass = 0
const check = (c, w) => { if (!c) throw new Error('FAIL: ' + w); pass++; console.log('  ok  ' + w) }
const sha = (b) => createHash('sha256').update(b).digest('hex')

// A stand-in GGUF: 2.5 chunks, so the transfer covers whole chunks AND a short final one.
const FILE = randomBytes(Math.floor(CHUNK_BYTES * 2.5))
const NAME = 'Qwen3.5-4B-Q4_K_M.gguf'

let reads = 0
const served = {
  name: NAME,
  size: FILE.length,
  async readBase64(offset, length) {
    reads++
    if (offset < 0 || length < 0 || offset + length > FILE.length)
      throw new Error(`read out of bounds: ${offset}+${length} of ${FILE.length}`)
    return FILE.subarray(offset, offset + length).toString('base64')
  },
}

// The host: one port, both protocols — exactly how helixCoordinator.ts wires it.
function startHost(model) {
  return new Promise((resolve) => {
    const server = net.createServer((sock) => {
      new WsServerConnection(sock, {
        onHttp: (req, res) => {
          void handleModelRequest(req, res, model.current).then((handled) => {
            if (!handled) res.send(404, { 'Content-Type': 'text/plain' }, 'not found')
          })
        },
      })
    })
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }))
  })
}

function get(port, path, headers = {}) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port, path, headers }, (res) => {
      const chunks = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }))
    }).on('error', reject)
  })
}

async function main() {
  const model = { current: served }
  const { server, port } = await startHost(model)
  try {
    // --- what's on offer ---
    const info = await get(port, '/model/info')
    check(info.status === 200, 'GET /model/info answers 200 on the coordinator port')
    const offer = JSON.parse(info.body.toString())
    check(offer.name === NAME, `the offer names the file (${offer.name})`)
    check(offer.size === FILE.length, 'the offer carries the exact byte count')

    // --- the whole file ---
    reads = 0
    const full = await get(port, '/model')
    check(full.status === 200, 'GET /model answers 200')
    check(Number(full.headers['content-length']) === FILE.length, 'Content-Length matches the file')
    check(full.headers['accept-ranges'] === 'bytes', 'the host advertises range support')
    check(full.body.length === FILE.length, `received all ${FILE.length} bytes`)
    check(sha(full.body) === sha(FILE), 'the received bytes are byte-identical to the file')
    check(reads === 3, `the file was read in ${reads} chunks, not slurped whole`)

    // --- resume after an interruption ---
    const cut = CHUNK_BYTES + 12345
    const part = await get(port, '/model', { Range: `bytes=${cut}-` })
    check(part.status === 206, 'a Range request answers 206 Partial Content')
    check(
      part.headers['content-range'] === `bytes ${cut}-${FILE.length - 1}/${FILE.length}`,
      'Content-Range describes the remainder'
    )
    check(part.body.length === FILE.length - cut, 'the partial body is exactly the remainder')
    check(sha(Buffer.concat([FILE.subarray(0, cut), part.body])) === sha(FILE),
      'a resumed download reassembles into the original file')

    // A closed range (the form a "verify the middle" request would use).
    const mid = await get(port, '/model', { Range: 'bytes=100-199' })
    check(mid.status === 206 && mid.body.length === 100, 'a closed range returns exactly that slice')
    check(sha(mid.body) === sha(FILE.subarray(100, 200)), 'the closed range holds the right bytes')

    // --- ranges that ask for nothing sane ---
    const bad = await get(port, '/model', { Range: `bytes=${FILE.length + 10}-` })
    check(bad.status === 416, 'a range past the end of the file is refused with 416')

    // --- the switch that turns it off ---
    model.current = null
    const off = await get(port, '/model/info')
    check(off.status === 404, 'with sending turned off the host offers nothing (404)')
    const offBytes = await get(port, '/model')
    check(offBytes.status === 404, 'and the bytes are not served either')

    // --- a record whose size is 0 must not become an offer of 0 bytes ---
    // The app's own model rows allow file_size = 0: statting is non-fatal at import and fails
    // outright for a model linked from outside the app (a content:// path). Serving that verbatim
    // announced a zero-length file, which the joining phone then discarded as "no model offered" —
    // a serveable model looking like no model at all.
    {
        let resolved = 0
        model.current = {
            name: NAME,
            size: 0,
            readBase64: served.readBase64,
            async resolveSize() {
                resolved++
                return FILE.length
            },
        }
        const info = await get(port, '/model/info')
        const offer = JSON.parse(info.body.toString())
        check(offer.size === FILE.length, 'an unknown recorded size is resolved from the file itself')
        check(resolved > 0, 'resolveSize() is what supplied it')

        const body = await get(port, '/model')
        check(body.status === 200 && body.body.length === FILE.length,
            'the bytes still stream correctly when the size had to be resolved')
    }

    // --- and if the size genuinely cannot be determined, say so rather than send an empty body ---
    {
        model.current = { name: NAME, size: 0, readBase64: served.readBase64, async resolveSize() { return 0 } }
        const info = await get(port, '/model/info')
        check(JSON.parse(info.body.toString()).size === 0, 'an unresolvable size is reported as 0')
        const body = await get(port, '/model')
        check(body.status === 500, 'and the byte route refuses rather than serving a zero-length model')
    }

    // --- unrelated paths still 404 rather than hanging the socket ---
    model.current = served
    const other = await get(port, '/nope')
    check(other.status === 404, 'an unknown path answers 404')

    console.log(`\nALL PASSED (${pass} checks) — the host serves its model on the mesh port, ranges and all.`)
  } finally {
    server.close()
  }
}

main().catch((e) => { console.error(e); process.exit(1) })
