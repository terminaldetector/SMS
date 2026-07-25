// Unified QR connect for the HELIX no-PC mesh: one sheet, two tabs — "My QR" (this phone's connect
// code, shown once it's hosting) and "Scan" (read the other phone's code). One entry point (a QR
// icon in the screen header) instead of separate buttons buried in each section, so there's nothing
// to hunt for: open the sheet, either side of the handshake is right there.

import { CameraView, useCameraPermissions } from 'expo-camera'
import { useRef, useState } from 'react'
import { ActivityIndicator, Text, TouchableOpacity, View } from 'react-native'
import QRCode from 'react-native-qrcode-svg'

import ThemedButton from '@components/buttons/ThemedButton'
import { Theme } from '@lib/theme/ThemeManager'

import BottomSheet from './BottomSheet'

type Tab = 'my' | 'scan'

interface HelixQrSheetProps {
    visible: boolean
    setVisible: (visible: boolean) => void
    hosting: boolean
    hostStarting: boolean
    hostIp: string
    hostPort: number
    hostIpIsStale: boolean
    agentsJoined: number
    onStartHosting: () => void
    onRetryIp: () => Promise<void>
    onScanned: (data: string) => void
}

const HelixQrSheet: React.FC<HelixQrSheetProps> = ({
    visible,
    setVisible,
    hosting,
    hostStarting,
    hostIp,
    hostPort,
    hostIpIsStale,
    agentsJoined,
    onStartHosting,
    onRetryIp,
    onScanned,
}) => {
    const { color, spacing, fontSize } = Theme.useTheme()
    const [tab, setTab] = useState<Tab>('my')
    const [retrying, setRetrying] = useState(false)
    const [permission, requestPermission] = useCameraPermissions()
    const scannedRef = useRef(false)

    const handleRetryIp = async () => {
        setRetrying(true)
        try {
            await onRetryIp()
        } finally {
            setRetrying(false)
        }
    }

    const handleScanned = ({ data }: { data: string }) => {
        if (scannedRef.current) return
        scannedRef.current = true
        onScanned(data)
        setVisible(false)
    }

    return (
        <BottomSheet
            visible={visible}
            setVisible={setVisible}
            onClose={() => {
                scannedRef.current = false
                setTab('my')
            }}
            sheetStyle={{ flex: 1, maxHeight: '75%', justifyContent: 'flex-start' }}>
            <View style={{ flexDirection: 'row', marginBottom: spacing.l }}>
                {(['my', 'scan'] as Tab[]).map((t) => (
                    <TouchableOpacity
                        key={t}
                        onPress={() => setTab(t)}
                        style={{
                            flex: 1,
                            alignItems: 'center',
                            paddingBottom: spacing.m,
                            borderBottomWidth: 2,
                            borderBottomColor: tab === t ? color.primary._500 : color.neutral._300,
                        }}>
                        <Text
                            style={{
                                color: tab === t ? color.text._100 : color.text._400,
                                fontSize: fontSize.l,
                            }}>
                            {t === 'my' ? 'My QR' : 'Scan'}
                        </Text>
                    </TouchableOpacity>
                ))}
            </View>

            {tab === 'my' ? (
                <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', rowGap: spacing.m }}>
                    {hosting && hostIp ? (
                        <>
                            <View style={{ backgroundColor: 'white', padding: spacing.l, borderRadius: spacing.m }}>
                                <QRCode value={`ws://${hostIp}:${hostPort}`} size={200} />
                            </View>
                            <Text style={{ color: color.text._100, fontSize: fontSize.l }}>
                                {hostIp}:{hostPort}
                            </Text>
                            <Text style={{ color: color.text._400, textAlign: 'center' }}>
                                Scan this with the other phone's Scan tab to join as an agent.
                            </Text>
                            {hostIpIsStale && (
                                <Text style={{ color: color.text._400, textAlign: 'center' }}>
                                    (address from last session — reopen if it's changed)
                                </Text>
                            )}
                            <Text style={{ color: color.text._400 }}>
                                {agentsJoined === 0
                                    ? 'waiting for a phone to join…'
                                    : `${agentsJoined} phone(s) joined`}
                            </Text>
                        </>
                    ) : hosting ? (
                        <>
                            {retrying ? (
                                <ActivityIndicator color={color.text._100} />
                            ) : (
                                <Text style={{ color: color.text._400, textAlign: 'center' }}>
                                    Couldn't detect this phone's Wi-Fi address.
                                </Text>
                            )}
                            <ThemedButton
                                label={retrying ? 'Retrying…' : 'Retry'}
                                variant="secondary"
                                onPress={handleRetryIp}
                            />
                        </>
                    ) : (
                        <>
                            <Text style={{ color: color.text._400, textAlign: 'center' }}>
                                Start hosting to get a QR code the other phone can scan.
                            </Text>
                            <ThemedButton
                                label={hostStarting ? 'Starting…' : 'Start hosting'}
                                variant="primary"
                                onPress={onStartHosting}
                            />
                        </>
                    )}
                </View>
            ) : permission?.granted ? (
                <CameraView
                    barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
                    onBarcodeScanned={handleScanned}
                    style={{ flex: 1, borderRadius: 8 }}
                />
            ) : (
                <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', rowGap: spacing.m }}>
                    <Text style={{ color: color.text._100, textAlign: 'center' }}>
                        Camera access is needed to scan the host's QR code.
                    </Text>
                    <ThemedButton label="Grant camera access" onPress={requestPermission} />
                </View>
            )}
        </BottomSheet>
    )
}

export default HelixQrSheet
