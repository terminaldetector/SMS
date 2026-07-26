package expo.modules.wifihotspot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "WifiHotspot"

/**
 * Ad-hoc, internet-free Wi-Fi connection between two phones — Android's LocalOnlyHotspot on the
 * host side, WifiNetworkSpecifier (or, below Android 10, the legacy WifiConfiguration API) on the
 * joining side. This is the "full Wi-Fi speed, no router required" path the mesh's WLAN join was
 * missing — the SHAREit-style model, minus SHAREit's own file-transfer protocol, since HELIX
 * already has one: helixCoordinator/helixAgent/RPC are plain TCP and do not change at all here.
 * Only how the two phones end up sharing a subnet does.
 *
 * A local Expo module for the same reason bitchat-ble is one: no maintained RN package wraps
 * startLocalOnlyHotspot or WifiNetworkSpecifier, and this needs to be New-Architecture-native.
 *
 * NOT YET VERIFIED ON A DEVICE. Every other native module in this app was written the same way and
 * then fixed against real phone logs; this one has had no such pass yet, and OEM Wi-Fi stacks are
 * exactly where past assumptions here have turned out wrong (see BitchatBleModule's LAN-address
 * history). Treat the first real test as the actual proof, not this file compiling.
 */
class WifiHotspotModule : Module() {

  private val context: Context
    get() = requireNotNull(appContext.reactContext) { "no react context" }

  private val wifiManager: WifiManager
    get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

  private val connectivityManager: ConnectivityManager
    get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  private val mainHandler = Handler(Looper.getMainLooper())

  // Host side: kept only so stopHotspot() has something to close.
  private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

  // Join side: kept so leaveHotspot() can unbind and release exactly what joinHotspot() requested.
  private var joinedNetwork: Network? = null
  private var joinCallback: ConnectivityManager.NetworkCallback? = null

  override fun definition() = ModuleDefinition {
    Name("WifiHotspot")

    // API 26 (Oreo) is when LocalOnlyHotspot was introduced; there is no fallback below it.
    Function("isSupported") {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    AsyncFunction("requestPermissions") { promise: Promise ->
      // Expo's permission-request UI flow lives in JS (expo-location's askAsync, which prompts for
      // the exact same ACCESS_FINE_LOCATION grant this feature needs); this just reports whether
      // it is already in place, matching bitchat-ble's requestPermissions().
      promise.resolve(granted(Manifest.permission.ACCESS_FINE_LOCATION))
    }

    // Starts an ad-hoc Wi-Fi AP with no internet uplink and resolves its credentials once Android
    // confirms it actually started. Rejects with a specific reason otherwise — in particular, a
    // LocalOnlyHotspot can fail even with the permission granted if system Location is turned OFF,
    // which is not obvious from the permission grant alone, so that case gets called out by name.
    AsyncFunction("startHotspot") { promise: Promise ->
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        promise.reject("E_UNSUPPORTED", "This Android version has no local-hotspot API (needs Android 8+)", null)
        return@AsyncFunction
      }
      if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
        promise.reject("E_PERMISSION", "Location permission is required to start a Wi-Fi hotspot", null)
        return@AsyncFunction
      }
      startHotspotInternal(promise)
    }

    AsyncFunction("stopHotspot") { promise: Promise ->
      try {
        hotspotReservation?.close()
      } catch (e: Exception) {
        Log.w(TAG, "stopHotspot: close() threw", e)
      }
      hotspotReservation = null
      promise.resolve(null)
    }

    // Joins the network `ssid`/`passphrase` names and binds this app's OWN traffic to it. Without
    // that bind, sockets would keep preferring the phone's normal Wi-Fi/mobile data even after
    // associating: the hotspot network has no internet, so Android would never pick it as the
    // default route on its own.
    AsyncFunction("joinHotspot") { ssid: String, passphrase: String, promise: Promise ->
      joinHotspotInternal(ssid, passphrase, promise)
    }

    AsyncFunction("leaveHotspot") { promise: Promise ->
      releaseJoinedNetwork()
      // Restores this process's default network routing. Harmless if nothing was ever bound.
      try {
        connectivityManager.bindProcessToNetwork(null)
      } catch (e: Exception) {
        Log.w(TAG, "leaveHotspot: bindProcessToNetwork(null) threw", e)
      }
      promise.resolve(null)
    }

    // This phone's OWN address on the network joinHotspot() bound it to, or '' if nothing is
    // joined. Reads LinkProperties off the exact Network reference joinHotspot() already holds —
    // not a scan of every Wi-Fi-transport network on the device, which would be ambiguous the
    // moment a phone keeps its regular Wi-Fi connection up alongside the joined hotspot (Android
    // permits both at once on much hardware, and nothing then says which one a generic scan would
    // return first). This is what makes a shard worker's announced RPC address correct when it
    // joined over this path instead of ordinary Wi-Fi.
    Function("getJoinedNetworkIp") {
      joinedNetworkIp()
    }
  }

  private fun granted(permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  // --- host --------------------------------------------------------------------------------

  private fun startHotspotInternal(promise: Promise) {
    val callback = object : WifiManager.LocalOnlyHotspotCallback() {
      override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
        hotspotReservation = reservation
        val (ssid, passphrase) = credentialsFrom(reservation)
        if (ssid.isEmpty()) {
          hotspotReservation = null
          runCatching { reservation.close() }
          promise.reject("E_HOTSPOT_CREDENTIALS", "Hotspot started but its credentials could not be read", null)
          return
        }
        val ip = hotspotSelfIp()
        Log.i(TAG, "startHotspot -> ssid='$ssid' ip='$ip'")
        promise.resolve(mapOf("ssid" to ssid, "passphrase" to passphrase, "ip" to ip))
      }

      override fun onStopped() {
        Log.i(TAG, "hotspot stopped")
        hotspotReservation = null
      }

      override fun onFailed(reason: Int) {
        hotspotReservation = null
        val why = when (reason) {
          WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no Wi-Fi channel is available"
          WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "this device or user profile disallows tethering"
          // The most common real cause: the phone's OWN regular "Portable Wi-Fi hotspot" (system
          // Settings > tethering) is already switched on — Android won't run a LocalOnlyHotspot on
          // top of that, since it's already committed the radio to that. Turning that off is what
          // resolves this, not anything this app can retry its way out of.
          WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "Wi-Fi is already in a mode that can't also host this — if regular Wi-Fi hotspot/tethering " +
              "is on in system Settings, turn it off and try again"
          // ERROR_GENERIC covers the most common real case: system Location toggled off, which
          // LocalOnlyHotspot requires even once the permission itself is granted.
          else -> "the OS refused (code $reason) — check that Location is turned ON in system " +
            "settings, and that no other app is already hosting a hotspot"
        }
        Log.e(TAG, "startHotspot failed: $why")
        promise.reject("E_HOTSPOT_FAILED", "Could not start the hotspot: $why", null)
      }
    }
    wifiManager.startLocalOnlyHotspot(callback, mainHandler)
  }

  // SoftApConfiguration (typed, non-deprecated accessors) only exists from API 30; below that,
  // LocalOnlyHotspotReservation only ever had the older WifiConfiguration getter.
  @Suppress("DEPRECATION")
  private fun credentialsFrom(reservation: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val config = reservation.softApConfiguration
      if (config != null) return (config.ssid ?: "") to (config.passphrase ?: "")
    }
    val legacy = reservation.wifiConfiguration
    val ssid = legacy?.SSID?.removeSurrounding("\"") ?: ""
    val passphrase = legacy?.preSharedKey?.removeSurrounding("\"") ?: ""
    return ssid to passphrase
  }

  // LocalOnlyHotspot does not hand back this phone's own address on the network it just created —
  // only the well-known default subnet is documented (192.168.49.1). Looking for the actual
  // ap-like interface first is the same belt-and-suspenders approach getLocalIpAddress() in
  // bitchat-ble uses, rather than trusting a hardcoded constant on every OEM.
  private fun hotspotSelfIp(): String {
    val apLike = Regex("^(ap|softap|swlan|wlan1)\\d*$", RegexOption.IGNORE_CASE)
    return runCatching {
      NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) && apLike.matches(it.name) }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { addr -> addr is Inet4Address && !addr.isLoopbackAddress }
        ?.hostAddress
    }.getOrNull() ?: "192.168.49.1"
  }

  // --- join --------------------------------------------------------------------------------

  private fun joinHotspotInternal(ssid: String, passphrase: String, promise: Promise) {
    if (ssid.isEmpty()) {
      promise.reject("E_ARGS", "ssid must not be empty", null)
      return
    }
    // joinHotspot replaces whatever this phone was bound to before; it does not stack requests.
    releaseJoinedNetwork()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      joinHotspotModern(ssid, passphrase, promise)
    } else {
      joinHotspotLegacy(ssid, passphrase, promise)
    }
  }

  // WifiNetworkSpecifier (API 29+): the officially supported way for an app to join a specific
  // Wi-Fi network for its own use, without touching the phone's saved network list and — the whole
  // point of this API existing — without needing location permission on the joining side at all.
  private fun joinHotspotModern(ssid: String, passphrase: String, promise: Promise) {
    val specifier = WifiNetworkSpecifier.Builder()
      .setSsid(ssid)
      .setWpa2Passphrase(passphrase)
      .build()
    val request = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      // A LocalOnlyHotspot network has no internet uplink by design; without removing this
      // capability the request would never be satisfied by it.
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .setNetworkSpecifier(specifier)
      .build()

    var settled = false
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        if (settled) return
        settled = true
        connectivityManager.bindProcessToNetwork(network)
        joinedNetwork = network
        Log.i(TAG, "joinHotspot: bound to $ssid")
        promise.resolve(true)
      }

      override fun onUnavailable() {
        if (settled) return
        settled = true
        Log.e(TAG, "joinHotspot: request for $ssid timed out or was refused")
        promise.reject("E_JOIN_FAILED", "Could not join $ssid — wrong password, or it is out of range", null)
      }
    }
    joinCallback = callback
    connectivityManager.requestNetwork(request, callback, mainHandler)
  }

  // Pre-Android-10 fallback: WifiNetworkSpecifier does not exist yet, so this falls back to the
  // classic "add to the phone's saved network list, then switch to it" API. It needs
  // CHANGE_WIFI_STATE, has no per-attempt success callback (addNetwork/enableNetwork are fire-and-
  // forget), and is known to be considerably flakier across OEM Wi-Fi stacks than the path above —
  // every actively maintained target device should hit joinHotspotModern() instead. Kept only so
  // the feature degrades to "probably works" rather than "does not exist" on old devices.
  @Suppress("DEPRECATION")
  private fun joinHotspotLegacy(ssid: String, passphrase: String, promise: Promise) {
    if (!granted(Manifest.permission.CHANGE_WIFI_STATE)) {
      promise.reject("E_PERMISSION", "Wi-Fi state permission is required to join on this Android version", null)
      return
    }
    try {
      val config = WifiConfiguration().apply {
        SSID = "\"$ssid\""
        preSharedKey = "\"$passphrase\""
      }
      val netId = wifiManager.addNetwork(config)
      if (netId == -1) {
        promise.reject("E_JOIN_FAILED", "Could not add the network (addNetwork returned -1)", null)
        return
      }
      wifiManager.disconnect()
      wifiManager.enableNetwork(netId, true)
      wifiManager.reconnect()
      // No per-attempt callback on this legacy path — this is optimistic. The caller dials the
      // announced address right after and will surface its own failure if the join did not land.
      promise.resolve(true)
    } catch (e: Exception) {
      promise.reject("E_JOIN_FAILED", "Legacy Wi-Fi join failed: ${e.message}", e)
    }
  }

  private fun releaseJoinedNetwork() {
    try {
      joinCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
    } catch (e: Exception) {
      Log.w(TAG, "releaseJoinedNetwork: unregisterNetworkCallback threw", e)
    }
    joinCallback = null
    joinedNetwork = null
  }

  // Empty even after a real join is one legitimate case: joinHotspotLegacy() (pre-API-29) has no
  // Network reference to read at all — the classic addNetwork/enableNetwork API predates
  // ConnectivityManager's per-network model. That is harmless there specifically because that API
  // also cannot hold two Wi-Fi connections at once, so the generic Wi-Fi scan this falls back to
  // has only one real candidate to find anyway.
  private fun joinedNetworkIp(): String {
    val network = joinedNetwork ?: return ""
    return runCatching {
      connectivityManager.getLinkProperties(network)?.linkAddresses
        ?.mapNotNull { it.address as? Inet4Address }
        ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
    }.getOrNull() ?: ""
  }
}
