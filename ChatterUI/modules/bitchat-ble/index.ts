// JS surface for the BitChat BLE transport.
//
// A BitChat node is BOTH a peripheral (advertises the service, runs a GATT server other peers write
// to) and a central (scans, connects, writes). No maintained React Native package does the
// peripheral half — the two that exist were last published in 2022 and predate the New Architecture
// — so this is a local Expo module, which autolinks and is New-Architecture-native without
// TurboModule codegen. See integration/chatterui_llamacpp/BITCHAT_BRIDGE.md.
//
// This module moves BYTES ONLY. Everything above it — packet format, Noise, fragmentation — is the
// pure-TS layer that is already proven in tests (bitchatCodec / bitchatNoise / bitchatFragment), so
// the one thing left to debug on a real phone is the radio plumbing itself.

import { requireOptionalNativeModule } from 'expo'

// A peer is identified by its BLE address. The same physical device can appear over both roles
// (we connected to it, and it connected to us); sends pick whichever link is available.
export interface BlePeerEvent {
    peerId: string
}

export interface BlePacketEvent {
    peerId: string
    /** Raw bytes as written/notified by the peer — one BitChat packet, still framed. */
    data: Uint8Array
}

/** What addListener hands back; declared locally so nothing depends on expo-modules-core's path. */
export interface BleSubscription {
    remove(): void
}

export interface BitchatBleModule {
    /** False when the device has no BLE at all. */
    isSupported(): boolean
    /**
     * This phone's IPv4 LAN address, or '' if it has none. Not BLE, but it lives here because this
     * is the only native code the fork owns, and expo-network's WifiManager-based answer is
     * 0.0.0.0 on plenty of modern devices (restricted Wi-Fi info, tethering, hotspot).
     */
    getLocalIpAddress(): string
    /** Plenty of chipsets scan but cannot advertise — check before promising to be reachable. */
    isPeripheralSupported(): boolean
    /** Whether the BLE runtime permissions are granted (Android 12+ wants SCAN/CONNECT/ADVERTISE). */
    requestPermissions(): Promise<boolean>
    /** Advertise `serviceUuid` and serve `characteristicUuid` so BitChat peers can write to us. */
    startPeripheral(serviceUuid: string, characteristicUuid: string): Promise<boolean>
    stopPeripheral(): Promise<void>
    /** Scan for peers advertising `serviceUuid`, connect, and subscribe to notifications. */
    startCentral(serviceUuid: string, characteristicUuid: string): Promise<boolean>
    stopCentral(): Promise<void>
    /** Send one already-framed packet to a peer. Returns false if no link to it is usable. */
    send(peerId: string, data: Uint8Array): Promise<boolean>
    /** Send to every connected peer; returns how many links accepted it. */
    broadcast(data: Uint8Array): Promise<number>
    connectedPeers(): string[]
    addListener(event: 'onPacket', listener: (e: BlePacketEvent) => void): BleSubscription
    addListener(
        event: 'onPeerConnected' | 'onPeerDisconnected',
        listener: (e: BlePeerEvent) => void
    ): BleSubscription
}

// Optional on purpose: an APK built before this module existed simply won't have it, and that must
// surface as a clear "not available in this build" rather than a crash at import time — the same
// failure mode that made every native call return null once already.
export const BitchatBle = requireOptionalNativeModule<BitchatBleModule>('BitchatBle')

export const isBitchatBleAvailable = () => BitchatBle !== null

export default BitchatBle
