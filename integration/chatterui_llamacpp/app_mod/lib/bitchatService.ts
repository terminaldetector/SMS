// Wires the BitChat bridge to the real radio and to this phone's model.
//
// Everything protocol-shaped lives in bitchatBridge/Codec/Noise/Fragment and is covered by tests;
// this file is the app-side glue: identity that survives restarts, the native BLE module, and the
// responder that turns an incoming private message into a model answer.

import { bytesToHex, hexToBytes } from '@noble/hashes/utils'
import { create } from 'zustand'

import { BitchatBridge, hex } from './bitchatBridge'
import { CHARACTERISTIC_UUID, SERVICE_UUID } from './bitchatCodec'
import { generateStaticKey } from './bitchatNoise'
import { Llama } from './engine/Local/LlamaLocal'
import { Logger } from './state/Logger'
import { mmkv } from './storage/MMKV'

const KEY_STORAGE = 'bitchat-static-key'
const MAX_LOG_LINES = 50

export interface BitchatPeer {
    /** BLE address — how the transport addresses it. */
    link: string
    /** BitChat peer id (hex) once a packet has identified it; empty until then. */
    peerId: string
    encrypted: boolean
}

interface BitchatState {
    running: boolean
    starting: boolean
    available: boolean
    peripheralSupported: boolean
    myPeerId: string
    peers: BitchatPeer[]
    log: string[]
    start: () => Promise<void>
    stop: () => Promise<void>
    refresh: () => void
}

// Identity has to survive restarts: a BitChat peer recognises us by the static key, so a fresh key
// each launch would look like a brand new stranger every time.
function loadStaticKey() {
    const stored = mmkv.getString(KEY_STORAGE)
    if (stored) {
        try {
            const secret = hexToBytes(stored)
            if (secret.length === 32) {
                const { pub } = keyFromSecret(secret)
                return { secret, pub }
            }
        } catch {
            /* fall through and regenerate */
        }
    }
    const key = generateStaticKey()
    mmkv.set(KEY_STORAGE, bytesToHex(key.secret))
    return key
}

function keyFromSecret(secret: Uint8Array) {
    // x25519 public key from a stored secret, without re-deriving through generateStaticKey.
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const { x25519 } = require('@noble/curves/ed25519')
    return { secret, pub: x25519.getPublicKey(secret) as Uint8Array }
}

// Lazily required so an APK without the module (or without BLE) fails with a clear message rather
// than at import time.
function nativeBle() {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    return require('../modules/bitchat-ble').BitchatBle
}

// Answers a BitChat user with this phone's loaded model.
async function answerWithModel(prompt: string): Promise<string> {
    const store = Llama.useLlamaModelStore.getState()
    if (!store.context) return 'No model is loaded on this device yet.'
    let out = ''
    await store.completion({ prompt, n_predict: 256 }, (t: string) => {
        out += t
    }, () => {})
    return out.trim() || '(empty answer)'
}

export const useBitchatStore = create<BitchatState>()((set, get) => {
    let bridge: BitchatBridge | null = null
    const subscriptions: { remove(): void }[] = []
    // BLE address -> BitChat peer id, learned from the first packet that arrives on that link.
    const linkPeers = new Map<string, string>()

    const log = (line: string) => {
        set({ log: [`${new Date().toLocaleTimeString()}  ${line}`, ...get().log].slice(0, MAX_LOG_LINES) })
    }

    const rebuildPeers = () => {
        const ble = nativeBle()
        const links: string[] = ble?.connectedPeers?.() ?? []
        set({
            peers: links.map((link) => {
                const peerId = linkPeers.get(link) ?? ''
                return {
                    link,
                    peerId,
                    encrypted: !!(peerId && bridge?.hasSession(hexToBytes(peerId))),
                }
            }),
        })
    }

    return {
        running: false,
        starting: false,
        available: false,
        peripheralSupported: false,
        myPeerId: '',
        peers: [],
        log: [],

        refresh: () => {
            const ble = nativeBle()
            set({
                available: !!ble && ble.isSupported(),
                peripheralSupported: !!ble && ble.isPeripheralSupported(),
            })
            if (get().running) rebuildPeers()
        },

        start: async () => {
            const ble = nativeBle()
            if (!ble) {
                Logger.errorToast('BitChat BLE is not in this build')
                return
            }
            if (!ble.isSupported()) {
                Logger.errorToast('This device has no Bluetooth LE')
                return
            }
            set({ starting: true })
            try {
                if (!(await ble.requestPermissions())) {
                    Logger.errorToast('Bluetooth permissions are needed to reach BitChat peers')
                    return
                }

                const key = loadStaticKey()
                bridge = new BitchatBridge(key, { send: (link, data) => ble.send(link, data) }, answerWithModel, {
                    nickname: 'helix',
                    onLog: log,
                })
                set({ myPeerId: hex(bridge.peerID) })

                subscriptions.push(
                    ble.addListener('onPacket', (e: { peerId: string; data: Uint8Array }) => {
                        void bridge?.onData(e.peerId, e.data)
                    }),
                    ble.addListener('onPeerConnected', (e: { peerId: string }) => {
                        log(`link up: ${e.peerId}`)
                        rebuildPeers()
                    }),
                    ble.addListener('onPeerDisconnected', (e: { peerId: string }) => {
                        log(`link down: ${e.peerId}`)
                        linkPeers.delete(e.peerId)
                        rebuildPeers()
                    })
                )

                // Both roles: peripheral so BitChat peers can find and write to us, central so we
                // find them. Advertising is not universally supported, so it is allowed to fail.
                const advertising = await ble.startPeripheral(SERVICE_UUID, CHARACTERISTIC_UUID)
                if (!advertising) log('advertising unavailable — this phone can only reach out, not be found')
                const scanning = await ble.startCentral(SERVICE_UUID, CHARACTERISTIC_UUID)
                if (!scanning && !advertising) {
                    Logger.errorToast('Could not start Bluetooth in either role')
                    return
                }

                set({ running: true })
                log(`bridge up as ${hex(bridge.peerID)}`)
                Logger.infoToast('BitChat bridge started')
            } catch (e) {
                log(`start failed: ${e instanceof Error ? e.message : String(e)}`)
                Logger.errorToast(`BitChat start failed: ${e instanceof Error ? e.message : String(e)}`)
            } finally {
                set({ starting: false })
            }
        },

        stop: async () => {
            const ble = nativeBle()
            subscriptions.splice(0).forEach((s) => s.remove())
            linkPeers.clear()
            bridge = null
            try {
                await ble?.stopPeripheral()
                await ble?.stopCentral()
            } catch {
                /* stopping a radio that never started is not an error worth surfacing */
            }
            set({ running: false, peers: [] })
            Logger.infoToast('BitChat bridge stopped')
        },
    }
})
