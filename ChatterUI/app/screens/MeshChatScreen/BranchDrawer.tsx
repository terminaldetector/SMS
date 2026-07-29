// The mesh chat's context branches — what ChatsDrawer is for an ordinary chat, and opened the same
// way, by swiping in from the right edge.
//
// A branch is a whole conversation. Switching between them is how you find out whether the mesh is
// really carrying context or just answering each prompt fresh: fork after a couple of turns, ask
// the follow-up two different ways, and compare. If both branches answer identically regardless of
// what came before them, the context never reached the model.

import { AntDesign } from '@expo/vector-icons'
import { FlatList, StyleSheet, Text, TouchableOpacity, View } from 'react-native'

import ThemedButton from '@components/buttons/ThemedButton'
import Drawer from '@components/views/Drawer'
import { activeOf, branchesOf, MeshBranch, useHelixChat } from '@lib/helixChat'
import { MeshMode } from '@lib/helixSession'
import { Theme } from '@lib/theme/ThemeManager'

interface BranchDrawerProps {
    mode: MeshMode
}

const BranchDrawer: React.FC<BranchDrawerProps> = ({ mode }) => {
    const styles = useStyles()
    const { color, spacing } = Theme.useTheme()
    // Subscribed to the raw map, which is a stable reference between changes, then derived from
    // with pure helpers. Selecting the derived list instead would hand zustand a freshly built
    // array on every comparison and it would never see the state settle.
    const chats = useHelixChat((s) => s.chats)
    const setActive = useHelixChat((s) => s.setActive)
    const newBranch = useHelixChat((s) => s.newBranch)
    const deleteBranch = useHelixChat((s) => s.deleteBranch)
    const clearBranch = useHelixChat((s) => s.clearBranch)
    const setShow = Drawer.useDrawerStore((state) => state.setShow)

    const branches = branchesOf(chats, mode)
    const activeId = activeOf(chats, mode).id

    const close = () => setShow(Drawer.ID.MESHBRANCH, false)

    const renderItem = ({ item }: { item: MeshBranch }) => {
        const selected = item.id === activeId
        const last = item.turns[item.turns.length - 1]
        return (
            <TouchableOpacity
                style={[styles.item, selected && styles.itemSelected]}
                onPress={() => {
                    setActive(mode, item.id)
                    close()
                }}>
                <View style={{ flex: 1 }}>
                    <Text style={selected ? styles.nameSelected : styles.name}>{item.name}</Text>
                    <Text style={styles.meta} numberOfLines={1}>
                        {item.turns.length === 0
                            ? 'empty'
                            : `${item.turns.length} turns · ${last?.text.slice(0, 40) ?? ''}`}
                    </Text>
                </View>
                {branches.length > 1 && (
                    <TouchableOpacity
                        hitSlop={10}
                        onPress={() => deleteBranch(mode, item.id)}
                        style={{ paddingLeft: spacing.m }}>
                        <AntDesign name="delete" size={18} color={color.error._300} />
                    </TouchableOpacity>
                )}
            </TouchableOpacity>
        )
    }

    return (
        <Drawer.Body
            drawerID={Drawer.ID.MESHBRANCH}
            direction="right"
            drawerStyle={{ width: '80%', right: 0, paddingTop: spacing.xl2 }}>
            <Text style={styles.title}>Context branches</Text>
            <Text style={styles.sub}>
                {mode === 'pointer'
                    ? 'Answered by a joined phone, over the mesh.'
                    : 'Answered by the model split across the mesh.'}
            </Text>

            <FlatList
                data={branches}
                keyExtractor={(b) => b.id}
                renderItem={renderItem}
                style={{ flex: 1, marginTop: spacing.l }}
            />

            <View style={{ paddingHorizontal: spacing.l, rowGap: spacing.m }}>
                <ThemedButton
                    label="New branch"
                    variant="secondary"
                    onPress={() => {
                        newBranch(mode)
                        close()
                    }}
                />
                <ThemedButton
                    label="Clear this branch"
                    variant="critical"
                    onPress={() => clearBranch(mode)}
                />
            </View>
        </Drawer.Body>
    )
}

export default BranchDrawer

const useStyles = () => {
    const { color, spacing, fontSize } = Theme.useTheme()
    return StyleSheet.create({
        title: {
            color: color.text._100,
            fontSize: fontSize.xl,
            paddingHorizontal: spacing.l,
        },
        sub: { color: color.text._500, paddingHorizontal: spacing.l, marginTop: spacing.s },
        item: {
            flexDirection: 'row',
            alignItems: 'center',
            paddingVertical: spacing.l,
            paddingHorizontal: spacing.l,
            borderBottomWidth: 1,
            borderBottomColor: color.neutral._200,
        },
        itemSelected: { backgroundColor: color.primary._100 },
        name: { color: color.text._300 },
        nameSelected: { color: color.text._100 },
        meta: { color: color.text._500, fontSize: fontSize.s, marginTop: 2 },
    })
}
