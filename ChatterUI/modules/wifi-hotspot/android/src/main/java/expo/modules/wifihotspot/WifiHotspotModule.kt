package expo.modules.wifihotspot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
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

// Carried across to the joining phone (via the QR code) so it builds a matching specifier instead
// of assuming WPA2 and silently failing to associate with a WPA3 access point.
private const val SECURITY_OPEN = "open"
private const val SECURITY_WPA2 = "wpa2"
private const val SECURITY_WPA3 = "wpa3"

// Long enough to read and accept the system "Connect to device?" dialog, short enough that a
// request nobody answers fails visibly instead of looking hung.
private const val JOIN_TIMEOUT_MS = 45_000

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
  private var joinedSsid: String? = null
  private var joinCallback: ConnectivityManager.NetworkCallback? = null

  override fun definition() = ModuleDefinition {
    Name("WifiHotspot")

    // API 26 (Oreo) is when LocalOnlyHotspot was introduced; there is no fallback below it.
    Function("isSupported") {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    AsyncFunction("requestPermissions") { promise: Promise ->
      // The prompt itself is raised from JS (PermissionsAndroid); this only reports whether the
      // grant is already in place, matching bitchat-ble's requestPermissions().
      promise.resolve(granted(hotspotPermission()))
    }

    // Which permission this API level actually gates the hotspot on, so JS asks for the right one.
    Function("getRequiredPermission") {
      hotspotPermission()
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
      if (!granted(hotspotPermission())) {
        promise.reject(
          "E_PERMISSION",
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            "The nearby-devices permission is required to start a Wi-Fi hotspot"
          else
            "Location permission is required to start a Wi-Fi hotspot",
          null
        )
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
    // `security` is "wpa2" (default), "wpa3" or "open" — what the host reported its AP actually
    // came up as. Optional so a code produced by an older build still joins as WPA2.
    AsyncFunction("joinHotspot") { ssid: String, passphrase: String, security: String?, promise: Promise ->
      joinHotspotInternal(ssid, passphrase, security ?: SECURITY_WPA2, promise)
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

    // The address of whoever is SERVING the joined hotspot — which, on a hotspot, is by definition
    // the phone hosting it. Authoritative in a way the scanned QR code is not: the host has to work
    // out its own address to put in that code and can get it wrong (it did — a host advertising a
    // hardcoded 192.168.49.1 while actually running on 192.168.43.1 is what made every join fail
    // with a websocket error and left the host showing zero devices). This is read from the DHCP
    // handshake this phone just completed, so it cannot disagree with reality.
    Function("getJoinedNetworkGateway") {
      joinedNetworkGateway()
    }
  }

  private fun granted(permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  // Android 13 moved startLocalOnlyHotspot() off ACCESS_FINE_LOCATION and onto its own
  // NEARBY_WIFI_DEVICES runtime permission. Asking for location on a Tiramisu+ device gets the
  // grant but not the capability, and the call still dies with a SecurityException reading
  // "does not have nearby devices permission" — which is exactly what happened on a real phone.
  private fun hotspotPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
      Manifest.permission.ACCESS_FINE_LOCATION
    }

  // --- host --------------------------------------------------------------------------------

  private fun startHotspotInternal(promise: Promise) {
    val callback = object : WifiManager.LocalOnlyHotspotCallback() {
      override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
        hotspotReservation = reservation
        val (ssid, passphrase, security) = credentialsFrom(reservation)
        if (ssid.isEmpty()) {
          hotspotReservation = null
          runCatching { reservation.close() }
          promise.reject("E_HOTSPOT_CREDENTIALS", "Hotspot started but its credentials could not be read", null)
          return
        }
        // Up to ~3s of polling: the AP interface routinely has no IPv4 yet at this instant.
        resolveHotspotIp(0, 12) { ip ->
          Log.i(TAG, "startHotspot -> ssid='$ssid' security='$security' ip='$ip'")
          promise.resolve(
            mapOf("ssid" to ssid, "passphrase" to passphrase, "ip" to ip, "security" to security)
          )
        }
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
  private fun credentialsFrom(reservation: WifiManager.LocalOnlyHotspotReservation): Triple<String, String, String> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val config = reservation.softApConfiguration
      if (config != null) {
        // Which WPA generation the AP actually came up as. Newer devices can bring a
        // LocalOnlyHotspot up as WPA3, and a joiner that assumes WPA2 will never associate with
        // it — the failure surfaces as a generic "wrong password", which is badly misleading.
        val security = when (config.securityType) {
          SoftApConfiguration.SECURITY_TYPE_OPEN -> SECURITY_OPEN
          SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> SECURITY_WPA3
          // Transition mode accepts a WPA2 supplicant, so it is joined as WPA2 on purpose.
          else -> SECURITY_WPA2
        }
        return Triple(config.ssid ?: "", config.passphrase ?: "", security)
      }
    }
    val legacy = reservation.wifiConfiguration
    val ssid = legacy?.SSID?.removeSurrounding("\"") ?: ""
    val passphrase = legacy?.preSharedKey?.removeSurrounding("\"") ?: ""
    // Below API 30 a LocalOnlyHotspot is always WPA2-PSK; WPA3 did not exist for this API yet.
    return Triple(ssid, passphrase, SECURITY_WPA2)
  }

  // LocalOnlyHotspot does not hand back this phone's own address on the network it just created,
  // so it has to be read off the AP interface. Returns "" when it genuinely cannot be determined
  // yet — deliberately NOT the documented 192.168.49.1 default, which is what this used to do and
  // was flatly wrong on real hardware: a device whose hotspot actually came up on 192.168.43.1 was
  // told to advertise 192.168.49.1, so joining phones associated to the Wi-Fi fine (the SSID and
  // passphrase are real, they come from the reservation) and then failed every dial to the
  // coordinator, leaving the host reporting zero devices. An empty answer lets the caller fall
  // back to normal interface detection, which found the correct address all along.
  private fun addressOf(iface: NetworkInterface): String? =
    runCatching {
      iface.inetAddresses.toList()
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
        ?.hostAddress
    }.getOrNull()

  private fun upInterfaces(): List<NetworkInterface> =
    runCatching {
      NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
    }.getOrDefault(emptyList())

  // Strictly the access-point interface. Kept separate from the looser search below because
  // "any site-local address" is NOT a safe stand-in while the AP is still coming up: a mobile
  // carrier hands out site-local addresses too (10.x — this very phone was seen on
  // 10.224.204.191), so a looser check returns the carrier address the instant it is asked,
  // stops the polling early, and puts an address no joining phone can ever reach into the QR.
  private fun apInterfaceIp(): String {
    val apLike = Regex("^(ap|softap|swlan|wlan\\d+)\\d*$", RegexOption.IGNORE_CASE)
    for (iface in upInterfaces()) {
      if (!apLike.matches(iface.name)) continue
      val addr = addressOf(iface)
      if (addr != null) {
        Log.i(TAG, "hotspotSelfIp -> $addr on ${iface.name} (ap interface)")
        return addr
      }
    }
    return ""
  }

  // Last resort once polling is exhausted and no AP-named interface ever produced an address: an
  // OEM may name it something unexpected. Cellular interfaces are excluded by name for the reason
  // above — a carrier address here is not a fallback, it is a wrong answer.
  private fun nonCellularIp(): String {
    val cellular = Regex("^(rmnet|ccmni|pdp|wwan|clat).*$", RegexOption.IGNORE_CASE)
    for (iface in upInterfaces()) {
      if (cellular.matches(iface.name)) continue
      val addr = addressOf(iface)
      if (addr != null) {
        Log.i(TAG, "hotspotSelfIp -> $addr on ${iface.name} (fallback, no ap interface found)")
        return addr
      }
    }
    return ""
  }

  // The AP interface usually has no IPv4 at the instant onStarted() fires — that race is exactly
  // what made the old code fall through to its hardcoded constant. Poll for the AP interface
  // specifically, and only widen the search once that has genuinely run out of time.
  private fun resolveHotspotIp(attempt: Int, maxAttempts: Int, onResolved: (String) -> Unit) {
    val ip = apInterfaceIp()
    if (ip.isNotEmpty()) {
      onResolved(ip)
      return
    }
    if (attempt >= maxAttempts) {
      val fallback = nonCellularIp()
      if (fallback.isEmpty()) Log.w(TAG, "hotspot started but no address appeared after $attempt tries")
      onResolved(fallback)
      return
    }
    mainHandler.postDelayed({ resolveHotspotIp(attempt + 1, maxAttempts, onResolved) }, 250)
  }

  // --- join --------------------------------------------------------------------------------

  private fun joinHotspotInternal(ssid: String, passphrase: String, security: String, promise: Promise) {
    if (ssid.isEmpty()) {
      promise.reject("E_ARGS", "ssid must not be empty", null)
      return
    }
    // Already on this exact network? Reuse it. Tearing the request down and raising a new one
    // makes Android show its "Connect to device?" dialog all over again, so re-scanning a code for
    // a hotspot this phone is already joined to used to drop a working link and demand another tap.
    val existing = joinedNetwork
    if (existing != null && joinedSsid == ssid &&
      connectivityManager.getNetworkCapabilities(existing) != null
    ) {
      Log.i(TAG, "joinHotspot: already joined to $ssid, reusing it")
      connectivityManager.bindProcessToNetwork(existing)
      promise.resolve(true)
      return
    }

    // Otherwise this replaces whatever this phone was bound to before; it does not stack requests.
    releaseJoinedNetwork()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      joinHotspotModern(ssid, passphrase, security, promise)
    } else {
      joinHotspotLegacy(ssid, passphrase, promise)
    }
  }

  // WifiNetworkSpecifier (API 29+): the officially supported way for an app to join a specific
  // Wi-Fi network for its own use, without touching the phone's saved network list and — the whole
  // point of this API existing — without needing location permission on the joining side at all.
  //
  // Android shows the user a system dialog for this request and will not associate until they
  // accept it, so the failure path here is at least as often "nobody tapped Connect" as it is a
  // genuinely bad credential.
  private fun joinHotspotModern(ssid: String, passphrase: String, security: String, promise: Promise) {
    val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
    when {
      security == SECURITY_OPEN -> { /* no passphrase to set */ }
      // A WPA3 access point will not accept a WPA2 supplicant, and the resulting failure looks
      // exactly like a wrong password — so the host tells us which it is rather than guessing.
      security == SECURITY_WPA3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
        builder.setWpa3Passphrase(passphrase)
      else -> builder.setWpa2Passphrase(passphrase)
    }
    val request = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      // A LocalOnlyHotspot network has no internet uplink by design; without removing this
      // capability the request would never be satisfied by it.
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .setNetworkSpecifier(builder.build())
      .build()

    var settled = false
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        if (settled) return
        settled = true
        connectivityManager.bindProcessToNetwork(network)
        joinedNetwork = network
        joinedSsid = ssid
        // onAvailable only means associated — DHCP may still be settling, so LinkProperties can
        // have neither an address nor a gateway for another moment. Resolving straight away means
        // the very next thing JS does (ask for the gateway, then dial the host) races that and
        // silently falls back to the address in the QR code. Same race the host side had; it
        // belongs on both ends.
        awaitLinkReady(network, 0, 12) {
          Log.i(TAG, "joinHotspot: bound to $ssid ($security) ip='${joinedNetworkIp()}' gw='${joinedNetworkGateway()}'")
          promise.resolve(true)
        }
      }

      override fun onUnavailable() {
        if (settled) return
        settled = true
        Log.e(TAG, "joinHotspot: request for $ssid ($security) was refused or timed out")
        promise.reject(
          "E_JOIN_FAILED",
          "Could not join $ssid — tap \"Connect\" on the dialog Android shows when joining. " +
            "If no dialog appeared, the host may have restarted its hotspot since this code was " +
            "made (the name and password change every time) — show a fresh QR and scan it again.",
          null
        )
      }
    }
    joinCallback = callback
    // Explicit timeout so a request that nobody accepts fails predictably with the message above,
    // rather than sitting on whatever internal deadline the platform happens to use (~80s was
    // observed) and leaving the UI looking hung. Generous enough to read and tap the dialog.
    connectivityManager.requestNetwork(request, callback, mainHandler, JOIN_TIMEOUT_MS)
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

  // Waits for the joined network to actually carry an address and a gateway before the join is
  // reported as done. Gives up after the allotted tries rather than failing: a usable link with no
  // readable gateway still works via the address from the QR code.
  private fun awaitLinkReady(network: Network, attempt: Int, maxAttempts: Int, onReady: () -> Unit) {
    val ready = joinedNetworkIp().isNotEmpty() && joinedNetworkGateway().isNotEmpty()
    if (ready || attempt >= maxAttempts) {
      if (!ready) Log.w(TAG, "joinHotspot: link not fully described after $attempt tries")
      onReady()
      return
    }
    mainHandler.postDelayed({ awaitLinkReady(network, attempt + 1, maxAttempts, onReady) }, 250)
  }

  private fun releaseJoinedNetwork() {
    try {
      joinCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
    } catch (e: Exception) {
      Log.w(TAG, "releaseJoinedNetwork: unregisterNetworkCallback threw", e)
    }
    joinCallback = null
    joinedNetwork = null
    joinedSsid = null
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

  // Two sources, both from the DHCP lease this phone just took from the host:
  //   - dhcpServerAddress (API 30+) names the server directly — on a hotspot that IS the host;
  //   - failing that, the default route's gateway, which on a hotspot subnet is the same device.
  // A LocalOnlyHotspot has no internet uplink, so a default route is not guaranteed to exist and
  // the DHCP server is the more reliable of the two where it is available.
  private fun joinedNetworkGateway(): String {
    val network = joinedNetwork ?: return ""
    return runCatching {
      val props = connectivityManager.getLinkProperties(network) ?: return@runCatching ""
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val dhcp = props.dhcpServerAddress
        if (dhcp != null && !dhcp.isAnyLocalAddress && !dhcp.isLoopbackAddress) {
          Log.i(TAG, "joinedNetworkGateway -> ${dhcp.hostAddress} (dhcp server)")
          return@runCatching dhcp.hostAddress ?: ""
        }
      }
      val gateway = props.routes
        .mapNotNull { it.gateway as? Inet4Address }
        .firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
      if (gateway != null) Log.i(TAG, "joinedNetworkGateway -> ${gateway.hostAddress} (route)")
      gateway?.hostAddress ?: ""
    }.getOrNull() ?: ""
  }
}
