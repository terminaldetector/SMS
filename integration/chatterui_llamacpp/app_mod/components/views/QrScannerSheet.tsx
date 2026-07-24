// QR scanner for the HELIX Mesh "Join as agent" flow — scan the host phone's QR (shown by the
// "Device-to-device (no PC)" section) instead of typing its IP:port by hand. Uses expo-camera's
// barcode scanning (already a ChatterUI dep, no new native module) — mirrors CameraSheet.tsx.

import { CameraView, useCameraPermissions } from 'expo-camera'
import { useRef } from 'react'
import { Text } from 'react-native'

import ThemedButton from '@components/buttons/ThemedButton'
import { Theme } from '@lib/theme/ThemeManager'

import BottomSheet from './BottomSheet'

interface QrScannerSheetProps {
    visible: boolean
    setVisible: (visible: boolean) => void
    onScanned: (data: string) => void
}

const QrScannerSheet: React.FC<QrScannerSheetProps> = ({ visible, setVisible, onScanned }) => {
    const { color, spacing } = Theme.useTheme()
    const [permission, requestPermission] = useCameraPermissions()
    const scannedRef = useRef(false)

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
            }}
            sheetStyle={{ flex: 1, maxHeight: '70%', justifyContent: 'space-between' }}>
            {permission?.granted ? (
                <CameraView
                    barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
                    onBarcodeScanned={handleScanned}
                    style={{ flex: 1, borderRadius: 8, marginBottom: 24 }}
                />
            ) : (
                <>
                    <Text style={{ color: color.text._100, marginBottom: spacing.l }}>
                        Camera access is needed to scan the host's QR code.
                    </Text>
                    <ThemedButton label="Grant camera access" onPress={requestPermission} />
                </>
            )}
        </BottomSheet>
    )
}

export default QrScannerSheet
