// A test chat for one mesh mode.
//
// Deliberately not the app's real chat: no character, no history, no persistence. It exists to
// answer one question — does THIS mode actually produce an answer right now — without the round
// trip through a character and a saved conversation. Pointer and Sharder each get one, wired to
// their own path (an agent's whole model versus this phone's split one); Hybrid gets none, since
// it is a placeholder with nothing behind it yet.

import { AntDesign } from '@expo/vector-icons'
import { getDocumentAsync } from 'expo-document-picker'
import { useState } from 'react'
import { ActivityIndicator, StyleSheet, Text, TouchableOpacity, View } from 'react-native'

import ThemedButton from '@components/buttons/ThemedButton'
import ThemedTextInput from '@components/input/ThemedTextInput'
import { Logger } from '@lib/state/Logger'
import { Theme } from '@lib/theme/ThemeManager'

// Whole-file text is pasted in front of the prompt, so it competes with the model's context
// window. Past this it is refused outright rather than silently truncated into nonsense.
const MAX_ATTACH_BYTES = 128 * 1024

interface Attachment {
    name: string
    text: string
}

export interface MeshChatPanelProps {
    title: string
    hint: string
    /** Blocks sending, with the reason shown in place of the input. */
    disabledReason?: string
    /**
     * Runs one exchange. `onToken` is for paths that stream; those that return a single answer can
     * ignore it and just resolve with the text.
     */
    onSend: (prompt: string, onToken: (chunk: string) => void) => Promise<string>
}

const MeshChatPanel: React.FC<MeshChatPanelProps> = ({ title, hint, disabledReason, onSend }) => {
    const styles = useStyles()
    const { color } = Theme.useTheme()
    const [prompt, setPrompt] = useState('')
    const [attachment, setAttachment] = useState<Attachment | null>(null)
    const [running, setRunning] = useState(false)
    const [answer, setAnswer] = useState('')

    const onAttach = async () => {
        try {
            const result = await getDocumentAsync({ copyToCacheDirectory: true })
            if (result.canceled) return
            const file = result.assets[0]
            if (!file) return
            if ((file.size ?? 0) > MAX_ATTACH_BYTES) {
                Logger.errorToast(
                    `${file.name} is too big to paste into a prompt (over ${MAX_ATTACH_BYTES / 1024} KB)`
                )
                return
            }
            // eslint-disable-next-line @typescript-eslint/no-var-requires
            const fs = require('expo-file-system/legacy')
            const text = await fs.readAsStringAsync(file.uri, { encoding: 'utf8' })
            setAttachment({ name: file.name, text })
        } catch (e) {
            // Anything not decodable as text lands here — a PDF or an image has no meaning as
            // prompt text, and saying so beats attaching mojibake.
            Logger.errorToast(
                `Could not read that as text: ${e instanceof Error ? e.message : String(e)}`
            )
        }
    }

    const onRun = async () => {
        if (!prompt.trim() && !attachment) {
            Logger.errorToast('Type something, or attach a file')
            return
        }
        setRunning(true)
        setAnswer('')
        try {
            const full = attachment
                ? `The following is the contents of ${attachment.name}:\n\n${attachment.text}\n\n${prompt}`
                : prompt
            let streamed = ''
            const final = await onSend(full, (chunk) => {
                streamed += chunk
                setAnswer(streamed)
            })
            // A streaming path has already filled this in; a single-answer one has not.
            if (final) setAnswer(final)
        } catch (e) {
            Logger.errorToast(`${title} failed: ${e instanceof Error ? e.message : String(e)}`)
        } finally {
            setRunning(false)
        }
    }

    return (
        <View style={styles.box}>
            <Text style={styles.section}>{title}</Text>
            <Text style={styles.dim}>{hint}</Text>

            {disabledReason ? (
                <Text style={[styles.dim, styles.gap]}>{disabledReason}</Text>
            ) : (
                <>
                    <ThemedTextInput
                        label="Prompt"
                        value={prompt}
                        onChangeText={setPrompt}
                        placeholder="Ask something…"
                        multiline
                        numberOfLines={3}
                        containerStyle={styles.gap}
                    />

                    <View style={styles.row}>
                        <TouchableOpacity onPress={onAttach} hitSlop={8} style={styles.attach}>
                            <AntDesign name="paper-clip" size={16} color={color.text._300} />
                            <Text style={styles.attachText}>
                                {attachment ? attachment.name : 'Attach a text file'}
                            </Text>
                        </TouchableOpacity>
                        {!!attachment && (
                            <TouchableOpacity onPress={() => setAttachment(null)} hitSlop={8}>
                                <AntDesign name="close" size={16} color={color.text._400} />
                            </TouchableOpacity>
                        )}
                    </View>
                    {!!attachment && (
                        <Text style={styles.dim}>
                            {`${(attachment.text.length / 1024).toFixed(1)} KB of text goes in front of the prompt`}
                        </Text>
                    )}

                    <ThemedButton
                        label={running ? 'Running…' : 'Send'}
                        variant="primary"
                        onPress={onRun}
                        buttonStyle={styles.gap}
                    />
                    {running && <ActivityIndicator color={color.text._100} style={styles.gap} />}
                    {!!answer && (
                        <View style={styles.answerBox}>
                            <Text style={styles.answer}>{answer}</Text>
                        </View>
                    )}
                </>
            )}
        </View>
    )
}

export default MeshChatPanel

const useStyles = () => {
    const { color, spacing, fontSize } = Theme.useTheme()
    return StyleSheet.create({
        box: {
            marginTop: spacing.xl2,
            paddingTop: spacing.l,
            borderTopWidth: 1,
            borderTopColor: color.neutral._300,
        },
        section: { color: color.text._100, fontSize: fontSize.l, marginBottom: spacing.s },
        dim: { color: color.text._500 },
        gap: { marginTop: spacing.l },
        row: { flexDirection: 'row', alignItems: 'center', columnGap: spacing.m, marginTop: spacing.m },
        attach: { flexDirection: 'row', alignItems: 'center', columnGap: spacing.s, flexShrink: 1 },
        attachText: { color: color.text._300, flexShrink: 1 },
        answerBox: {
            marginTop: spacing.l,
            padding: spacing.l,
            borderRadius: spacing.m,
            borderWidth: 1,
            borderColor: color.primary._300,
        },
        answer: { color: color.text._100 },
    })
}
