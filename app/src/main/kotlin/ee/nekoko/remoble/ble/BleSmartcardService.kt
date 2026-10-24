package ee.nekoko.remoble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import ee.nekoko.remoble.omapi.SmartcardChannel
import ee.nekoko.remoble.omapi.SmartcardReader
import ee.nekoko.remoble.omapi.SmartcardService
import ee.nekoko.remoble.omapi.SmartcardSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import org.json.JSONObject

/** BLE-central smart-card backend exposed through the existing RemoCard HTTP API. */
@SuppressLint("MissingPermission")
class BleSmartcardService(
        private val context: Context,
        private val executor: Executor,
        private val onReadersChanged: () -> Unit = {}
) : SmartcardService {
    companion object {
        private const val TAG = "RemobleBle"
        private const val SEEN_TTL_MS = 60_000L
        private const val PREFS_NAME = "remoble_prefs"
        private const val PREF_REMEMBERED_READERS = "ble_remembered_readers"
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val foundReaders = ConcurrentHashMap<String, BleReader>()
    @Volatile private var scanning = false
    @Volatile private var closed = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = accept(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::accept)
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    init {
        loadRememberedReaders()
        startScan()
    }

    override val readers: Array<SmartcardReader>
        get() {
            if (!scanning && !closed) startScan()
            val cutoff = System.currentTimeMillis() - SEEN_TTL_MS
            return foundReaders.values
                    .filter { it.remembered || it.lastSeen >= cutoff }
                    .sortedBy { it.name }
                    .toTypedArray()
        }

    override val isConnected: Boolean
        get() = !closed && hasPermissions() && adapter?.isEnabled == true

    fun startScan(): Boolean {
        // On Android 12+, even querying adapter state requires BLUETOOTH_CONNECT.
        if (closed || scanning || !hasPermissions() || adapter?.isEnabled != true) return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(null, settings, callback)
            scanning = true
            true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start BLE scan", error)
            false
        }
    }

    override fun shutdown() {
        closed = true
        if (scanning && hasPermissions()) runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
        foundReaders.values.forEach { it.disconnect() }
        foundReaders.clear()
    }

    /**
     * Drops the current GATT/profile state and establishes a fresh authenticated session for a
     * reader that is already known to Remoble. This is intentionally explicit rather than hidden
     * inside APDU transmission: a rejected AID is a valid card response, so only the HTTP
     * open-channel fallback should decide that every candidate AID has failed.
     */
    fun reconnectReader(readerName: String): SmartcardSession? {
        val reader = foundReaders.values.firstOrNull { it.name == readerName } ?: return null
        Log.w(TAG, "Resetting GATT connection for $readerName")
        reader.disconnect()
        // Some Android Bluetooth stacks need a short quiet period after BluetoothGatt.close()
        // before the same peripheral can be connected again.
        Thread.sleep(500)
        return reader.openSession()
    }

    private fun accept(result: ScanResult) {
        val advertised = result.scanRecord?.deviceName.orEmpty()
        val platform = runCatching { result.device.name.orEmpty() }.getOrDefault("")
        val displayName = platform.ifBlank { advertised }.ifBlank { "BLE eUICC" }
        val services = result.scanRecord?.serviceUuids.orEmpty().map(ParcelUuid::getUuid).toSet()
        val type = BleProfileFactory.detect(displayName, services) ?: return

        val address = result.device.address
        val readerName = "ble:$type:$displayName|$address"
        val existing = foundReaders[address]
        if (existing == null) {
            foundReaders[address] = BleReader(readerName, displayName, type, result.device)
            executor.execute { onReadersChanged() }
        } else {
            existing.lastSeen = System.currentTimeMillis()
        }
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadRememberedReaders() {
        val bluetoothAdapter = adapter ?: return
        prefs.getStringSet(PREF_REMEMBERED_READERS, emptySet()).orEmpty().forEach { encoded ->
            try {
                val record = JSONObject(encoded)
                val address = record.getString("address")
                val displayName = record.getString("name")
                val storedType = record.getString("type")
                val type = BleProfileFactory.detect(displayName, emptySet()) ?: storedType
                val device = bluetoothAdapter.getRemoteDevice(address)
                val readerName = "ble:$type:$displayName|$address"
                foundReaders[address] = BleReader(
                        readerName,
                        displayName,
                        type,
                        device,
                        remembered = true
                )
            } catch (error: Exception) {
                Log.w(TAG, "Ignoring invalid remembered BLE reader", error)
            }
        }
    }

    private fun remember(reader: BleReader) {
        if (reader.remembered) return
        reader.remembered = true
        val current = prefs.getStringSet(PREF_REMEMBERED_READERS, emptySet()).orEmpty().toMutableSet()
        current.removeAll { encoded ->
            runCatching { JSONObject(encoded).optString("address") == reader.device.address }
                    .getOrDefault(false)
        }
        current += JSONObject()
                .put("address", reader.device.address)
                .put("name", reader.displayName)
                .put("type", reader.type)
                .toString()
        prefs.edit().putStringSet(PREF_REMEMBERED_READERS, current).apply()
        Log.i(TAG, "Remembered BLE reader ${reader.name}")
    }

    private inner class BleReader(
            override val name: String,
            val displayName: String,
            val type: String,
            val device: BluetoothDevice,
            @Volatile var remembered: Boolean = false
    ) : SmartcardReader {
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        private val lock = Any()
        private var profile: BleProfile? = null

        override val isSecureElementPresent: Boolean
            get() = remembered || System.currentTimeMillis() - lastSeen < SEEN_TTL_MS

        override fun openSession(): SmartcardSession {
            synchronized(lock) {
                if (profile == null) {
                    val client = GattClient(context, device)
                    val configuredPin = prefs.getString(NbleAuth.PREF_KEY, null)
                            ?.takeIf(NbleAuth::isHexPin)
                    val newProfile =
                            BleProfileFactory.create(type, client, displayName, configuredPin)
                    try {
                        newProfile.connect()
                        profile = newProfile
                        remember(this)
                    } catch (error: Throwable) {
                        runCatching { newProfile.close() }
                        throw error
                    }
                }
            }
            return BleSession(this)
        }

        fun transceive(command: ByteArray): ByteArray = synchronized(lock) {
            val active = profile ?: error("BLE reader is not connected")
            try {
                active.transmit(command)
            } catch (error: Exception) {
                disconnect()
                throw error
            }
        }

        fun disconnect() = synchronized(lock) {
            runCatching { profile?.close() }
            profile = null
        }
    }

    private class BleSession(override val reader: BleReader) : SmartcardSession {
        @Volatile private var closed = false
        override val isClosed: Boolean get() = closed
        override fun close() { closed = true }

        override fun openLogicalChannel(aid: ByteArray): SmartcardChannel? {
            check(!closed) { "BLE session is closed" }
            val response = reader.transceive(BleProtocolCodec.selectAid(aid))
            if (response.size < 2) return null
            val sw1 = response[response.size - 2].toInt() and 0xff
            val sw2 = response.last().toInt() and 0xff
            return if ((sw1 == 0x90 && sw2 == 0x00) || sw1 == 0x61) BleChannel(this, reader) else null
        }
    }

    private class BleChannel(
            override val session: SmartcardSession,
            private val reader: BleReader
    ) : SmartcardChannel {
        @Volatile private var closed = false
        override fun transmit(command: ByteArray): ByteArray {
            check(!closed) { "BLE channel is closed" }
            return reader.transceive(command)
        }
        override fun close() { closed = true }
    }
}
