// HELIX Mesh screen (ChatterUI mesh mod) — two roles:
//   L1 (client): a UI over a HELIX node (helix/host/http_control.py) over fetch.
//   L2 (agent):  this phone's loaded GGUF model JOINS the mesh as a Track-A agent over WebSocket
//                (no native module: built-in WebSocket + @noble + expo-crypto nonce).
// First-experiments UI.

import * as ExpoCrypto from 'expo-crypto'
import React, { useMemo, useRef, useState } from 'react'
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native'
import { useMMKVString } from 'react-native-mmkv'

import ThemedButton from '@components/buttons/ThemedButton'
import HorizontalSelector from '@components/input/HorizontalSelector'
import ThemedTextInput from '@components/input/ThemedTextInput'
import HeaderTitle from '@components/views/HeaderTitle'
import { HelixAgentNode, makeExpoRandomBytes, makeLlamaAgentRunner } from '@lib/helixAgent'
import { HelixClient, InferMode, normalizeBaseUrl } from '@lib/helixClient'
import { Llama } from '@lib/engine/Local/LlamaLocal'
import { Logger } from '@lib/state/Logger'
import { mmkv } from '@lib/storage/MMKV'
import { Theme } from '@lib/theme/ThemeManager'

const HOST_KEY = 'helix-mesh-host'
const WS_KEY = 'helix-agent-ws'
// Cluster secret — must match the coordinator (helix/host/agent_host_ws_demo.py).
const AGENT_SECRET = 'helix-agent-host-ws-demo'

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
    const agentRef = useRef<HelixAgentNode | null>(null)
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
            await node.connect(url)
            agentRef.current = node
            setAgentJoined(true)
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
        Logger.infoToast('Left the mesh')
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
                <Text style={styles.section}>Join as agent (this phone's model)</Text>
                <Text style={styles.dim}>
                    Share your loaded model with a mesh coordinator over WebSocket. Load a model in
                    Models first.
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
                    <Text style={styles.node}>● online as {agentId} — answering mesh tasks</Text>
                )}
            </View>

            <Text style={styles.help}>
                On a PC in the same Wi-Fi: {'\n'}
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
