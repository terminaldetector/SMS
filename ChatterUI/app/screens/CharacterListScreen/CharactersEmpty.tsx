import { MaterialIcons } from '@expo/vector-icons'
import { Text, View } from 'react-native'

import { Theme } from '@lib/theme/ThemeManager'

const CharactersEmpty = () => {
    const { color, spacing, fontSize } = Theme.useTheme()
    return (
        <View
            style={{
                paddingVertical: spacing.xl,
                paddingHorizontal: spacing.m,
                flex: 1,
                alignItems: 'center',
                marginTop: spacing.xl3,
            }}>
            <MaterialIcons name="person-search" color={color.text._700} size={60} />
            <Text
                style={{
                    color: color.text._700,
                    marginTop: spacing.xl,
                    fontStyle: 'italic',
                    fontSize: fontSize.l,
                }}>
                No Characters Found. Try Importing Some!
            </Text>
        </View>
    )
}

export default CharactersEmpty
