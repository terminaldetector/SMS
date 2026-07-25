package expo.modules.bitchatble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE transport for BitChat interop — the one piece of the bridge that must be native.
 *
 * A BitChat node is simultaneously:
 *   - a PERIPHERAL: advertises the service UUID and runs a GATT server that peers write packets to,
 *     pushing its own packets back as notifications;
 *   - a CENTRAL: scans for that same UUID, connects, subscribes to notifications and writes packets.
 *
 * Both roles run at once, so two ChatterUI phones (or a ChatterUI phone and a real BitChat one) will
 * find each other whichever side happens to scan first.
 *
 * This module deliberately knows nothing about BitChat's packet format, Noise or fragmentation —
 * those live in TypeScript and are already covered by tests. Keeping the native side to "move these
 * bytes" is what makes the untestable part small.
 */
class BitchatBleModule : Module() {

  private val context: Context
    get() = requireNotNull(appContext.reactContext) { "no react context" }

  private val bluetoothManager: BluetoothManager?
    get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

  private val adapter: BluetoothAdapter?
    get() = bluetoothManager?.adapter

  // Peripheral side
  private var gattServer: BluetoothGattServer? = null
  private var serverCharacteristic: BluetoothGattCharacteristic? = null
  private var advertiseCallback: AdvertiseCallback? = null

  /** Centrals that subscribed to notifications, keyed by address — we can only notify these. */
  private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()

  // Central side
  private var scanCallback: ScanCallback? = null
  private val gattClients = ConcurrentHashMap<String, BluetoothGatt>()
  private val clientCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()

  /** Addresses we are mid-connect on, so a repeated scan hit doesn't open a second GATT. */
  private val connecting = Collections.synchronizedSet(mutableSetOf<String>())

  private var serviceUuid: UUID? = null
  private var characteristicUuid: UUID? = null

  // Standard Client Characteristic Configuration descriptor: without it, an Android central cannot
  // subscribe to our notifications.
  private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

  override fun definition() = ModuleDefinition {
    Name("BitchatBle")

    Events("onPacket", "onPeerConnected", "onPeerDisconnected")

    Function("isSupported") {
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter != null
    }

    // Plenty of chipsets can scan but not advertise; the caller needs to know before promising a
    // peer that it can be reached.
    Function("isPeripheralSupported") {
      adapter?.isMultipleAdvertisementSupported == true && adapter?.bluetoothLeAdvertiser != null
    }

    AsyncFunction("requestPermissions") { ->
      // Expo's permission flow is UI-side; here we only report whether the grants we need are in
      // place, so JS can surface a clear message rather than failing deep inside a BLE callback.
      hasPermissions()
    }

    AsyncFunction("startPeripheral") { service: String, characteristic: String ->
      requirePermissions()
      serviceUuid = UUID.fromString(service)
      characteristicUuid = UUID.fromString(characteristic)
      startGattServer() && startAdvertising()
    }

    AsyncFunction("stopPeripheral") { ->
      stopAdvertising()
      stopGattServer()
    }

    AsyncFunction("startCentral") { service: String, characteristic: String ->
      requirePermissions()
      serviceUuid = UUID.fromString(service)
      characteristicUuid = UUID.fromString(characteristic)
      startScan()
    }

    AsyncFunction("stopCentral") { ->
      stopScan()
      disconnectClients()
    }

    AsyncFunction("send") { peerId: String, data: ByteArray ->
      sendTo(peerId, data)
    }

    AsyncFunction("broadcast") { data: ByteArray ->
      // Union of both roles: a peer we connected to and a peer that connected to us are both
      // reachable, and either link is fine.
      val peers = HashSet<String>()
      peers.addAll(clientCharacteristics.keys)
      peers.addAll(subscribedCentrals.keys)
      peers.count { sendTo(it, data) }
    }

    Function("connectedPeers") {
      (clientCharacteristics.keys + subscribedCentrals.keys).distinct()
    }

    OnDestroy {
      stopAdvertising()
      stopGattServer()
      stopScan()
      disconnectClients()
    }
  }

  // --- permissions ---------------------------------------------------------------------------

  private fun granted(permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  private fun hasPermissions(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      granted(Manifest.permission.BLUETOOTH_SCAN) &&
        granted(Manifest.permission.BLUETOOTH_CONNECT) &&
        granted(Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
      granted(Manifest.permission.BLUETOOTH) &&
        granted(Manifest.permission.BLUETOOTH_ADMIN) &&
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

  private fun requirePermissions() {
    if (!hasPermissions()) throw BleException("Bluetooth permissions are not granted")
    if (adapter?.isEnabled != true) throw BleException("Bluetooth is off")
  }

  // --- peripheral ----------------------------------------------------------------------------

  @SuppressLint("MissingPermission") // guarded by requirePermissions()
  private fun startGattServer(): Boolean {
    stopGattServer()
    val manager = bluetoothManager ?: return false
    val server = manager.openGattServer(context, gattServerCallback) ?: return false

    // Mirrors BitChat's own characteristic: read + write + writeWithoutResponse + notify.
    val characteristic = BluetoothGattCharacteristic(
      characteristicUuid,
      BluetoothGattCharacteristic.PROPERTY_READ or
        BluetoothGattCharacteristic.PROPERTY_WRITE or
        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
      BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
    )
    characteristic.addDescriptor(
      BluetoothGattDescriptor(
        cccdUuid,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
      )
    )

    val gattService = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    gattService.addCharacteristic(characteristic)
    if (!server.addService(gattService)) {
      server.close()
      return false
    }

    gattServer = server
    serverCharacteristic = characteristic
    return true
  }

  @SuppressLint("MissingPermission")
  private fun stopGattServer() {
    subscribedCentrals.clear()
    serverCharacteristic = null
    gattServer?.let {
      runCatching { it.clearServices() }
      runCatching { it.close() }
    }
    gattServer = null
  }

  @SuppressLint("MissingPermission")
  private fun startAdvertising(): Boolean {
    stopAdvertising()
    val advertiser = adapter?.bluetoothLeAdvertiser ?: return false

    val settings = AdvertiseSettings.Builder()
      .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
      .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
      .setConnectable(true)
      .setTimeout(0) // advertise until told to stop
      .build()

    // The 31-byte advertisement is too small for a 128-bit UUID plus a name, and dropping the name
    // is what keeps the service UUID present — which is the only field peers actually filter on.
    val data = AdvertiseData.Builder()
      .setIncludeDeviceName(false)
      .setIncludeTxPowerLevel(false)
      .addServiceUuid(ParcelUuid(serviceUuid))
      .build()

    val callback = object : AdvertiseCallback() {
      override fun onStartFailure(errorCode: Int) {
        advertiseCallback = null
      }
    }
    return runCatching {
      advertiser.startAdvertising(settings, data, callback)
      advertiseCallback = callback
      true
    }.getOrDefault(false)
  }

  @SuppressLint("MissingPermission")
  private fun stopAdvertising() {
    advertiseCallback?.let { cb ->
      runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    }
    advertiseCallback = null
  }

  private val gattServerCallback = object : BluetoothGattServerCallback() {
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        subscribedCentrals.remove(device.address)
        emitPeer("onPeerDisconnected", device.address)
      }
      // A connect alone isn't useful yet: we can only push packets once the central subscribes,
      // so "connected" is reported from the descriptor write below.
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      characteristic: BluetoothGattCharacteristic,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray
    ) {
      if (characteristic.uuid == characteristicUuid) emitPacket(device.address, value)
      if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
      }
    }

    @SuppressLint("MissingPermission")
    override fun onDescriptorWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      descriptor: BluetoothGattDescriptor,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray
    ) {
      if (descriptor.uuid == cccdUuid) {
        val enabling = value.isNotEmpty() && value[0].toInt() != 0
        if (enabling) {
          subscribedCentrals[device.address] = device
          emitPeer("onPeerConnected", device.address)
        } else {
          subscribedCentrals.remove(device.address)
        }
      }
      if (responseNeeded) {
        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
      }
    }

    @SuppressLint("MissingPermission")
    override fun onCharacteristicReadRequest(
      device: BluetoothDevice,
      requestId: Int,
      offset: Int,
      characteristic: BluetoothGattCharacteristic
    ) {
      // Nothing is exposed by reading; packets arrive by write and leave by notify.
      gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
    }
  }

  // --- central -------------------------------------------------------------------------------

  @SuppressLint("MissingPermission")
  private fun startScan(): Boolean {
    stopScan()
    val scanner = adapter?.bluetoothLeScanner ?: return false

    val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
    val settings = ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .build()

    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        connectTo(result.device)
      }

      override fun onBatchScanResults(results: MutableList<ScanResult>) {
        results.forEach { connectTo(it.device) }
      }
    }
    return runCatching {
      scanner.startScan(filters, settings, callback)
      scanCallback = callback
      true
    }.getOrDefault(false)
  }

  @SuppressLint("MissingPermission")
  private fun stopScan() {
    scanCallback?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
    scanCallback = null
  }

  @SuppressLint("MissingPermission")
  private fun connectTo(device: BluetoothDevice) {
    val address = device.address
    // Scans repeat the same device constantly; without this guard every hit opens another GATT.
    if (clientCharacteristics.containsKey(address) || gattClients.containsKey(address)) return
    if (!connecting.add(address)) return

    val gatt = device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
    if (gatt == null) {
      connecting.remove(address)
      return
    }
    gattClients[address] = gatt
  }

  private val gattClientCallback = object : BluetoothGattCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      val address = gatt.device.address
      when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
          // Ask for the largest MTU first: BitChat packets pad to 512, and a 23-byte default would
          // fragment everything needlessly.
          if (!gatt.requestMtu(517)) gatt.discoverServices()
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          connecting.remove(address)
          clientCharacteristics.remove(address)
          gattClients.remove(address)?.let { runCatching { it.close() } }
          emitPeer("onPeerDisconnected", address)
        }
      }
    }

    @SuppressLint("MissingPermission")
    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
      gatt.discoverServices()
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      val address = gatt.device.address
      if (status != BluetoothGatt.GATT_SUCCESS) {
        runCatching { gatt.disconnect() }
        return
      }
      val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
      if (characteristic == null) {
        // Advertised the service but doesn't serve our characteristic — not a usable peer.
        runCatching { gatt.disconnect() }
        return
      }

      gatt.setCharacteristicNotification(characteristic, true)
      // setCharacteristicNotification is local only; the peer starts notifying only once its CCCD
      // is written. Missing this is the classic "connected but nothing ever arrives" bug.
      val cccd = characteristic.getDescriptor(cccdUuid)
      if (cccd != null) {
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          gatt.writeDescriptor(cccd, enable)
        } else {
          @Suppress("DEPRECATION")
          cccd.value = enable
          @Suppress("DEPRECATION")
          gatt.writeDescriptor(cccd)
        }
      }

      clientCharacteristics[address] = characteristic
      connecting.remove(address)
      emitPeer("onPeerConnected", address)
    }

    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray
    ) {
      if (characteristic.uuid == characteristicUuid) emitPacket(gatt.device.address, value)
    }

    @Deprecated("Pre-API-33 callback; the newer overload above carries the value directly.")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic
    ) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // handled above
      if (characteristic.uuid == characteristicUuid) {
        characteristic.value?.let { emitPacket(gatt.device.address, it) }
      }
    }
  }

  @SuppressLint("MissingPermission")
  private fun disconnectClients() {
    gattClients.values.forEach { runCatching { it.disconnect() }; runCatching { it.close() } }
    gattClients.clear()
    clientCharacteristics.clear()
    connecting.clear()
  }

  // --- sending -------------------------------------------------------------------------------

  @SuppressLint("MissingPermission")
  private fun sendTo(peerId: String, data: ByteArray): Boolean {
    // Prefer the central link (a plain write) — it reports success synchronously, whereas a notify
    // can be silently dropped when the stack's buffer is full.
    val characteristic = clientCharacteristics[peerId]
    val gatt = gattClients[peerId]
    if (characteristic != null && gatt != null) {
      return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          gatt.writeCharacteristic(
            characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
          ) == BluetoothGatt.GATT_SUCCESS
        } else {
          @Suppress("DEPRECATION")
          characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
          @Suppress("DEPRECATION")
          characteristic.value = data
          @Suppress("DEPRECATION")
          gatt.writeCharacteristic(characteristic)
        }
      }.getOrDefault(false)
    }

    // Otherwise the peer connected to us, so it is reachable by notification.
    val device = subscribedCentrals[peerId] ?: return false
    val server = gattServer ?: return false
    val serverChar = serverCharacteristic ?: return false
    return runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        server.notifyCharacteristicChanged(device, serverChar, false, data) == BluetoothGatt.GATT_SUCCESS
      } else {
        @Suppress("DEPRECATION")
        serverChar.value = data
        @Suppress("DEPRECATION")
        server.notifyCharacteristicChanged(device, serverChar, false)
      }
    }.getOrDefault(false)
  }

  // --- events --------------------------------------------------------------------------------

  private fun emitPacket(peerId: String, data: ByteArray) {
    sendEvent("onPacket", mapOf("peerId" to peerId, "data" to data))
  }

  private fun emitPeer(event: String, peerId: String) {
    sendEvent(event, mapOf("peerId" to peerId))
  }
}

private class BleException(message: String) : Exception(message)
