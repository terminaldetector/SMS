import { CameraCapturedPicture, CameraView } from 'expo-camera'
import { useRef } from 'react'

import ThemedButton from '@components/buttons/ThemedButton'

import BottomSheet from './BottomSheet'

interface CameraSheetProps {
    visible: boolean
    setVisible: (visible: boolean) => void
    onTakePicture: (picture: CameraCapturedPicture) => void
}

const CameraSheet: React.FC<CameraSheetProps> = ({ visible, setVisible, onTakePicture }) => {
    const cameraRef = useRef<CameraView>(null)

    const handleTakePicture = async () => {
        const camera = cameraRef.current
        if (!camera) return
        const picture = await camera.takePictureAsync()
        if (!picture) return
        onTakePicture(picture)
        setVisible(false)
    }

    return (
        <BottomSheet
            visible={visible}
            setVisible={setVisible}
            sheetStyle={{ flex: 1, maxHeight: '70%', justifyContent: 'space-between' }}>
            <CameraView
                ref={cameraRef}
                autofocus="on"
                mode="picture"
                style={{ flex: 1, borderRadius: 8, marginBottom: 24 }}
            />
            <ThemedButton iconName="camera" onPress={handleTakePicture} />
        </BottomSheet>
    )
}

export default CameraSheet
