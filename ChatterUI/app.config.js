const IS_DEV = process.env.APP_VARIANT === 'development'

// This fork is called TriangleUI. Only the display name and icon change: `slug`, `scheme` and the
// Android/iOS package ids deliberately stay as they are, because changing a package id makes this a
// different app to the OS — a new install that cannot see the chats, characters or models the one
// already on the phone has.
module.exports = {
    expo: {
        name: IS_DEV ? 'TriangleUI (DEV)' : 'TriangleUI',
        newArchEnabled: true,
        slug: 'ChatterUI',
        version: '0.9.0',
        orientation: 'default',
        icon: './assets/images/icon.png',
        scheme: 'chatterui',
        userInterfaceStyle: 'automatic',
        assetBundlePatterns: ['**/*'],
        ios: {
            icon: {
                dark: './assets/images/ios-dark.png',
                light: './assets/images/ios-light.png',
                tinted: './assets/images/icon.png',
            },
            supportsTablet: true,
            package: IS_DEV ? 'com.Vali98.ChatterUIDev' : 'com.Vali98.ChatterUI',
            bundleIdentifier: IS_DEV ? 'com.Vali98.ChatterUIDev' : 'com.Vali98.ChatterUI',
        },
        android: {
            adaptiveIcon: {
                foregroundImage: './assets/images/adaptive-icon-foreground.png',
                backgroundImage: './assets/images//adaptive-icon-background.png',
                monochromeImage: './assets/images/adaptive-icon-foreground.png',
                backgroundColor: '#000',
            },
            edgeToEdgeEnabled: true,
            package: IS_DEV ? 'com.Vali98.ChatterUIDev' : 'com.Vali98.ChatterUI',
            userInterfaceStyle: 'dark',
            permissions: [
                'android.permission.FOREGROUND_SERVICE',
                'android.permission.WAKE_LOCK',
                'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
            ],
        },
        web: {
            bundler: 'metro',
            output: 'static',
            favicon: './assets/images/adaptive-icon.png',
        },
        plugins: [
            [
                'expo-asset',
                {
                    assets: ['./assets/models/aibot.raw', './assets/models/llama3tokenizer.gguf'],
                },
            ],
            [
                'expo-build-properties',
                {
                    android: {
                        largeHeap: true,
                        usesCleartextTraffic: true,
                        enableProguardInReleaseBuilds: true,
                        enableShrinkResourcesInReleaseBuilds: true,
                        useLegacyPackaging: true,
                        extraProguardRules: '-keep class com.rnllama.** { *; }',
                    },
                },
            ],
            [
                'expo-splash-screen',
                {
                    backgroundColor: '#000000',
                    image: './assets/images/adaptive-icon.png',
                    imageWidth: 200,
                },
            ],
            [
                'expo-notifications',
                {
                    icon: './assets/images/notification.png',
                },
            ],
            [
                './expo-build-plugins/androidattributes.plugin.js',
                {
                    'android:largeHeap': true,
                },
            ],
            ['@vali98/react-native-process-text', { label: 'Ask In TriangleUI' }],
            [
                'expo-camera',
                {
                    cameraPermission: 'Allow TriangleUI to access your camera',
                },
            ],
            ['expo-sqlite', { withSQLiteVecExtension: true }],
            'expo-localization',
            'expo-router',
            'expo-font',
            'expo-image',
            './expo-build-plugins/bgactions.plugin.js',
            './expo-build-plugins/usercert.plugin.js',
            './expo-build-plugins/rnllama.plugin.js',
            './expo-build-plugins/copyhtp.plugin.js',
        ],
        experiments: {
            typedRoutes: true,
            reactCompiler: true,
        },
        extra: {
            router: {
                origin: false,
            },
            eas: {
                projectId: 'd588a96a-5eb0-457a-85bc-e21acfdc60e9',
            },
        },
    },
}
