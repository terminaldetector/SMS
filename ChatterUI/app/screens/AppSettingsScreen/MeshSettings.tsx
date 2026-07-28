// Settings for the mesh itself, as opposed to what any one screen is doing with it.
//
// These are the values every phone in a mesh has to agree on — ports and the cluster secret — plus
// how much memory this particular phone is willing to lend. They used to be constants inside
// HELIX Mesh, which was fine while it was a demo and wrong the moment two phones had to match.
//
// Nothing here disturbs a mesh that is already running: a bound socket cannot follow a changed
// port, and a coordinator keeps the secret it started with. The new values apply to the next
// start, which is said plainly on screen rather than left to be discovered.

import { useRouter } from 'expo-router'
import React from 'react'
import { Text, View } from 'react-native'
import { useMMKVString } from 'react-native-mmkv'

import ThemedButton from '@components/buttons/ThemedButton'
import ThemedTextInput from '@components/input/ThemedTextInput'
import HorizontalSelector from '@components/input/HorizontalSelector'
import SectionTitle from '@components/text/SectionTitle'
import {
    HELIX_DEFAULT_PORT,
    HELIX_DEFAULT_RPC_PORT,
    HELIX_DEFAULT_SECRET,
    HelixKeys,
    MEMORY_PROFILE_FRACTION,
    MemoryProfile,
    helixMemoryProfile,
} from '@lib/helixSettings'
import { Theme } from '@lib/theme/ThemeManager'

const portIsValid = (raw: string | undefined): boolean => {
    if (!raw) return true
    const n = Number(raw)
    return Number.isInteger(n) && n >= 1024 && n <= 65535
}

const MeshSettings = () => {
    const { color, spacing } = Theme.useTheme()
    const router = useRouter()
    const [port, setPort] = useMMKVString(HelixKeys.port)
    const [rpcPort, setRpcPort] = useMMKVString(HelixKeys.rpcPort)
    const [secret, setSecret] = useMMKVString(HelixKeys.secret)
    const [, setMemoryProfile] = useMMKVString(HelixKeys.memoryProfile)
    const profile = helixMemoryProfile()

    const usingDefaultSecret = !secret || secret === HELIX_DEFAULT_SECRET
    const dim = { color: color.text._500 }
    const warn = { color: color.error._300 }

    return (
        <View style={{ rowGap: 8 }}>
            <SectionTitle>Mesh Network</SectionTitle>

            <Text style={dim}>
                Every phone in one mesh must use the same values. Changes apply the next time you
                start hosting or join — a mesh running right now keeps what it started with.
            </Text>

            <ThemedTextInput
                label="Mesh port"
                value={port ?? ''}
                onChangeText={setPort}
                placeholder={String(HELIX_DEFAULT_PORT)}
                keyboardType="number-pad"
                containerStyle={{ marginTop: spacing.m }}
                description="Where the host listens for phones joining, and where its QR points. Change it only if something else on the phone already has this port."
            />
            {!portIsValid(port) && (
                <Text style={warn}>
                    {`Not a usable port — ${HELIX_DEFAULT_PORT} will be used instead. Pick 1024–65535.`}
                </Text>
            )}

            <ThemedTextInput
                label="Layer transfer port"
                value={rpcPort ?? ''}
                onChangeText={setRpcPort}
                placeholder={String(HELIX_DEFAULT_RPC_PORT)}
                keyboardType="number-pad"
                containerStyle={{ marginTop: spacing.m }}
                description="Used in Sharder only: the port each phone serves its share of the model's layers on."
            />
            {!portIsValid(rpcPort) && (
                <Text style={warn}>
                    {`Not a usable port — ${HELIX_DEFAULT_RPC_PORT} will be used instead. Pick 1024–65535.`}
                </Text>
            )}

            <ThemedTextInput
                label="Cluster secret"
                value={secret ?? ''}
                onChangeText={setSecret}
                placeholder={HELIX_DEFAULT_SECRET}
                autoCapitalize="none"
                autoCorrect={false}
                containerStyle={{ marginTop: spacing.m }}
                description="Encrypts everything phones send each other, and decides who may join. Type the same phrase on each phone."
            />
            {usingDefaultSecret && (
                <Text style={warn}>
                    This is the built-in demo phrase, and it is public. Anyone running this app on
                    the same network can join your mesh and be sent your prompts. Set your own.
                </Text>
            )}

            <View style={{ marginTop: spacing.l }}>
                <Text style={{ color: color.text._100 }}>Memory this phone lends</Text>
                <HorizontalSelector
                    selected={profile}
                    onPress={(v: MemoryProfile) => setMemoryProfile(v)}
                    values={[
                        { label: 'Cautious', value: 'cautious' },
                        { label: 'Balanced', value: 'balanced' },
                        { label: 'Greedy', value: 'greedy' },
                    ]}
                />
                <Text style={[dim, { marginTop: spacing.m }]}>
                    {`Sharder splits a model by what each phone can hold, and this sets how much of this phone's free memory it may claim — ${Math.round(
                        MEMORY_PROFILE_FRACTION[profile] * 100
                    )}% of it. More memory here means fewer hops per token, but a phone killed for running out of memory costs the whole mesh the answer it was in the middle of.`}
                </Text>
            </View>

            {/* Context length, threads and layer count are not duplicated here — they already have
                a home, and two controls for one value is how they end up disagreeing. This is the
                signpost, since a mesh's speed depends on them as much as on anything above. */}
            <ThemedButton
                label="Local model settings"
                variant="secondary"
                onPress={() => router.push('/screens/ModelManagerScreen')}
                buttonStyle={{ marginTop: spacing.l }}
            />
            <Text style={dim}>
                Context length, threads and layers per model live in Models, under "Show Settings".
                They apply to whatever this phone runs, meshed or alone.
            </Text>
        </View>
    )
}

export default MeshSettings
