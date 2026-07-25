// HELIX Mesh screen (ChatterUI mesh mod) — three roles:
//   L1 (client):  a UI over a HELIX node (helix/host/http_control.py) over fetch.
//   L2 (agent):   this phone's loaded GGUF model JOINS the mesh as a Track-A agent over WebSocket
//                 (no native module: built-in WebSocket + @noble + expo-crypto nonce).
//   Device-to-device (no PC): this phone HOSTS the coordinator (helixCoordinator.ts) so another
//                 ChatterUI phone joins it directly with "Join as agent" — no PC in the loop.
// First-experiments UI.

import { AntDesign } from '@expo/vector-icons'
import * as ExpoCrypto from 'expo-crypto'
import React, { useEffect, useMemo, useRef, useState } from 'react'
import { ActivityIndicator, ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native'
import { useMMKVString } from 'react-native-mmkv'

import ThemedButton from '@components/buttons/ThemedButton'
import HorizontalSelector from '@components/input/HorizontalSelector'
import ThemedTextInput from '@components/input/ThemedTextInput'
import HeaderButton from '@components/views/HeaderButton'
import HeaderTitle from '@components/views/HeaderTitle'
import HelixQrSheet from '@components/views/HelixQrSheet'
import { HelixAgentNode, makeExpoRandomBytes, makeLlamaAgentRunner } from '@lib/helixAgent'
import { HelixClient, InferMode, normalizeBaseUrl } from '@lib/helixClient'
import { HelixCoordinator } from '@lib/helixCoordinator'
import { Llama } from '@lib/engine/Local/LlamaLocal'
import { Logger } from '@lib/state/Logger'
import { mmkv } from '@lib/storage/MMKV'
import { Theme } from '@lib/theme/ThemeManager'

const HOST_KEY = 'helix-mesh-host'
const WS_KEY = 'helix-agent-ws'
const LAST_HOST_IP_KEY = 'helix-mesh-last-host-ip'
// Cluster secret — the phone-to-phone coordinator and the "Join as agent" side share this, and it
// also matches the PC demo (helix/host/agent_host_ws_demo.py).
const AGENT_SECRET = 'helix-agent-host-ws-demo'
const HOST_PORT = 8790
type HostMode = 'single' | 'voting'

// Best-effort LAN IP for the host phone (so the QR/address the other phone needs is right there,
// no hunting through Settings). expo-network is an Expo module (New-Architecture-safe); required
// lazily so it never touches startup. One retry: right as the server starts, expo-network can read
// a stale/empty value for a moment on some devices.
async function getLanIp(): Promise<string> {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const Net = require('expo-network')
    const read = async () => {
        try {
            const ip = await Net.getIpAddressAsync()
            return typeof ip === 'string' && ip !== '0.0.0.0' ? ip : ''
        } catch {
            return ''
        }
    }
    const first = await read()
    if (first) return first
    await new Promise((r) => setTimeout(r, 400))
    return read()
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
    const [agentJoined, setAgentJoined] = useState(false)
    const [agentJoining, setAgentJoining] = useState(false)
    // Distinct from agentJoined: the session stays joined across a Wi-Fi drop while the node
    // reconnects, and the UI should say so rather than keep claiming "online".
    const [agentOnline, setAgentOnline] = useState(false)
    const [showQrSheet, setShowQrSheet] = useState(false)
    const agentRef = useRef<HelixAgentNode | null>(null)

    // Device-to-device (no PC): this phone hosts the coordinator.
    const [hosting, setHosting] = useState(false)
    const [hostStarting, setHostStarting] = useState(false)
    const [hostIp, setHostIp] = useState('')
    // True when hostIp came from a saved previous session, not this session's own detection — a
    // phone's LAN IP is usually stable on the same Wi-Fi, but "usually" isn't "definitely", so the
    // QR still shows (nothing to hunt for) with a note that it may be stale.
    const [hostIpIsStale, setHostIpIsStale] = useState(false)
    const [hostAgents, setHostAgents] = useState<string[]>([])
    const [hostPrompt, setHostPrompt] = useState('')
    const [hostMode, setHostMode] = useState<HostMode>('single')
    const [hostRunning, setHostRunning] = useState(false)
    const [hostResult, setHostResult] = useState('')
    const coordRef = useRef<HelixCoordinator | null>(null)
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

    const onJoinAgent = async () => {
        const url = normalizeWs(wsUrl ?? '')
        if (!url) {
            Logger.errorToast('Enter the coordinator address (e.g. 192.168.1.10:8790)')
            return
        }
        const store = Llama.useLlamaModelStore.getState()
        if (!store.context) {
            Logger.errorToast('Load a model in ChatterUI first (Models), then join')
            return
        }
        setAgentJoining(true)
        try {
            const runner = makeLlamaAgentRunner(store, {
                agent_id: agentId,
                skills: ['chat'],
                task_types: ['chat'],
                models: ['local'],
            })
            const node = new HelixAgentNode(agentId, AGENT_SECRET, runner, {
                randomBytes: makeExpoRandomBytes(ExpoCrypto),
            })
            node.onStateChange = setAgentOnline
            await node.connect(url)
            agentRef.current = node
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
        setAgentJoined(false)
        setAgentOnline(false)
        Logger.infoToast('Left the mesh')
    }

    // Leaving the screen shouldn't strand a live agent connection.
    useEffect(() => {
        return () => agentRef.current?.close()
    }, [])

    // Poll the coordinator's joined agents while hosting, and tear the server down on unmount.
    useEffect(() => {
        if (!hosting) return
        const t = setInterval(() => setHostAgents(coordRef.current?.agents() ?? []), 1000)
        return () => clearInterval(t)
    }, [hosting])
    useEffect(() => {
        return () => coordRef.current?.close()
    }, [])

    // Shared by the initial detect (onStartHost) and the manual "Retry" in the QR sheet.
    const detectAndSetHostIp = async () => {
        const detected = await getLanIp()
        if (detected) {
            setHostIp(detected)
            setHostIpIsStale(false)
            mmkv.set(LAST_HOST_IP_KEY, detected)
        } else {
            // Detection can fail (permissions, timing, OEM quirks) even though the phone's IP
            // hasn't actually changed since last time — showing last session's is still far
            // better than sending the user to hunt for it in Settings.
            const last = mmkv.getString(LAST_HOST_IP_KEY) ?? ''
            setHostIp(last)
            setHostIpIsStale(!!last)
        }
    }

    const onStartHost = async () => {
        setHostStarting(true)
        try {
            const coord = new HelixCoordinator(`host-${agentId}`, AGENT_SECRET, {
                randomBytes: makeExpoRandomBytes(ExpoCrypto),
            })
            await coord.listen(HOST_PORT, '0.0.0.0')
            coordRef.current = coord
            setHosting(true)
            setHostAgents([])
            await detectAndSetHostIp()
            Logger.infoToast(`Hosting on :${HOST_PORT} — other phone joins this device`)
        } catch (e) {
            coordRef.current?.close()
            coordRef.current = null
            Logger.errorToast(`Host start failed: ${e instanceof Error ? e.message : String(e)}`)
        } finally {
            setHostStarting(false)
        }
    }

    const onStopHost = () => {
        coordRef.current?.close()
        coordRef.current = null
        setHosting(false)
        setHostAgents([])
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
            Logger.errorToast('No agent phone has joined yet')
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
                    This phone becomes the coordinator. The other ChatterUI phone loads a model and
                    uses "Join as agent" below, pointed at this phone's address. No PC needed.
                </Text>
                <ThemedButton
                    label={hostStarting ? 'Starting…' : hosting ? 'Stop hosting' : 'Start hosting'}
                    variant={hosting ? 'critical' : 'primary'}
                    onPress={hosting ? onStopHost : onStartHost}
                    buttonStyle={styles.gap}
                />
                {hosting && (
                    <View style={styles.gap}>
                        <Text style={styles.node}>
                            ● hosting on port {HOST_PORT}
                            {hostIp ? ` — other phone joins ${hostIp}:${HOST_PORT}` : ''}
                        </Text>
                        <TouchableOpacity onPress={() => setShowQrSheet(true)}>
                            <Text style={[styles.dim, styles.gap]}>
                                Tap the QR icon (top right) to show the connect code
                            </Text>
                        </TouchableOpacity>
                        <Text style={[styles.node, styles.gap]}>
                            Agents joined ({hostAgents.length})
                        </Text>
                        {hostAgents.length === 0 ? (
                            <Text style={styles.dim}>waiting for the other phone to join…</Text>
                        ) : (
                            hostAgents.map((a) => (
                                <Text key={a} style={styles.node}>
                                    • {a}
                                </Text>
                            ))
                        )}

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
                    Share your loaded model with a mesh coordinator over WebSocket. Load a model in
                    Models first, then tap the QR icon (top right) → Scan, or type its address below.
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
                    onPress={agentJoined ? onLeaveAgent : onJoinAgent}
                    buttonStyle={styles.gap}
                />
                {agentJoined && (
                    <Text style={styles.node}>
                        {agentOnline
                            ? `● online as ${agentId} — answering mesh tasks`
                            : `◌ ${agentId} — connection lost, reconnecting…`}
                    </Text>
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
                agentsJoined={hostAgents.length}
                onStartHosting={onStartHost}
                onRetryIp={detectAndSetHostIp}
                onScanned={(data) => {
                    setWsUrl(data)
                    Logger.infoToast('Scanned host address')
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
    })
}
