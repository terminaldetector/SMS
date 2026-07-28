// ELIZA mode — a joke, and only a joke.
//
// Hidden behind eight taps on "Pointer". This phone loads a model, another phone on the mesh runs
// its own, and the two are left talking to each other while you watch. The framing is 1966: the
// remote side is asked to answer like a DOCTOR-style program, teletype and all.
//
// It is genuinely useless, which is the point. It does incidentally make one real thing visible —
// two models on two phones taking turns, unattended — so it doubles as the least formal possible
// soak test of the Pointer path.

import { useEffect, useRef, useState } from 'react'
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native'

import ThemedButton from '@components/buttons/ThemedButton'
import BottomSheet from '@components/views/BottomSheet'
import { Llama } from '@lib/engine/Local/LlamaLocal'
import { meshSession } from '@lib/helixSession'
import { Logger } from '@lib/state/Logger'
import { Theme } from '@lib/theme/ThemeManager'

// Long enough to read, short enough that the two do not race each other into nonsense.
const TURN_PAUSE_MS = 1200
const MAX_TURNS = 40

const ELIZA_PREAMBLE =
    'You are ELIZA, a Rogerian psychotherapist program written at MIT in 1966. Reply in ONE short ' +
    'line, in uppercase, as a teletype would. Reflect what the other says back as a question. Never ' +
    'explain that you are an AI.'

const PATIENT_PREAMBLE =
    'You are a person talking to ELIZA, a computer program, in 1966. Reply in ONE short line. You ' +
    'are curious and a little sceptical that a machine can understand you.'

interface Line {
    who: 'ELIZA' | 'YOU'
    text: string
}

interface ElizaSheetProps {
    visible: boolean
    setVisible: (v: boolean) => void
}

const ElizaSheet: React.FC<ElizaSheetProps> = ({ visible, setVisible }) => {
    const styles = useStyles()
    const { color } = Theme.useTheme()
    const [lines, setLines] = useState<Line[]>([])
    const [running, setRunning] = useState(false)
    // Read inside the loop rather than captured, so Stop takes effect on the current turn.
    const runningRef = useRef(false)

    useEffect(() => {
        if (!visible) {
            runningRef.current = false
            setRunning(false)
        }
    }, [visible])

    // This phone plays the patient with its own model; the mesh plays ELIZA with another phone's.
    const localTurn = async (prompt: string): Promise<string> => {
        const store = Llama.useLlamaModelStore.getState()
        if (!store.context) throw new Error('load a model on this phone first')
        let out = ''
        await store.completion(
            { prompt: `${PATIENT_PREAMBLE}\n\nELIZA: ${prompt}\nYOU:`, n_predict: 60 },
            (t: string) => {
                out += t
            },
            () => {}
        )
        return out.split('\n')[0].trim() || '…'
    }

    const remoteTurn = async (prompt: string): Promise<string> => {
        const coord = meshSession.coord
        if (!coord) throw new Error('not hosting a mesh')
        if (coord.agents().length === 0) throw new Error('no other phone has joined')
        const answer = await coord.infer(`${ELIZA_PREAMBLE}\n\nYOU: ${prompt}\nELIZA:`, 'single')
        return answer.split('\n')[0].trim().toUpperCase() || 'GO ON.'
    }

    const start = async () => {
        setLines([])
        runningRef.current = true
        setRunning(true)
        let said = 'Men are all alike.'
        setLines([{ who: 'YOU', text: said }])
        try {
            for (let turn = 0; turn < MAX_TURNS && runningRef.current; turn++) {
                const eliza = await remoteTurn(said)
                if (!runningRef.current) break
                setLines((l) => [...l, { who: 'ELIZA', text: eliza }])
                await new Promise((r) => setTimeout(r, TURN_PAUSE_MS))
                if (!runningRef.current) break

                said = await localTurn(eliza)
                if (!runningRef.current) break
                setLines((l) => [...l, { who: 'YOU', text: said }])
                await new Promise((r) => setTimeout(r, TURN_PAUSE_MS))
            }
        } catch (e) {
            Logger.errorToast(`ELIZA: ${e instanceof Error ? e.message : String(e)}`)
        } finally {
            runningRef.current = false
            setRunning(false)
        }
    }

    return (
        <BottomSheet
            visible={visible}
            setVisible={setVisible}
            sheetStyle={{ flex: 1, maxHeight: '80%', justifyContent: 'flex-start' }}>
            <Text style={styles.title}>ELIZA</Text>
            <Text style={styles.sub}>
                MIT, 1966. Your phone's model talks to another phone's, unattended. It does nothing
                useful — that is the entire idea.
            </Text>

            <ScrollView style={styles.log} contentContainerStyle={styles.logContent}>
                {lines.length === 0 && !running && (
                    <Text style={styles.dim}>— PRESS START —</Text>
                )}
                {lines.map((l, i) => (
                    <Text key={i} style={l.who === 'ELIZA' ? styles.eliza : styles.you}>
                        {l.who === 'ELIZA' ? `ELIZA: ${l.text}` : `   YOU: ${l.text}`}
                    </Text>
                ))}
                {running && <ActivityIndicator color={color.text._300} style={{ marginTop: 12 }} />}
            </ScrollView>

            <ThemedButton
                label={running ? 'Stop' : 'Start'}
                variant={running ? 'critical' : 'primary'}
                onPress={() => {
                    if (running) {
                        runningRef.current = false
                        setRunning(false)
                    } else void start()
                }}
            />
        </BottomSheet>
    )
}

export default ElizaSheet

const useStyles = () => {
    const { color, spacing, fontSize } = Theme.useTheme()
    return StyleSheet.create({
        title: {
            color: color.text._100,
            fontSize: fontSize.xl2,
            letterSpacing: 4,
            textAlign: 'center',
            marginBottom: spacing.s,
        },
        sub: { color: color.text._500, textAlign: 'center', marginBottom: spacing.l },
        log: { flex: 1, marginBottom: spacing.l },
        logContent: { paddingBottom: spacing.l },
        dim: { color: color.text._500, textAlign: 'center', marginTop: spacing.xl2 },
        // Monospace on purpose: the whole gag is a teletype.
        eliza: { color: color.primary._800, fontFamily: 'monospace', marginBottom: spacing.s },
        you: { color: color.text._300, fontFamily: 'monospace', marginBottom: spacing.s },
    })
}
