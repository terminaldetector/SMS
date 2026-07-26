// JS surface for an ad-hoc, internet-free Wi-Fi connection between two phones — Android's
// LocalOnlyHotspot on the host side, WifiNetworkSpecifier (or, pre-Android-10, the legacy
// WifiConfiguration API) on the joining side. This is the SHAREit-style "full Wi-Fi speed, no
// router required" path: HELIX's own protocol (helixCoordinator/helixAgent/RPC, all plain TCP)
// does not change at all — only how the two phones end up sharing a subnet does.
//
// A local Expo module for the same reason bitchat-ble is one: no maintained RN package wraps these
// APIs, and this needs to be New-Architecture-native.

import { requireOptionalNativeModule } from 'expo'

export interface HotspotCredentials {
    ssid: string
    passphrase: string
    /** This phone's address on the hotspot it just started — normally 192.168.49.1. */
    ip: string
}

export interface WifiHotspotModule {
    /** False below Android 8 (API 26) — LocalOnlyHotspot does not exist there. */
    isSupported(): boolean
    /** Runtime location permission the HOST needs; Android requires it for this API on every version. */
    requestPermissions(): Promise<boolean>
    /**
     * Starts an ad-hoc Wi-Fi AP with no internet uplink and returns its credentials. Rejects with a
     * specific reason otherwise — in particular, this can fail even with location permission
     * granted if system Location is turned off, which is not obvious from the permission alone.
     */
    startHotspot(): Promise<HotspotCredentials>
    stopHotspot(): Promise<void>
    /**
     * Joins the network `ssid`/`passphrase` name and binds this app's OWN network traffic to it —
     * without that bind, sockets would keep preferring the phone's normal Wi-Fi/mobile data even
     * after associating, since this network has no internet and Android would not make it the
     * default route on its own.
     */
    joinHotspot(ssid: string, passphrase: string): Promise<boolean>
    /** Undoes joinHotspot's process bind and releases the requested network. */
    leaveHotspot(): Promise<void>
    /**
     * This phone's OWN address on the network joinHotspot() bound it to, or '' if nothing is
     * joined. Reads the exact network joinHotspot() holds a reference to, rather than scanning
     * every Wi-Fi network the phone happens to have up — the regular Wi-Fi connection can stay
     * live alongside the joined hotspot on plenty of hardware, so a generic scan cannot tell them
     * apart. This is what a shard worker needs to announce the right RPC address.
     */
    getJoinedNetworkIp(): string
    /**
     * The address serving the joined hotspot — i.e. the host phone itself, read from the DHCP
     * lease this phone just took. Authoritative in a way the scanned QR is not: the host has to
     * work out its own address to put in that code and can get it wrong. '' if not joined, or on
     * a build/API level that cannot report it.
     */
    getJoinedNetworkGateway(): string
}

// Optional on purpose, same reasoning as bitchat-ble: an APK built before this module existed must
// fail with a clear "not available" rather than crash at import time.
export const WifiHotspot = requireOptionalNativeModule<WifiHotspotModule>('WifiHotspot')

export const isWifiHotspotAvailable = () => WifiHotspot !== null

export default WifiHotspot
