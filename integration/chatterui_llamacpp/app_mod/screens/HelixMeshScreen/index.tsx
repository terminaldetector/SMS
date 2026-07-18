// HELIX Mesh screen (ChatterUI Level 1 / mesh mod) — ChatterUI as a UI over a HELIX node.
//
// Connect to a HELIX HTTP control node (helix/host/http_control.py) on the LAN, list the mesh
// agents, and run a prompt across the mesh (single / parallel / voting, or a fused SuperAgent).
// Uses only fetch (via @lib/helixClient) — no native module. First-experiments UI.

import React, { useMemo, useState } from 'react'
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native'
import { useMMKVString } from 'react-native-mmkv'

import ThemedButton from '@components/buttons/ThemedButton'
import HorizontalSelector from '@components/input/HorizontalSelector'
import ThemedTextInput from '@components/input/ThemedTextInput'
import HeaderTitle from '@components/views/HeaderTitle'
import { HelixClient, InferMode, normalizeBaseUrl } from '@lib/helixClient'
import { Logger } from '@lib/state/Logger'
import { Theme } from '@lib/theme/ThemeManager'

const HOST_KEY = 'helix-mesh-host'

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

    const client = useMemo(() => {
        const base = normalizeBaseUrl(host ?? '')
        return base ? new HelixClient(base) : null
    }, [host])

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

            <Text style={styles.help}>
                Run a HELIX node on your PC/phone: {'\n'}
                python -m helix.host.http_control --host 0.0.0.0{'\n'}
                then enter its LAN IP above. Same mesh, driven from the app.
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
        help: { color: color.text._500, marginTop: spacing.xl2, fontSize: fontSize.s },
    })
}
