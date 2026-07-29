// A real chat window for one mesh mode.
//
// This replaces the single-shot test panels. Those could only tell you that something came back,
// and the question worth asking about a mesh is whether a CONVERSATION survives it — whether turn
// four still knows what turn one said, over a link that has already been the source of every hard
// bug in this project.
//
// It is deliberately the same shape as the app's own chat: swipe left-to-right for settings, swipe
// right-to-left for the list of context branches, same as ChatScreen. The gestures are not copied
// for the sake of familiarity — they are the ones already in muscle memory, and a test tool nobody
// can drive is not a test tool.
//
// Not the app's real chat, though, and it should not become it: no character, no persona, no
// database. History here is a plain list of turns assembled into one prompt, which is exactly what
// makes it useful for finding out where context is being lost — there is nothing else in the way.

import { AntDesign } from '@expo/vector-icons'
import { getDocumentAsync } from 'expo-document-picker'
import { useLocalSearchParams } from 'expo-router'
import { useEffect, useRef, useState } from 'react'
import {
    ActivityIndicator,
    Image,
    Keyboard,
    ScrollView,
    StyleSheet,
    Text,
    TextInput,
    TouchableOpacity,
    View,
} from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'

import Drawer from '@components/views/Drawer'
import HeaderButton from '@components/views/HeaderButton'
import HeaderTitle from '@components/views/HeaderTitle'
import SettingsDrawer from '@components/views/SettingsDrawer'
import { Llama } from '@lib/engine/Local/LlamaLocal'
import { activeOf, buildBranchPrompt, MeshTurn, useHelixChat } from '@lib/helixChat'
import { meshRunBlocker, runMeshTurn } from '@lib/helixChatRun'
import { buildBranchPromptExact } from '@lib/helixChatTokens'
import { MultimodalStatus, probeMultimodal } from '@lib/helixMultimodal'
import { MeshMode } from '@lib/helixSession'
import { helixMeshContext } from '@lib/helixSettings'
import { Logger } from '@lib/state/Logger'
import { Theme } from '@lib/theme/ThemeManager'

import BranchDrawer from './BranchDrawer'

// Whole-file text goes in front of the prompt, so it competes with the context window rather than
// living outside it. Past this it is refused rather than silently truncated into nonsense.
const MAX_ATTACH_BYTES = 128 * 1024

const isMode = (v: unknown): v is MeshMode => v === 'pointer' || v === 'sharder' || v === 'hybrid'

const MeshChatScreen = () => {
    const styles = useStyles()
    const { color, spacing } = Theme.useTheme()
    const params = useLocalSearchParams<{ mode?: string }>()
    const mode: MeshMode = isMode(params.mode) ? params.mode : 'pointer'

    // Subscribed to the raw map — one stable reference — then derived from with pure helpers.
    // Selecting the branch directly would rebuild it on every comparison and never settle.
    const chats = useHelixChat((s) => s.chats)
    const addTurn = useHelixChat((s) => s.addTurn)
    const updateLastAssistant = useHelixChat((s) => s.updateLastAssistant)
    const forkBranch = useHelixChat((s) => s.forkBranch)
    const branch = activeOf(chats, mode)

    const [input, setInput] = useState('')
    const [attachment, setAttachment] = useState<{ name: string; text: string } | null>(null)
    const [images, setImages] = useState<string[]>([])
    const [running, setRunning] = useState(false)
    const [multimodal, setMultimodal] = useState<MultimodalStatus | null>(null)
    const scrollRef = useRef<ScrollView>(null)
    const setShow = Drawer.useDrawerStore((state) => state.setShow)

    const blocker = meshRunBlocker(mode)
    const contextBudget =
        mode === 'pointer'
            ? helixMeshContext()
            : Llama.useLlamaPreferencesStore.getState().config.context_length

    // Live, because loading or unloading a projector is something done between two messages.
    useEffect(() => {
        let cancelled = false
        const llama = Llama.useLlamaModelStore.getState()
        probeMultimodal(mode, llama.context, !!llama.mmproj).then((s) => {
            if (!cancelled) setMultimodal(s)
        })
        return () => {
            cancelled = true
        }
    }, [mode, running])

    const onAttachFile = async () => {
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

            const fs = require('expo-file-system/legacy')
            const text = await fs.readAsStringAsync(file.uri, { encoding: 'utf8' })
            setAttachment({ name: file.name, text })
        } catch (e) {
            // A PDF or an image has no meaning as prompt text; saying so beats attaching mojibake.
            Logger.errorToast(
                `Could not read that as text: ${e instanceof Error ? e.message : String(e)}`
            )
        }
    }

    const onAttachImage = async () => {
        if (!multimodal?.canAttach) {
            Logger.warnToast(multimodal?.reason ?? 'Images are not available here')
            return
        }
        const result = await getDocumentAsync({ type: 'image/*', copyToCacheDirectory: true })
        if (result.canceled) return
        const file = result.assets[0]
        if (file) setImages((prev) => [...prev, file.uri])
    }

    const onSend = async () => {
        if (running) return
        if (!input.trim() && !attachment && !images.length) {
            Logger.errorToast('Type something first')
            return
        }
        if (blocker) {
            Logger.errorToast(blocker)
            return
        }
        Keyboard.dismiss()

        const text = attachment
            ? `The following is the contents of ${attachment.name}:\n\n${attachment.text}\n\n${input}`
            : input
        const sentImages = images

        // The prompt is built from history plus this turn BEFORE anything is stored, so it never
        // depends on reading state back out mid-update.
        //
        // Sharder measures against the real tokenizer rather than guessing from character count —
        // it has one loaded, since the split model IS this phone's own context. The character
        // estimate stays for Pointer, where the answering phone's tokenizer is genuinely unknown;
        // using it for Sharder too was the cause of "Context is full" failures on Cyrillic-heavy
        // chats, where 4 chars/token undercounts by a wide margin and the trim keeps too much.
        const userTurn: MeshTurn = { role: 'user', text, images: sentImages, at: Date.now() }
        const allTurns = [...branch.turns, userTurn]
        const built =
            mode === 'sharder'
                ? await buildBranchPromptExact(allTurns, contextBudget)
                : buildBranchPrompt(allTurns, contextBudget)

        addTurn(mode, userTurn)
        addTurn(mode, { role: 'assistant', text: '', at: Date.now() })
        setInput('')
        setAttachment(null)
        setImages([])
        setRunning(true)

        try {
            if (built.dropped > 0)
                Logger.warnToast(
                    `${built.dropped} earlier turns did not fit in ${contextBudget} tokens and were left out`
                )

            let streamed = ''
            const answer = await runMeshTurn(mode, built.prompt, {
                images: sentImages,
                onToken: (chunk) => {
                    streamed += chunk
                    updateLastAssistant(mode, streamed)
                },
            })
            updateLastAssistant(mode, answer.trim() || '(empty answer)')
        } catch (e) {
            const message = e instanceof Error ? e.message : String(e)
            // The failure is written into the transcript rather than only toasted: in a branch you
            // will read again later, a turn that silently stayed empty is indistinguishable from a
            // model that had nothing to say.
            updateLastAssistant(mode, `⚠ ${message}`)
            Logger.errorToast(`Mesh chat: ${message}`)
        } finally {
            setRunning(false)
        }
    }

    const onFork = (index: number) => {
        forkBranch(mode, index)
        Logger.info('Forked into a new branch from this turn')
    }

    return (
        <Drawer.Gesture
            config={[
                { drawerID: Drawer.ID.MESHBRANCH, openDirection: 'left', closeDirection: 'right' },
                { drawerID: Drawer.ID.SETTINGS, openDirection: 'right', closeDirection: 'left' },
            ]}>
            <SafeAreaView style={styles.root} edges={['bottom']}>
                <HeaderTitle
                    title={`${mode === 'pointer' ? 'Pointer' : 'Sharder'} · ${branch.name}`}
                />
                <HeaderButton
                    headerLeft={() => <Drawer.Button drawerID={Drawer.ID.SETTINGS} />}
                    headerRight={() => (
                        <TouchableOpacity
                            hitSlop={12}
                            onPress={() => setShow(Drawer.ID.MESHBRANCH, true)}>
                            <AntDesign name="bars" size={24} color={color.text._100} />
                        </TouchableOpacity>
                    )}
                />

                <ScrollView
                    ref={scrollRef}
                    style={styles.log}
                    contentContainerStyle={styles.logContent}
                    onContentSizeChange={() => scrollRef.current?.scrollToEnd({ animated: true })}>
                    {branch.turns.length === 0 && (
                        <View style={styles.empty}>
                            <Text style={styles.dim}>
                                {mode === 'pointer'
                                    ? 'Whatever you send goes to a joined phone and comes back as its model’s answer. Ask a couple of things in a row to see whether it remembers.'
                                    : 'This runs on the model split across the mesh. Ask a couple of things in a row to see whether the split context holds.'}
                            </Text>
                            <Text style={[styles.dim, { marginTop: spacing.l }]}>
                                Swipe right for branches, left for settings.
                            </Text>
                        </View>
                    )}

                    {branch.turns.map((turn: MeshTurn, i: number) => (
                        <View
                            key={i}
                            style={turn.role === 'user' ? styles.userTurn : styles.botTurn}>
                            <View style={styles.turnHeader}>
                                <Text style={styles.role}>
                                    {turn.role === 'user'
                                        ? 'You'
                                        : mode === 'pointer'
                                          ? 'Agent'
                                          : 'Shard'}
                                </Text>
                                <TouchableOpacity onPress={() => onFork(i)} hitSlop={10}>
                                    <Text style={styles.fork}>fork here</Text>
                                </TouchableOpacity>
                            </View>
                            {!!turn.images?.length && (
                                <View style={styles.imageRow}>
                                    {turn.images.map((uri, n) => (
                                        <Image key={n} source={{ uri }} style={styles.thumb} />
                                    ))}
                                </View>
                            )}
                            <Text style={styles.turnText}>
                                {turn.text || (running && i === branch.turns.length - 1 ? '…' : '')}
                            </Text>
                        </View>
                    ))}
                    {running && <ActivityIndicator color={color.text._300} style={styles.gap} />}
                </ScrollView>

                {!!blocker && <Text style={styles.blocker}>{blocker}</Text>}

                <View style={styles.inputBar}>
                    <View style={styles.attachRow}>
                        <TouchableOpacity
                            onPress={onAttachFile}
                            hitSlop={8}
                            style={styles.attachBtn}>
                            <AntDesign name="paper-clip" size={16} color={color.text._400} />
                            <Text style={styles.attachText} numberOfLines={1}>
                                {attachment ? attachment.name : 'text file'}
                            </Text>
                        </TouchableOpacity>

                        <TouchableOpacity
                            onPress={onAttachImage}
                            hitSlop={8}
                            style={styles.attachBtn}>
                            <AntDesign
                                name="picture"
                                size={16}
                                color={multimodal?.canAttach ? color.text._400 : color.text._700}
                            />
                            <Text
                                style={[
                                    styles.attachText,
                                    !multimodal?.canAttach && { color: color.text._700 },
                                ]}
                                numberOfLines={1}>
                                {images.length ? `${images.length} image(s)` : 'image'}
                            </Text>
                        </TouchableOpacity>

                        {(!!attachment || !!images.length) && (
                            <TouchableOpacity
                                hitSlop={8}
                                onPress={() => {
                                    setAttachment(null)
                                    setImages([])
                                }}>
                                <AntDesign name="close" size={16} color={color.text._400} />
                            </TouchableOpacity>
                        )}
                    </View>

                    {/* The capability is stated, always — a picture button that does nothing when
                        tapped is how "multimodal" turns into a support question. */}
                    {!!multimodal && !multimodal.canAttach && (
                        <Text style={styles.capability}>{multimodal.reason}</Text>
                    )}

                    <View style={styles.sendRow}>
                        <TextInput
                            style={styles.input}
                            value={input}
                            onChangeText={setInput}
                            placeholder="Ask something…"
                            placeholderTextColor={color.text._700}
                            multiline
                        />
                        <TouchableOpacity onPress={onSend} disabled={running} hitSlop={10}>
                            <AntDesign
                                name={running ? 'loading' : 'arrow-up'}
                                size={22}
                                color={running ? color.text._700 : color.primary._700}
                            />
                        </TouchableOpacity>
                    </View>

                    <Text style={styles.budget}>
                        {`${branch.turns.length} turns · budget ${contextBudget} tokens${
                            mode === 'pointer'
                                ? ' (set in Settings — the agent’s window is not announced)'
                                : ''
                        }`}
                    </Text>
                </View>

                <SettingsDrawer />
                <BranchDrawer mode={mode} />
            </SafeAreaView>
        </Drawer.Gesture>
    )
}

export default MeshChatScreen

const useStyles = () => {
    const { color, spacing, fontSize } = Theme.useTheme()
    return StyleSheet.create({
        root: { flex: 1, backgroundColor: color.neutral._100 },
        log: { flex: 1 },
        logContent: { padding: spacing.l, paddingBottom: spacing.xl2 },
        empty: { paddingTop: spacing.xl3, paddingHorizontal: spacing.l },
        dim: { color: color.text._500, textAlign: 'center' },
        gap: { marginTop: spacing.l },
        userTurn: {
            marginBottom: spacing.l,
            padding: spacing.m,
            borderRadius: spacing.m,
            backgroundColor: color.neutral._200,
        },
        botTurn: {
            marginBottom: spacing.l,
            padding: spacing.m,
            borderRadius: spacing.m,
            borderWidth: 1,
            borderColor: color.primary._300,
        },
        turnHeader: {
            flexDirection: 'row',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: spacing.s,
        },
        role: { color: color.text._400, fontSize: fontSize.s },
        fork: { color: color.text._600, fontSize: fontSize.s },
        turnText: { color: color.text._100 },
        imageRow: { flexDirection: 'row', columnGap: spacing.s, marginBottom: spacing.s },
        thumb: { width: 64, height: 64, borderRadius: spacing.s },
        blocker: {
            color: color.error._300,
            paddingHorizontal: spacing.l,
            paddingBottom: spacing.s,
        },
        inputBar: {
            borderTopWidth: 1,
            borderTopColor: color.neutral._300,
            paddingHorizontal: spacing.l,
            paddingTop: spacing.m,
        },
        attachRow: { flexDirection: 'row', alignItems: 'center', columnGap: spacing.l },
        attachBtn: { flexDirection: 'row', alignItems: 'center', columnGap: spacing.s },
        attachText: { color: color.text._400, fontSize: fontSize.s },
        capability: { color: color.text._600, fontSize: fontSize.s, marginTop: spacing.s },
        sendRow: {
            flexDirection: 'row',
            alignItems: 'flex-end',
            columnGap: spacing.m,
            marginTop: spacing.m,
        },
        input: {
            flex: 1,
            color: color.text._100,
            maxHeight: 120,
            paddingVertical: spacing.s,
        },
        budget: { color: color.text._600, fontSize: fontSize.s, paddingVertical: spacing.s },
    })
}
