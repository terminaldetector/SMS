// BitChat bridge — lets a BitChat user on the BLE mesh talk to this phone's model.
//
// The protocol layers underneath are covered by tests; what this screen exists for is the part that
// can only be judged on a device: whether Bluetooth actually came up, who is reachable, and whether
// a session got encrypted. Hence the live log rather than a single "connected" light.

import React, { useEffect } from 'react'
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native'
import { useShallow } from 'zustand/react/shallow'

import ThemedButton from '@components/buttons/ThemedButton'
import HeaderTitle from '@components/views/HeaderTitle'
import { useBitchatStore } from '@lib/bitchatService'
import { Theme } from '@lib/theme/ThemeManager'

const BitchatBridgeScreen = () => {
    const styles = useStyles()
    const { color } = Theme.useTheme()

    const { running, starting, available, peripheralSupported, myPeerId, peers, log, start, stop, refresh } =
        useBitchatStore(
            useShallow((s) => ({
                running: s.running,
                starting: s.starting,
                available: s.available,
                peripheralSupported: s.peripheralSupported,
                myPeerId: s.myPeerId,
                peers: s.peers,
                log: s.log,
                start: s.start,
                stop: s.stop,
                refresh: s.refresh,
            }))
        )

    // Peer lists change without any event of their own (a link can go quiet), so poll while running.
    useEffect(() => {
        refresh()
        if (!running) return
        const t = setInterval(refresh, 2000)
        return () => clearInterval(t)
    }, [running, refresh])

    return (
        <ScrollView style={styles.container} contentContainerStyle={styles.content}>
            <HeaderTitle title="BitChat Bridge" />

            <Text style={styles.dim}>
                Answers BitChat users over Bluetooth with this phone's model. No Wi-Fi and no
                internet — the mesh is the radio. Load a model in Models first.
            </Text>

            {!available && (
                <Text style={[styles.warn, styles.gap]}>
                    Bluetooth LE isn't available in this build or on this device.
                </Text>
            )}
            {available && !peripheralSupported && (
                <Text style={[styles.warn, styles.gap]}>
                    This chipset can't advertise, so BitChat peers won't discover this phone — it can
                    only reach out to peers it finds first.
                </Text>
            )}

            <ThemedButton
                label={starting ? 'Starting…' : running ? 'Stop bridge' : 'Start bridge'}
                variant={running ? 'critical' : 'primary'}
                onPress={running ? stop : start}
                buttonStyle={styles.gap}
            />
            {starting && <ActivityIndicator color={color.text._100} style={styles.gap} />}

            {running && !!myPeerId && (
                <Text style={[styles.node, styles.gap]}>● this phone is {myPeerId}</Text>
            )}

            <View style={styles.box}>
                <Text style={styles.section}>Peers ({peers.length})</Text>
                {peers.length === 0 ? (
                    <Text style={styles.dim}>
                        {running ? 'scanning — nobody in range yet…' : 'not running'}
                    </Text>
                ) : (
                    peers.map((p) => (
                        <Text key={p.link} style={styles.node}>
                            {p.encrypted ? '🔒' : '•'} {p.peerId || p.link}
                            {p.encrypted ? ' — encrypted session' : ' — connected, no session yet'}
                        </Text>
                    ))
                )}
            </View>

            <View style={styles.box}>
                <Text style={styles.section}>Activity</Text>
                {log.length === 0 ? (
                    <Text style={styles.dim}>nothing yet</Text>
                ) : (
                    log.map((line, i) => (
                        <Text key={`${i}-${line}`} style={styles.logLine}>
                            {line}
                        </Text>
                    ))
                )}
            </View>

            <Text style={styles.help}>
                A BitChat user starts a private chat with this phone; the message becomes a prompt
                and the reply comes back over the same encrypted session. Bluetooth permissions are
                requested on start — on Android 12+ these are Nearby devices, not location.
            </Text>
        </ScrollView>
    )
}

export default BitchatBridgeScreen

const useStyles = () => {
    const { color, spacing, fontSize } = Theme.useTheme()
    return StyleSheet.create({
        container: { flex: 1 },
        content: { paddingHorizontal: spacing.xl2, paddingBottom: spacing.xl2 },
        gap: { marginTop: spacing.l },
        section: { color: color.text._100, fontSize: fontSize.l, marginBottom: spacing.s },
        node: { color: color.text._300, marginLeft: spacing.s },
        dim: { color: color.text._500 },
        warn: { color: color.error._500 },
        logLine: { color: color.text._400, fontSize: fontSize.s, marginLeft: spacing.s },
        box: {
            marginTop: spacing.xl2,
            paddingTop: spacing.l,
            borderTopWidth: 1,
            borderTopColor: color.neutral._300,
        },
        help: { color: color.text._500, marginTop: spacing.xl2, fontSize: fontSize.s },
    })
}
