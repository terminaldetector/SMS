// HELIX Mesh screen (ChatterUI mesh mod) — three roles:
//   L1 (client):  a UI over a HELIX node (helix/host/http_control.py) over fetch.
//   L2 (agent):   this phone's loaded GGUF model JOINS the mesh as a Track-A agent over WebSocket
//                 (no native module: built-in WebSocket + @noble + expo-crypto nonce).
//   Device-to-device (no PC): this phone HOSTS the coordinator (helixCoordinator.ts) so another
//                 ChatterUI phone joins it directly with "Join as agent" — no PC in the loop.
// First-experiments UI.

import { AntDesign } from '@expo/vector-icons'
import { closeFd, getContentFd } from '@vali98/react-native-fs'
import { useRouter } from 'expo-router'
import * as ExpoCrypto from 'expo-crypto'
import React, { useEffect, useMemo, useRef, useState } from 'react'
import {
    ActivityIndicator,
    PermissionsAndroid,
    Platform,
    ScrollView,
    StyleSheet,
    Text,
    TouchableOpacity,
    View,
} from 'react-native'
import { useMMKVBoolean, useMMKVString } from 'react-native-mmkv'

import ThemedButton from '@components/buttons/ThemedButton'
import HorizontalSelector from '@components/input/HorizontalSelector'
import ThemedSwitch from '@components/input/ThemedSwitch'
import ThemedTextInput from '@components/input/ThemedTextInput'
import HeaderButton from '@components/views/HeaderButton'
import HeaderTitle from '@components/views/HeaderTitle'
import HelixQrSheet from '@components/views/HelixQrSheet'
import { HelixAgentNode, makeExpoRandomBytes, makeLlamaAgentRunner } from '@lib/helixAgent'
import { planLocalShard } from '@lib/helixRpc'
import { HelixClient, InferMode, normalizeBaseUrl } from '@lib/helixClient'
import { HelixCoordinator } from '@lib/helixCoordinator'
import { encodeHotspotQuery, parseScannedAddress, withHost } from '@lib/helixHotspot'
import { httpBaseFromHost, servedModelFromFile, syncModelFromHost } from '@lib/helixModelSync'
import { Llama } from '@lib/engine/Local/LlamaLocal'
import { Model } from '@lib/engine/Local/Model'
import { Logger } from '@lib/state/Logger'
import { mmkv } from '@lib/storage/MMKV'
import { Theme } from '@lib/theme/ThemeManager'

const HOST_KEY = 'helix-mesh-host'
const WS_KEY = 'helix-agent-ws'
const LAST_HOST_IP_KEY = 'helix-mesh-last-host-ip'
const LAST_HOST_TRANSPORT_KEY = 'helix-mesh-last-host-ip-transport'
// Whether a host offers its GGUF to joining phones, and whether a joining phone accepts it.
// Default ON — the whole point is that the second phone doesn't need the file passed to it by hand.
const MODEL_TRANSFER_KEY = 'helix-mesh-model-transfer'
// Whether "Start hosting" runs its own Wi-Fi hotspot instead of using whatever network this phone
// is already on — the SHAREit-style path: full Wi-Fi speed, no shared router required at all.
// Default OFF: unlike everything else on this screen, the native side of this one has not yet been
// through a real-device test pass, so it should not turn on for anyone who hasn't chosen it.
const USE_HOTSPOT_KEY = 'helix-mesh-use-hotspot'
// Cluster secret — the phone-to-phone coordinator and the "Join as agent" side share this, and it
// also matches the PC demo (helix/host/agent_host_ws_demo.py).
const AGENT_SECRET = 'helix-agent-host-ws-demo'
const HOST_PORT = 8790
// llama.cpp's rpc-server port on each shard worker. Fixed: every phone runs one server, and the
// address is announced, so there is nothing to configure.
const RPC_PORT = 50052
type HostMode = 'single' | 'voting'

// A mesh session belongs to the app, not to this screen being on top.
//
// These used to be component refs torn down by an unmount effect, which meant simply walking over
// to Models to load a model — the one thing a host most often needs to do — silently closed the
// coordinator and stopped the hotspot. Coming back showed "Start hosting" again as if nothing had
// been running, and any phone mid-join just found a dead port. Hosting now ends only when the user
// says so, or when the app does.
let liveCoord: HelixCoordinator | null = null
let liveHotspotActive = false
let liveHostIp = ''
let liveHostIpTransport = ''
let liveHostQrExtra = ''
let liveAgent: HelixAgentNode | null = null
let liveAgentOnline = false
let liveAgentJoinedHotspot = false

// Lazily required like the other native bits, so nothing here runs at app startup.
function deviceTotalMemory(): number {
    try {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        return Number(require('expo-device').totalMemory ?? 0)
    } catch {
        return 0
    }
}

export interface DeviceMemory {
    total: number
    /** What is genuinely free right now, already discounted by the OS's kill threshold. */
    usable: number
    low: boolean
}

// What this phone can actually commit to holding layers, as opposed to how much RAM it owns.
//
// Placement used to divide the model by TOTAL memory, which overstates every device by whatever
// the OS and other apps are already using — so a phone was handed a share it could not hold, and
// the model was effectively loaded whole on whoever had the bigger number. This reports live
// availability instead, minus Android's own low-memory threshold (below which it starts killing
// processes) and a margin, so a worker survives the inference it just agreed to take part in.
function deviceMemory(): DeviceMemory {
    const total = deviceTotalMemory()
    try {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const info = require('../../../modules/bitchat-ble').BitchatBle?.getMemoryInfo?.()
        if (info && Number(info.available) > 0) {
            const headroom = Number(info.available) - Number(info.threshold ?? 0)
            // Two thirds of the remaining headroom: weights are not the only thing that grows
            // during inference (KV cache, compute buffers), and being killed mid-answer is worse
            // than taking one layer fewer.
            const usable = Math.max(0, Math.floor(headroom * 0.66))
            return { total: Number(info.total) || total, usable, low: !!info.low }
        }
    } catch {
        /* fall through to the paper figure below */
    }
    // No native module (or it could not answer): a conservative slice of total is still closer to
    // the truth than total itself, which no device ever has free.
    return { total, usable: Math.floor(total * 0.25), low: false }
}

// Lazily required so an APK without the module fails with a clear message rather than at import
// time — same reasoning as bitchatService.ts's nativeBle().
function wifiHotspotModule() {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    return require('../../../modules/wifi-hotspot').WifiHotspot
}

// WifiManager.startLocalOnlyHotspot() requires ACCESS_FINE_LOCATION on every Android version, with
// no BLE-style exemption — this is the one runtime prompt Android does not let this feature skip.
async function requestHotspotPermission(): Promise<boolean> {
    if (Platform.OS !== 'android') return true
    try {
        // Which permission gates the hotspot depends on the API level, and asking for the wrong one
        // yields a grant without the capability — startHotspot then still dies with a
        // SecurityException ("does not have nearby devices permission" on Android 13+). The native
        // side decides; this only raises the prompt.
        const required =
            wifiHotspotModule()?.getRequiredPermission?.() ??
            (Number(Platform.Version) >= 33
                ? PermissionsAndroid.PERMISSIONS.NEARBY_WIFI_DEVICES
                : PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION)
        const nearby = required === PermissionsAndroid.PERMISSIONS.NEARBY_WIFI_DEVICES
        const result = await PermissionsAndroid.request(required, {
            title: nearby ? 'Nearby devices permission' : 'Location permission',
            message: nearby
                ? 'Android requires this to start a direct Wi-Fi hotspot between phones. It is not ' +
                  'used to work out where you are.'
                : 'Android requires this to start a direct Wi-Fi hotspot between phones, even though ' +
                  'the app has no use for your location.',
            buttonPositive: 'OK',
        })
        return result === PermissionsAndroid.RESULTS.GRANTED
    } catch {
        return false
    }
}

// The GGUF's layer count, which the planner needs to divide the model. It isn't in the model DB
// row, so read it back off the file — the same call the importer uses.
// Traced from the fork's C++: loadLlamaModelInfo() reads GGUF metadata only (never touches tensor
// data, so this is cheap regardless of file size) via a plain fopen() underneath — which, unlike
// the real model-load path in LlamaLocal.ts, was never given the same content:// -> file-descriptor
// resolution a model kept via "Link external" (rather than copied in) needs. fopen() can never open
// a content:// URI directly, so every externally-linked model failed sharding with a bare "Failed to
// load model info" and no indication why — the load() path worked fine because it already resolves
// this, so the model could look completely healthy for chat while sharding stayed broken for it.
async function readLayerCount(modelPath: string): Promise<number> {
    const isContentUri = modelPath.includes('content://')
    const resolvedPath = isContentUri ? ((await getContentFd(modelPath)) ?? modelPath) : modelPath
    try {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const { loadLlamaModelInfo } = require('cui-llama.rn')
        const info: any = await loadLlamaModelInfo(resolvedPath)
        const arch = info?.['general.architecture']
        const count = Number(info?.[`${arch}.block_count`] ?? 0)
        if (!count) throw new Error(`no ${arch || '?'}.block_count in its metadata`)
        return count
    } catch (e) {
        throw new Error(`couldn't read ${modelPath} (${e instanceof Error ? e.message : String(e)})`)
    } finally {
        if (isContentUri) await closeFd(resolvedPath)
    }
}

export interface DetectedIp {
    ip: string
    /** 'usb' | 'wifi' | 'other' | '' — which link it came from, for display next to the address. */
    transport: string
}

// This phone's own address on whatever it joined via the direct-hotspot path, or '' if it hasn't
// joined one. Checked first, ahead of everything below: it reads the exact Network reference
// wifi-hotspot's joinHotspot() bound to, so — unlike a generic Wi-Fi scan — it cannot be confused
// by the phone's regular Wi-Fi connection staying up alongside the joined hotspot, which plenty of
// hardware allows. A shard worker that joined this way and instead announced its ordinary Wi-Fi
// address would hand the coordinator an address nobody on the hotspot subnet can dial.
function hotspotJoinedIp(): string {
    try {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const ip = require('../../../modules/wifi-hotspot').WifiHotspot?.getJoinedNetworkIp?.()
        return typeof ip === 'string' && ip !== '0.0.0.0' ? ip : ''
    } catch {
        return ''
    }
}

// Best-effort LAN IP for the host phone (so the QR/address the other phone needs is right there,
// no hunting through Settings). Both sources are required lazily so nothing runs at app startup.
//
// Native first: expo-network asks WifiManager, which answers 0.0.0.0 on plenty of modern devices —
// restricted Wi-Fi info, tethering, or the phone being the hotspot itself. That is what made QR
// connect and the shard worker fail on a Redmi Turbo while working elsewhere. Enumerating the
// network interfaces (bitchat-ble's getLocalIpAddress) needs no permission and sees the address in
// all of those cases, INCLUDING a USB cable to a PC/hub — expo-network only ever sees Wi-Fi, so a
// wired connection would otherwise report exactly like "no address at all". expo-network stays as
// the fallback for a build without the native module.
function nativeLanIp(): DetectedIp {
    try {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const ble = require('../../../modules/bitchat-ble').BitchatBle
        const ip = ble?.getLocalIpAddress?.()
        if (typeof ip !== 'string' || ip === '0.0.0.0') return { ip: '', transport: '' }
        const transport = ble?.getLocalIpTransport?.()
        return { ip, transport: typeof transport === 'string' ? transport : '' }
    } catch {
        return { ip: '', transport: '' }
    }
}

// One retry on the fallback path: right as the server starts, expo-network can read a stale/empty
// value for a moment on some devices.
async function expoNetworkLanIp(): Promise<DetectedIp> {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const Net = require('expo-network')
    const read = async (): Promise<DetectedIp> => {
        try {
            const ip = await Net.getIpAddressAsync()
            // expo-network reads WifiManager: whatever it finds is by definition a Wi-Fi address.
            return typeof ip === 'string' && ip !== '0.0.0.0' ? { ip, transport: 'wifi' } : { ip: '', transport: '' }
        } catch {
            return { ip: '', transport: '' }
        }
    }
    const first = await read()
    if (first.ip) return first
    await new Promise((r) => setTimeout(r, 400))
    return read()
}

async function getLanIp(): Promise<DetectedIp> {
    const hotspotIp = hotspotJoinedIp()
    if (hotspotIp) return { ip: hotspotIp, transport: 'hotspot' }
    const native = nativeLanIp()
    return native.ip ? native : await expoNetworkLanIp()
}

// Bytes as GB for the device panel; '?' when a figure never arrived rather than a false 0.00.
function gb(bytes: number): string {
    return bytes > 0 ? `${(bytes / 2 ** 30).toFixed(2)} GB` : '?'
}

function transportLabel(transport: string): string {
    if (transport === 'usb') return ' (wired)'
    if (transport === 'wifi') return ' (Wi-Fi)'
    if (transport === 'hotspot') return ' (direct Wi-Fi hotspot)'
    return ''
}

function normalizeWs(input: string): string {
    let s = (input || '').trim().replace(/\/+$/, '')
    if (!s) return ''
    if (!/^wss?:\/\//i.test(s)) s = 'ws://' + s
    if (!/:\d+/.test(s.replace(/^wss?:\/\//i, ''))) s = s + ':8790'
    return s
}

const HelixMeshScreen = () => {
    const styles = useStyles()
    const router = useRouter()
    const { color } = Theme.useTheme()

    const [host, setHost] = useMMKVString(HOST_KEY)
    const [connected, setConnected] = useState(false)
    const [connecting, setConnecting] = useState(false)
    const [nodes, setNodes] = useState<string[]>([])

    const [prompt, setPrompt] = useState('')
    const [mode, setMode] = useState<InferMode>('single')
    const [running, setRunning] = useState(false)
    const [result, setResult] = useState('')

    // L2 agent state
    const [wsUrl, setWsUrl] = useMMKVString(WS_KEY)
    const [agentJoined, setAgentJoined] = useState(!!liveAgent)
    const [agentJoining, setAgentJoining] = useState(false)
    // Distinct from agentJoined: the session stays joined across a Wi-Fi drop while the node
    // reconnects, and the UI should say so rather than keep claiming "online".
    const [agentOnline, setAgentOnline] = useState(liveAgentOnline)
    const [showQrSheet, setShowQrSheet] = useState(false)
    const agentRef = useRef<HelixAgentNode | null>(liveAgent)
    // True once THIS join went through a direct Wi-Fi hotspot — leaving the mesh should also
    // release that network, or the phone stays bound to it (and off its normal Wi-Fi/mobile data)
    // even after the host stops hosting.
    const joinedHotspotRef = useRef(liveAgentJoinedHotspot)

    // Model transfer: the host serves its GGUF on the same port, the joining phone pulls it if it
    // doesn't already have that exact file. useMMKVBoolean returns undefined until it's been set,
    // hence the `!== false` reads below — unset means on.
    const [modelTransfer, setModelTransfer] = useMMKVBoolean(MODEL_TRANSFER_KEY)
    const transferOn = modelTransfer !== false
    const [transferText, setTransferText] = useState('')

    // Device-to-device (no PC): this phone hosts the coordinator.
    const [hosting, setHosting] = useState(!!liveCoord)
    const [hostStarting, setHostStarting] = useState(false)
    const [hostIp, setHostIp] = useState(liveHostIp)
    const [hostIpTransport, setHostIpTransport] = useState(liveHostIpTransport)
    // True when hostIp came from a saved previous session, not this session's own detection — a
    // phone's LAN IP is usually stable on the same Wi-Fi, but "usually" isn't "definitely", so the
    // QR still shows (nothing to hunt for) with a note that it may be stale.
    const [hostIpIsStale, setHostIpIsStale] = useState(false)
    const [hostAgents, setHostAgents] = useState<string[]>([])
    const [hostPrompt, setHostPrompt] = useState('')
    const [hostMode, setHostMode] = useState<HostMode>('single')
    const [hostRunning, setHostRunning] = useState(false)
    const [hostResult, setHostResult] = useState('')
    const coordRef = useRef<HelixCoordinator | null>(liveCoord)

    // Direct Wi-Fi hotspot ("fast connect"): the query string this session's QR appends after
    // ws://host:port, and whether hosting actually has a hotspot running that needs stopping.
    const [useHotspot, setUseHotspot] = useMMKVBoolean(USE_HOTSPOT_KEY)
    const hotspotOn = !!useHotspot
    const [hostQrExtra, setHostQrExtra] = useState(liveHostQrExtra)
    const hotspotActiveRef = useRef(liveHotspotActive)

    // Subscribed, not read once: what this phone has loaded can change while it is already
    // hosting, and both the offer served to joining phones and the line describing it have to
    // follow that rather than freeze at whatever was loaded when hosting began.
    const loadedModel = Llama.useLlamaModelStore((state) => state.model)

    // Level 3 sharding: one big model split across phones by llama.cpp RPC.
    // Non-empty once this phone's rpc-server is up: its announced "host:port". Doubles as the
    // "am I offering my RAM" flag, so the two can't disagree.
    const [shardRpcAddr, setShardRpcAddr] = useState('')
    const [shardStarting, setShardStarting] = useState(false)
    const [shardPlan, setShardPlan] = useState('')
    // Re-read while the screen is open: free memory is a moving figure, and it is the number
    // the split will actually be computed from.
    const [hostMemory, setHostMemory] = useState<DeviceMemory>(() => deviceMemory())
    const agentId = useMemo(() => {
        const k = 'helix-agent-id'
        let v = mmkv.getString(k)
        if (!v) {
            v = 'phone-' + ExpoCrypto.randomUUID().slice(0, 8)
            mmkv.set(k, v)
        }
        return v
    }, [])

    const client = useMemo(() => {
        const base = normalizeBaseUrl(host ?? '')
        return base ? new HelixClient(base) : null
    }, [host])

    // Pull the host's model onto this phone (skipped when we already have that exact file), and
    // load it if nothing is loaded yet — so joining is one tap even on a phone that has never seen
    // the model, instead of exporting a multi-gigabyte GGUF and passing it around by Share.
    // A model that IS loaded is never replaced: that's the user's choice, not ours.
    const transferModelFromHost = async (wsAddr: string) => {
        const base = httpBaseFromHost(wsAddr)
        if (!base) return
        setTransferText('Checking what the host offers…')
        // Logged, not just shown: when this step fails the user only ever saw the generic "Load a
        // model first" that follows, and the log recorded nothing at all between joining the
        // hotspot and that message — which is precisely why a broken transfer was invisible in two
        // rounds of logs. The address is included because getting it wrong is the likeliest cause.
        Logger.info(`Asking the host at ${base} what model it offers`)
        const t = await syncModelFromHost(base, ({ received, total }) => {
            const pct = total > 0 ? Math.floor((received / total) * 100) : 0
            setTransferText(`Downloading the host's model… ${pct}%`)
        })
        if (!t) {
            setTransferText('')
            Logger.warnToast(`Nothing to transfer from ${base}`)
            return
        }
        Logger.info(t.downloaded ? `Received ${t.downloaded} from the host` : `Already have ${t.offer.name}`)
        if (t.downloaded) Logger.infoToast(`Received ${t.downloaded} from the host`)
        setTransferText('')

        const store = Llama.useLlamaModelStore.getState()
        if (store.context) return
        const row = (await Model.getModelListQuery()).find((m) => m.file === t.offer.name)
        if (row) await store.load(row)
    }

    // `urlOverride` lets a QR scan join immediately with the address it just read, rather than
    // waiting a render cycle for `wsUrl` state to catch up — `setWsUrl` right before calling this
    // does not make the new value visible to this closure until the component re-renders.
    const onJoinAgent = async (urlOverride?: string) => {
        const url = normalizeWs(urlOverride ?? wsUrl ?? '')
        if (!url) {
            Logger.errorToast('Enter the coordinator address (e.g. 192.168.1.10:8790)')
            return
        }
        setAgentJoining(true)
        if (transferOn) {
            try {
                await transferModelFromHost(url)
            } catch (e) {
                // A failed transfer is not a failed join: this phone may already have a model of
                // its own, which is the pre-transfer behaviour and still perfectly valid.
                setTransferText('')
                Logger.warnToast(`Model transfer failed: ${e instanceof Error ? e.message : String(e)}`)
            }
        }
        const store = Llama.useLlamaModelStore.getState()
        if (!store.context) {
            // Distinguish "you chose not to receive a model" from "we tried and it didn't arrive".
            // The second is a fault worth chasing; the first is just how the setting is configured,
            // and showing the same message for both sent the last debugging round down the wrong
            // path entirely.
            Logger.errorToast(
                transferOn
                    ? "No model here and none arrived from the host — check the warning above, or load one in Models"
                    : 'Load a model in TriangleUI first (Models), then join'
            )
            setAgentJoining(false)
            return
        }
        try {
            const runner = makeLlamaAgentRunner(store, {
                agent_id: agentId,
                skills: ['chat'],
                task_types: ['chat'],
                models: ['local'],
                mem: deviceMemory().usable,
                // Only set once this phone is actually serving layers — its absence is what tells
                // the host "answers prompts, but don't place layers here".
                ...(shardRpcAddr ? { rpc: shardRpcAddr } : {}),
            })
            const node = new HelixAgentNode(agentId, AGENT_SECRET, runner, {
                randomBytes: makeExpoRandomBytes(ExpoCrypto),
            })
            node.onStateChange = (v) => {
                liveAgentOnline = v
                setAgentOnline(v)
            }
            await node.connect(url)
            agentRef.current = node
            liveAgent = node
            setAgentJoined(true)
            setAgentOnline(true)
            Logger.infoToast(`Joined mesh as agent ${agentId}`)
        } catch (e) {
            Logger.errorToast(`Join failed: ${e instanceof Error ? e.message : String(e)}`)
        } finally {
            setAgentJoining(false)
        }
    }

    const onLeaveAgent = () => {
        agentRef.current?.close()
        agentRef.current = null
        liveAgent = null
        liveAgentOnline = false
        setAgentJoined(false)
        setAgentOnline(false)
        if (joinedHotspotRef.current) {
            joinedHotspotRef.current = false
            liveAgentJoinedHotspot = false
            const hotspot = wifiHotspotModule()
            if (hotspot) void hotspot.leaveHotspot().catch(() => {})
        }
        Logger.infoToast('Left the mesh')
    }

    // Nothing is torn down here on purpose. Leaving this screen is not leaving the mesh — going to
    // Models to load a model is a normal part of hosting, and it used to kill the session outright.
    // The live node's state-change callback belongs to whichever mount is currently showing, so it
    // is rebound here rather than left pointing at a component that no longer exists.
    useEffect(() => {
        const node = agentRef.current
        if (!node) return
        node.onStateChange = (v) => {
            liveAgentOnline = v
            setAgentOnline(v)
        }
        setAgentOnline(liveAgentOnline)
    }, [])

    // Keep the module-level session in step with what this screen shows, so a later mount restores
    // the same picture instead of a blank one.
    useEffect(() => {
        liveHostIp = hostIp
        liveHostIpTransport = hostIpTransport
        liveHostQrExtra = hostQrExtra
    }, [hostIp, hostIpTransport, hostQrExtra])

    // Free memory moves as other apps come and go, and it is what the split is computed from,
    // so the panel showing it must not be a snapshot from whenever the screen first opened.
    useEffect(() => {
        const t = setInterval(() => setHostMemory(deviceMemory()), 3000)
        return () => clearInterval(t)
    }, [])

    // Poll the coordinator's joined agents while hosting.
    useEffect(() => {
        if (!hosting) return
        const t = setInterval(() => setHostAgents(coordRef.current?.agents() ?? []), 1000)
        return () => clearInterval(t)
    }, [hosting])

    // Shared by the initial detect (onStartHost) and the manual "Retry" in the QR sheet.
    const detectAndSetHostIp = async () => {
        const { ip, transport } = await getLanIp()
        if (ip && hotspotActiveRef.current) {
            // While this phone is serving its own hotspot, the label is known and the persisted
            // "last known address" below must never be used: it belongs to some other network from
            // a previous session and would confidently point joining phones at nothing.
            setHostIp(ip)
            setHostIpTransport('hotspot')
            setHostIpIsStale(false)
            return
        }
        if (ip) {
            setHostIp(ip)
            setHostIpTransport(transport)
            setHostIpIsStale(false)
            mmkv.set(LAST_HOST_IP_KEY, ip)
            mmkv.set(LAST_HOST_TRANSPORT_KEY, transport)
        } else if (hotspotActiveRef.current) {
            setHostIp('')
            setHostIpTransport('hotspot')
            setHostIpIsStale(false)
        } else {
            // Detection can fail (permissions, timing, OEM quirks) even though the phone's IP
            // hasn't actually changed since last time — showing last session's is still far
            // better than sending the user to hunt for it in Settings.
            const last = mmkv.getString(LAST_HOST_IP_KEY) ?? ''
            setHostIp(last)
            setHostIpTransport(last ? (mmkv.getString(LAST_HOST_TRANSPORT_KEY) ?? '') : '')
            setHostIpIsStale(!!last)
        }
    }

    // Point the coordinator at this phone's loaded GGUF so a joining phone can pull it instead of
    // being sent the file by hand. Only the loaded model is offered: it is unambiguously the one
    // this mesh session is about, and it is the one already known to exist on disk.
    const offerLoadedModel = (coord: HelixCoordinator, enabled: boolean) => {
        const model = Llama.useLlamaModelStore.getState().model
        coord.offerModel(
            enabled && model ? servedModelFromFile(model.file_path, model.file, model.file_size) : null
        )
    }

    // Keep the offer in step with the switch AND with which model is loaded, for as long as
    // hosting lasts. Watching only the switch was a real bug: the offer was a snapshot taken at
    // "Start hosting", so a host that began hosting before loading its model went on serving
    // nothing indefinitely. A joining phone then asked, got a truthful "no model offered", and
    // reported it had nothing to work with — which is exactly the case this whole feature exists
    // for, one phone having the file and the other not.
    useEffect(() => {
        if (coordRef.current && hosting) offerLoadedModel(coordRef.current, transferOn)
    }, [transferOn, hosting, loadedModel])

    const stopHotspotIfActive = () => {
        if (!hotspotActiveRef.current) return
        hotspotActiveRef.current = false
        liveHotspotActive = false
        setHostQrExtra('')
        const hotspot = wifiHotspotModule()
        if (hotspot) void hotspot.stopHotspot().catch(() => {})
    }

    const onStartHost = async () => {
        setHostStarting(true)
        let hotspotStarted = false
        try {
            // A host must not be pinned to a network it joined earlier. bindProcessToNetwork()
            // covers every socket this process owns, listening ones included, so a phone that was
            // an agent on someone else's hotspot before would keep serving only on that network —
            // and its coordinator would sit there looking perfectly healthy while being unreachable
            // from the hotspot this phone is itself running. That is exactly the shape of "0
            // devices connected" on a host that is plainly hosting and offering a model.
            const released = wifiHotspotModule()?.clearNetworkBinding?.() ?? false
            if (released) {
                Logger.info('Released the network binding left from joining — a host serves on all networks')
                joinedHotspotRef.current = false
                liveAgentJoinedHotspot = false
            }

            if (hotspotOn) {
                const hotspot = wifiHotspotModule()
                if (!hotspot) throw new Error('Wi-Fi hotspot is not in this build')
                if (!hotspot.isSupported()) {
                    throw new Error('This device has no Wi-Fi hotspot API (needs Android 8+)')
                }
                if (!(await requestHotspotPermission())) {
                    throw new Error(
                        Number(Platform.Version) >= 33
                            ? 'The nearby-devices permission is needed to start a direct Wi-Fi hotspot'
                            : 'Location permission is needed to start a direct Wi-Fi hotspot'
                    )
                }
                // Android requires this permission for the hotspot itself, not because the app has
                // any use for location — see requestHotspotPermission()'s prompt copy.
                const creds = await hotspot.startHotspot()
                hotspotStarted = true
                hotspotActiveRef.current = true
                liveHotspotActive = true
                // The native side now returns '' rather than guessing when the AP interface has no
                // address yet — falling back to ordinary detection here beats advertising a
                // constant that may not be this device's actual hotspot address. Getting this
                // wrong is invisible until a phone tries to join and silently can't reach anything.
                const hotspotIp = creds.ip || (await getLanIp()).ip
                setHostIp(hotspotIp)
                setHostIpTransport('hotspot')
                setHostIpIsStale(false)
                setHostQrExtra(encodeHotspotQuery(creds))
                if (!hotspotIp) {
                    Logger.warnToast(
                        "Hotspot is up but its address couldn't be read — the other phone can still " +
                            'join by scanning, which uses the gateway instead'
                    )
                }
            }

            const coord = new HelixCoordinator(`host-${agentId}`, AGENT_SECRET, {
                randomBytes: makeExpoRandomBytes(ExpoCrypto),
            })
            await coord.listen(HOST_PORT, '0.0.0.0')
            coordRef.current = coord
            liveCoord = coord
            offerLoadedModel(coord, transferOn)
            setHosting(true)
            setHostAgents([])
            if (!hotspotOn) {
                setHostQrExtra('')
                await detectAndSetHostIp()
            }
            Logger.infoToast(
                hotspotOn
                    ? `Hosting a direct Wi-Fi hotspot on :${HOST_PORT} — scan to join, no router needed`
                    : `Hosting on :${HOST_PORT} — other phone joins this device`
            )
        } catch (e) {
            coordRef.current?.close()
            coordRef.current = null
        liveCoord = null
            if (hotspotStarted) stopHotspotIfActive()
            Logger.errorToast(`Host start failed: ${e instanceof Error ? e.message : String(e)}`)
        } finally {
            setHostStarting(false)
        }
    }

    const onStopHost = () => {
        coordRef.current?.close()
        coordRef.current = null
        liveCoord = null
        setHosting(false)
        setHostAgents([])
        stopHotspotIfActive()
        Logger.infoToast('Stopped hosting')
    }

    const onRunHost = async () => {
        const coord = coordRef.current
        if (!coord) return
        if (!hostPrompt.trim()) {
            Logger.errorToast('Enter a prompt')
            return
        }
        if (coord.agents().length === 0) {
            Logger.errorToast('No device has joined the mesh yet')
            return
        }
        setHostRunning(true)
        setHostResult('')
        try {
            setHostResult(await coord.infer(hostPrompt, hostMode))
        } catch (e) {
            Logger.errorToast(`Run failed: ${e instanceof Error ? e.message : String(e)}`)
        } finally {
            setHostRunning(false)
        }
    }

    // Worker side: run llama.cpp's rpc-server on this phone and tell the coordinator it can hold
    // layers (address + RAM). There is no stop API for the server — once started it serves for the
    // process's lifetime — so this is a one-way switch, and the UI says so.
    const onShareCompute = async () => {
        const { ip } = await getLanIp()
        if (!ip) {
            Logger.errorToast("Couldn't detect this phone's network address — can't be a shard worker")
            return
        }
        try {
            // eslint-disable-next-line @typescript-eslint/no-var-requires
            const { startRpcServer } = require('cui-llama.rn')
            const ok = await startRpcServer(`0.0.0.0:${RPC_PORT}`)
            if (!ok) {
                // The native side only answers false for two reasons: this build has no RPC
                // backend compiled in, or the backend registry offered no local device to serve.
                Logger.errorToast(
                    `Could not start the shard worker on :${RPC_PORT} — this build has no RPC backend`
                )
                return
            }
            const addr = `${ip}:${RPC_PORT}`
            setShardRpcAddr(addr)
            // Announce it so the host can place layers here. If we've already joined, patch the
            // live card; otherwise onJoinAgent picks it up when joining.
            agentRef.current?.updateCard({ rpc: addr, mem: deviceMemory().usable })
            Logger.infoToast(`Sharing compute — ${addr}`)
        } catch (e) {
            Logger.errorToast(`Shard worker failed: ${e instanceof Error ? e.message : String(e)}`)
        }
    }

    // Host side: plan the split across the joined phones and load the model distributed. It goes
    // through the normal model load, so the sharded model becomes the app's context and can be
    // chatted with like any other.
    const onStartShard = async () => {
        const coord = coordRef.current
        if (!coord) return
        // Sharding needs the model's FILE and its metadata, never the model resident in this
        // phone's RAM. Requiring a loaded one meant the whole GGUF had to be pulled into memory
        // first, only to be unloaded a moment later and re-loaded split — the exact "loads
        // everything up front" behaviour sharding exists to avoid, inherited from the ordinary
        // single-device path. A model that has merely been chosen before is enough.
        const store = Llama.useLlamaModelStore.getState()
        const model = store.model ?? Llama.useLlamaPreferencesStore.getState().lastModel
        if (!model) {
            Logger.errorToast('Pick the model to shard in Models first — it does not need loading')
            return
        }
        if (!hostIp) {
            Logger.errorToast("Couldn't detect this phone's network address — can't drive a shard")
            return
        }
        setShardStarting(true)
        setShardPlan('')
        try {
            const nLayers = await readLayerCount(model.file_path)
            if (!nLayers) {
                Logger.errorToast("Couldn't read the model's layer count from its GGUF")
                return
            }
            const plan = planLocalShard(
                { model_id: model.name, n_layers: nLayers, model_bytes: model.file_size },
                { id: `host-${agentId}`, mem: deviceMemory().usable, rpc: `${hostIp}:${RPC_PORT}` },
                coord.agentInfo()
            )
            setShardPlan(
                plan.endpoints
                    .map((e) => `${e.role === 'main' ? 'this phone' : e.node}: layers ${e.band[0]}–${e.band[1]}`)
                    .join('\n')
            )
            // Only if something is actually resident: load() refuses a model that is already
            // loaded, and a single-device context has to give up its memory before the split one
            // asks for its share. Nothing to do when sharding straight from a chosen model, which
            // is now the normal case.
            if (Llama.useLlamaModelStore.getState().context)
                await Llama.useLlamaModelStore.getState().unload()
            await Llama.useLlamaModelStore.getState().load(model, {
                rpc_servers: plan.rpc_arg ? plan.rpc_arg.split(',') : [],
                tensor_split: plan.tensor_split,
                // Without this every layer stays on this phone regardless of the split.
                n_layers: nLayers,
            })
            Logger.infoToast(`Sharded across ${plan.ring.length} phones`)
        } catch (e) {
            Logger.errorToast(`Shard failed: ${e instanceof Error ? e.message : String(e)}`)
        } finally {
            setShardStarting(false)
        }
    }

    const onConnect = async () => {
        if (!client) {
            Logger.errorToast('Enter the HELIX node address first (e.g. 192.168.1.10:8799)')
            return
        }
        setConnecting(true)
        setConnected(false)
        try {
            await client.health()
            const live = await client.nodes()
            setNodes(live)
            setConnected(true)
            Logger.infoToast(`Connected — ${live.length} mesh node(s)`)
        } catch (e) {
            Logger.errorToast(`Connect failed: ${e instanceof Error ? e.message : String(e)}`)
        } finally {
            setConnecting(false)
        }
    }

    const run = async (fn: (c: HelixClient) => Promise<string>) => {
        if (!client) return
        if (!prompt.trim()) {
            Logger.errorToast('Enter a prompt')
            return
        }
        setRunning(true)
        setResult('')
        try {
            setResult(await fn(client))
        } catch (e) {
            Logger.errorToast(`Run failed: ${e instanceof Error ? e.message : String(e)}`)
        } finally {
            setRunning(false)
        }
    }

    return (
        <ScrollView style={styles.container} contentContainerStyle={styles.content}>
            <HeaderTitle title="HELIX Mesh" />
            <HeaderButton
                headerRight={() => (
                    <TouchableOpacity onPress={() => setShowQrSheet(true)} hitSlop={12}>
                        <AntDesign name="qrcode" size={26} color={color.text._100} />
                    </TouchableOpacity>
                )}
            />

            <ThemedTextInput
                label="HELIX node (host:port)"
                value={host ?? ''}
                onChangeText={setHost}
                placeholder="192.168.1.10:8799"
                autoCapitalize="none"
                autoCorrect={false}
            />
            <ThemedButton
                label={connecting ? 'Connecting…' : connected ? 'Reconnect' : 'Connect'}
                variant="secondary"
                onPress={onConnect}
                buttonStyle={styles.gap}
            />

            {connected && (
                <View style={styles.gap}>
                    <Text style={styles.section}>Mesh nodes ({nodes.length})</Text>
                    {nodes.length === 0 ? (
                        <Text style={styles.dim}>none announced yet</Text>
                    ) : (
                        nodes.map((n) => (
                            <Text key={n} style={styles.node}>
                                • {n}
                            </Text>
                        ))
                    )}

                    <ThemedTextInput
                        label="Prompt"
                        value={prompt}
                        onChangeText={setPrompt}
                        placeholder="Ask the mesh…"
                        multiline
                        numberOfLines={3}
                        containerStyle={styles.gap}
                    />

                    <HorizontalSelector
                        label="Mode"
                        selected={mode}
                        onPress={setMode}
                        style={styles.gap}
                        values={[
                            { label: 'Single', value: 'single' },
                            { label: 'Parallel', value: 'parallel' },
                            { label: 'Voting', value: 'voting' },
                        ]}
                    />

                    <View style={[styles.row, styles.gap]}>
                        <ThemedButton
                            label="Run"
                            variant="primary"
                            onPress={() => run((c) => c.infer(prompt, mode))}
                            buttonStyle={styles.flex}
                        />
                        <ThemedButton
                            label="SuperAgent"
                            variant="secondary"
                            onPress={() => run((c) => c.superRun(prompt, 'ensemble'))}
                            buttonStyle={styles.flex}
                        />
                    </View>

                    {running && <ActivityIndicator color={color.text._100} style={styles.gap} />}
                    {!!result && (
                        <View style={styles.resultBox}>
                            <Text style={styles.section}>Result</Text>
                            <Text style={styles.result}>{result}</Text>
                        </View>
                    )}
                </View>
            )}

            <View style={styles.agentBox}>
                <Text style={styles.section}>Device-to-device (no PC)</Text>
                <Text style={styles.dim}>
                    This phone becomes the coordinator. The other TriangleUI phone loads a model and
                    uses "Join as agent" below, pointed at this phone's address. No PC needed.
                </Text>
                <ThemedSwitch
                    label="Send the model between phones"
                    description={
                        'The host serves its loaded GGUF on the same port, and a phone joining it ' +
                        'downloads that file if it does not already have it — no exporting and ' +
                        'sharing the model by hand. Turn off to keep every phone on its own model.'
                    }
                    value={transferOn}
                    onChangeValue={setModelTransfer}
                />
                <ThemedSwitch
                    label="Direct Wi-Fi hotspot (fastest, no router needed)"
                    description={
                        "This phone runs its own Wi-Fi network instead of using whatever it's already " +
                        'on — full Wi-Fi speed with nothing shared between phones required, like a ' +
                        'file-transfer app. Needs a one-time Location permission (Android requires it ' +
                        'for this specific API, not because the app uses your location). Off by ' +
                        'default: newer than the rest of this screen, so worth trying deliberately. ' +
                        'Takes effect on the next "Start hosting", not on an already-running session.'
                    }
                    value={hotspotOn}
                    onChangeValue={setUseHotspot}
                />
                <ThemedButton
                    label={hostStarting ? 'Starting…' : hosting ? 'Stop hosting' : 'Start hosting'}
                    variant={hosting ? 'critical' : 'primary'}
                    onPress={hosting ? onStopHost : onStartHost}
                    buttonStyle={styles.gap}
                />
                {hosting && (
                    <View style={styles.gap}>
                        <View style={styles.deviceBanner}>
                            <Text style={styles.deviceBannerCount}>{hostAgents.length}</Text>
                            <Text style={styles.deviceBannerLabel}>
                                {hostAgents.length === 1 ? 'device connected to the mesh' : 'devices connected to the mesh'}
                            </Text>
                        </View>
                        <Text style={[styles.node, styles.gap]}>
                            ● hosting on port {HOST_PORT}
                            {hostIp
                                ? ` — other phone joins ${hostIp}:${HOST_PORT}${transportLabel(hostIpTransport)}`
                                : ''}
                        </Text>
                        <Text style={styles.dim}>
                            {/* Driven by the subscribed `loadedModel`, not by reading the
                                coordinator ref — a ref does not re-render, so this line used to
                                keep claiming whatever was true when hosting started. */}
                            {!transferOn
                                ? 'Not sending the model — each phone uses its own.'
                                : loadedModel
                                  ? `Offering ${loadedModel.file} to phones that join`
                                  : 'No model loaded, so there is nothing to send — load one in Models (no need to restart hosting).'}
                        </Text>
                        <TouchableOpacity onPress={() => setShowQrSheet(true)}>
                            <Text style={[styles.dim, styles.gap]}>
                                Tap the QR icon (top right) to show the connect code
                            </Text>
                        </TouchableOpacity>
                        <View style={styles.gap}>
                            {hostAgents.length === 0 ? (
                                <Text style={styles.dim}>waiting for the other phone to join…</Text>
                            ) : (
                                hostAgents.map((a) => (
                                    <Text key={a} style={styles.node}>
                                        • {a}
                                    </Text>
                                ))
                            )}
                        </View>

                        <ThemedTextInput
                            label="Prompt"
                            value={hostPrompt}
                            onChangeText={setHostPrompt}
                            placeholder="Ask the other phone's model…"
                            multiline
                            numberOfLines={3}
                            containerStyle={styles.gap}
                        />
                        <HorizontalSelector
                            label="Mode"
                            selected={hostMode}
                            onPress={setHostMode}
                            style={styles.gap}
                            values={[
                                { label: 'Single', value: 'single' },
                                { label: 'Voting', value: 'voting' },
                            ]}
                        />
                        <ThemedButton
                            label="Run on mesh"
                            variant="primary"
                            onPress={onRunHost}
                            buttonStyle={styles.gap}
                        />
                        {hostRunning && <ActivityIndicator color={color.text._100} style={styles.gap} />}
                        {!!hostResult && (
                            <View style={styles.resultBox}>
                                <Text style={styles.section}>Result</Text>
                                <Text style={styles.result}>{hostResult}</Text>
                            </View>
                        )}
                    </View>
                )}
            </View>

            <View style={styles.agentBox}>
                <Text style={styles.section}>Join as agent (this phone's model)</Text>
                <Text style={styles.dim}>
                    Share your loaded model with a mesh coordinator over WebSocket. Tap the QR icon
                    (top right) → Scan, or type its address below.
                    {transferOn
                        ? " If this phone doesn't have the host's model yet, joining downloads it first."
                        : ' Load a model in Models first — sending the model between phones is off.'}
                </Text>
                <ThemedTextInput
                    label="Coordinator (ws host:port)"
                    value={wsUrl ?? ''}
                    onChangeText={setWsUrl}
                    placeholder="192.168.1.10:8790"
                    autoCapitalize="none"
                    autoCorrect={false}
                    containerStyle={styles.gap}
                />
                <ThemedButton
                    label={agentJoining ? 'Joining…' : agentJoined ? 'Leave mesh' : 'Join as agent'}
                    variant={agentJoined ? 'critical' : 'primary'}
                    onPress={agentJoined ? onLeaveAgent : () => onJoinAgent()}
                    buttonStyle={styles.gap}
                />
                {!!transferText && <Text style={[styles.node, styles.gap]}>{transferText}</Text>}
                {agentJoined && (
                    <Text style={styles.node}>
                        {agentOnline
                            ? `● online as ${agentId} — answering mesh tasks`
                            : `◌ ${agentId} — connection lost, reconnecting…`}
                    </Text>
                )}
            </View>

            <View style={styles.agentBox}>
                <Text style={styles.section}>Sharding — one big model across phones</Text>
                <Text style={styles.dim}>
                    Splits a model too big for one phone by layers, using llama.cpp's RPC. Every
                    phone taking part shares its RAM; the host loads the model and drives it.
                </Text>

                <Text style={[styles.node, styles.gap]}>On this phone</Text>
                {shardRpcAddr ? (
                    <Text style={styles.node}>● sharing compute at {shardRpcAddr}</Text>
                ) : (
                    <ThemedButton
                        label="Share this phone's RAM"
                        variant="secondary"
                        onPress={onShareCompute}
                        buttonStyle={styles.gap}
                    />
                )}
                {!shardRpcAddr && (
                    <Text style={styles.dim}>
                        Can't be switched off without restarting the app — llama.cpp's rpc-server
                        has no stop.
                    </Text>
                )}

                {hosting && (
                    <>
                        <Text style={[styles.node, styles.gap]}>As host</Text>
                        <Text style={styles.dim}>
                            Load the model you want to shard (Models), make sure the other phones
                            joined and tapped "Share this phone's RAM", then start.
                        </Text>
                        <ThemedButton
                            label={shardStarting ? 'Starting…' : 'Shard the loaded model'}
                            variant="primary"
                            onPress={onStartShard}
                            buttonStyle={styles.gap}
                        />
                        {shardStarting && <ActivityIndicator color={color.text._100} style={styles.gap} />}
                        {/* What the mesh knows about each participant, and therefore why the split
                            came out as it did. Shown before sharding too, since the useful moment
                            to see a phone is short on memory is BEFORE handing it layers. */}
                        <View style={styles.resultBox}>
                            <Text style={styles.section}>Devices in the mesh</Text>
                            <Text style={styles.result}>
                                {`this phone — ${gb(hostMemory.usable)} usable of ${gb(hostMemory.total)}` +
                                    (hostMemory.low ? '  ⚠ low memory' : '')}
                            </Text>
                            {hostAgents.length === 0 ? (
                                <Text style={styles.dim}>no other phone has joined yet</Text>
                            ) : (
                                (coordRef.current?.agentInfo() ?? []).map((a) => (
                                    <Text key={a.id} style={styles.result}>
                                        {`${a.id} — ${a.mem > 0 ? `${gb(a.mem)} offered` : 'memory not reported'}` +
                                            `${a.rpc ? `, sharing RAM at ${a.rpc}` : ', not sharing RAM'}`}
                                    </Text>
                                ))
                            )}
                            <Text style={[styles.dim, styles.gap]}>
                                Layers are divided by what each phone can spare right now — live free
                                memory less Android's own kill threshold — not by total RAM, which is
                                never actually free.
                            </Text>
                        </View>
                        {!!shardPlan && (
                            <View style={styles.resultBox}>
                                <Text style={styles.section}>Layer split</Text>
                                <Text style={styles.result}>{shardPlan}</Text>
                                {/* The sharded model replaces this phone's own loaded model, so it
                                    is already what every chat uses — but nothing said so, and the
                                    "Run on mesh" box above is a different mode entirely (it asks
                                    ANOTHER phone's model to answer), which just times out here. */}
                                <Text style={[styles.dim, styles.gap]}>
                                    This is now this phone's model — open any chat and it runs across
                                    the phones above. "Run on mesh" is for something else: asking
                                    another phone's own model to answer.
                                </Text>
                                <ThemedButton
                                    label="Open chat"
                                    variant="secondary"
                                    onPress={() => router.push('/')}
                                    buttonStyle={styles.gap}
                                />
                            </View>
                        )}
                    </>
                )}
            </View>

            <HelixQrSheet
                visible={showQrSheet}
                setVisible={setShowQrSheet}
                hosting={hosting}
                hostStarting={hostStarting}
                hostIp={hostIp}
                hostPort={HOST_PORT}
                hostIpIsStale={hostIpIsStale}
                hostQrExtra={hostQrExtra}
                agentsJoined={hostAgents.length}
                onStartHosting={onStartHost}
                onRetryIp={detectAndSetHostIp}
                onScanned={(data) => {
                    const { base, hotspot } = parseScannedAddress(data)
                    setWsUrl(base)
                    // Scanning IS the connect action — asking for a second, separate tap on "Join
                    // as agent" after this is exactly the friction a QR code exists to remove.
                    if (agentJoined) onLeaveAgent()
                    void (async () => {
                        let target = base
                        if (hotspot) {
                            const wifiHotspot = wifiHotspotModule()
                            if (!wifiHotspot) {
                                Logger.errorToast(
                                    "This code needs a direct Wi-Fi hotspot, which isn't in this build"
                                )
                                return
                            }
                            // The two roles genuinely conflict: joining pins this whole process to
                            // the joined network, which is precisely what stops a coordinator of
                            // our own from being reachable. Better to say so than to leave a host
                            // silently serving into nowhere.
                            if (hosting) {
                                Logger.warnToast(
                                    'This phone is hosting — joining another phone will make its own ' +
                                        'mesh unreachable. Stop hosting first.'
                                )
                            }
                            Logger.infoToast("Joining the host's Wi-Fi hotspot…")
                            try {
                                if (
                                    !(await wifiHotspot.joinHotspot(
                                        hotspot.ssid,
                                        hotspot.passphrase,
                                        hotspot.security
                                    ))
                                ) {
                                    Logger.errorToast("Could not join the host's Wi-Fi hotspot")
                                    return
                                }
                                joinedHotspotRef.current = true
                                liveAgentJoinedHotspot = true
                                // Trust the network over the QR code. The gateway of a hotspot IS
                                // the phone serving it, read from the DHCP lease we just took,
                                // whereas the address in the code is only what the host BELIEVED
                                // its own address to be — and a host that gets that wrong produces
                                // a code that joins the Wi-Fi fine and then fails every dial.
                                const gateway = wifiHotspot.getJoinedNetworkGateway?.() ?? ''
                                if (gateway) {
                                    target = withHost(base, gateway)
                                    if (target !== base) {
                                        setWsUrl(target)
                                        Logger.info(
                                            `Host advertised ${base}, but the hotspot gateway is ${gateway} — using the gateway`
                                        )
                                    }
                                }
                            } catch (e) {
                                Logger.errorToast(
                                    `Could not join the host's Wi-Fi hotspot: ${e instanceof Error ? e.message : String(e)}`
                                )
                                return
                            }
                        }
                        await onJoinAgent(target)
                    })()
                }}
            />

            <Text style={styles.help}>
                No PC: one phone taps "Start hosting", the other loads a model — either scans the
                host's QR (tap the QR icon, top right) or types its IP:{HOST_PORT} into "Join as
                agent". Both on the same Wi-Fi.{'\n\n'}
                With a PC in the same Wi-Fi: {'\n'}
                • L1 (drive the mesh): python -m helix.host.http_control --host 0.0.0.0{'\n'}
                • L2 (use this phone as an agent): python -m helix.host.agent_host_ws_demo --host 0.0.0.0
            </Text>
        </ScrollView>
    )
}

export default HelixMeshScreen

const useStyles = () => {
    const { color, spacing, fontSize } = Theme.useTheme()
    return StyleSheet.create({
        container: { flex: 1 },
        content: { paddingHorizontal: spacing.xl2, paddingBottom: spacing.xl2 },
        gap: { marginTop: spacing.l },
        row: { flexDirection: 'row', columnGap: spacing.m },
        flex: { flex: 1 },
        section: { color: color.text._100, fontSize: fontSize.l, marginBottom: spacing.s },
        node: { color: color.text._300, marginLeft: spacing.s },
        dim: { color: color.text._500 },
        resultBox: {
            marginTop: spacing.l,
            padding: spacing.l,
            borderRadius: spacing.m,
            borderWidth: 1,
            borderColor: color.primary._300,
        },
        result: { color: color.text._100 },
        agentBox: {
            marginTop: spacing.xl2,
            paddingTop: spacing.l,
            borderTopWidth: 1,
            borderTopColor: color.neutral._300,
        },
        help: { color: color.text._500, marginTop: spacing.xl2, fontSize: fontSize.s },
        deviceBanner: {
            flexDirection: 'row',
            alignItems: 'center',
            justifyContent: 'center',
            paddingVertical: spacing.m,
            borderRadius: spacing.m,
            backgroundColor: color.primary._100,
        },
        deviceBannerCount: { color: color.primary._800, fontSize: fontSize.xl2, fontWeight: 'bold' },
        deviceBannerLabel: { color: color.primary._700, fontSize: fontSize.m, marginLeft: spacing.s },
    })
}
