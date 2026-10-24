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

internal class BleCardSettlingException(val retryAfterMillis: Long) : IllegalStateException(
        "9eSIM is applying the profile switch; retry in ${retryAfterMillis.coerceAtLeast(0)} ms"
)

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
        private const val NBLE_SETTLE_QUIET_MS = 20_000L
        private const val NBLE_SETTLE_MAX_MS = 150_000L
        private const val NBLE_SETTLE_INITIAL_PROBE_GAP_MS = 5_000L
        private const val NBLE_SETTLE_MAX_PROBE_GAP_MS = 20_000L
        private val NBLE_STATUS_APDU = byteArrayOf(
                0x80.toByte(), 0xF2.toByte(), 0x00, 0x00, 0x00
        )
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
        reader.throwIfSettling()
        Log.w(TAG, "Resetting GATT connection for $readerName")
        reader.disconnect()
        // Some Android Bluetooth stacks need a short quiet period after BluetoothGatt.close()
        // before the same peripheral can be connected again.
        Thread.sleep(500)
        return reader.openSession()
    }

    fun isReaderSettling(readerName: String): Boolean =
            foundReaders.values.firstOrNull { it.name == readerName }?.isSettling() == true

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
        private var settleDeadlineMs = 0L
        private var nextSettleProbeMs = 0L
        private var settleProbeGapMs = NBLE_SETTLE_INITIAL_PROBE_GAP_MS
        private var profileSwitchRefreshPending = false

        override val isSecureElementPresent: Boolean
            get() = remembered || System.currentTimeMillis() - lastSeen < SEEN_TTL_MS

        override fun openSession(): SmartcardSession {
            synchronized(lock) {
                settleNbleIfRequired()
                if (profile == null) {
                    profile = connectProfile()
                }
            }
            return BleSession(this)
        }

        private fun connectProfile(): BleProfile {
            val client = GattClient(context, device)
            val configuredPin = prefs.getString(NbleAuth.PREF_KEY, null)
                    ?.takeIf(NbleAuth::isHexPin)
            val newProfile = BleProfileFactory.create(type, client, displayName, configuredPin)
            try {
                newProfile.connect()
                remember(this)
                return newProfile
            } catch (error: Throwable) {
                runCatching { newProfile.close() }
                throw error
            }
        }

        fun transceive(command: ByteArray): ByteArray = synchronized(lock) {
            val active = profile ?: error("BLE reader is not connected")
            try {
                if (type == BleProfileFactory.TYPE_NBLE &&
                        BleProtocolCodec.nbleRequestsProactiveRefresh(command)) {
                    profileSwitchRefreshPending = true
                }
                val response = active.transmit(command)
                if (type == BleProfileFactory.TYPE_NBLE) {
                    val state = BleProtocolCodec.nbleCardState(response)
                    when (state) {
                        BleProtocolCodec.NbleCardState.PROACTIVE_REFRESH,
                        BleProtocolCodec.NbleCardState.SETTLING -> {
                            profileSwitchRefreshPending = false
                            beginNbleSettle(active)
                        }
                        BleProtocolCodec.NbleCardState.READY -> {
                            if (profileSwitchRefreshPending) {
                                profileSwitchRefreshPending = false
                                beginNbleSettle(active)
                            }
                        }
                        BleProtocolCodec.NbleCardState.OTHER -> {
                            val sw1 = response.getOrNull(response.size - 2)
                                    ?.toInt()?.and(0xff)
                            if (sw1 != 0x61) profileSwitchRefreshPending = false
                        }
                    }
                }
                response
            } catch (error: Exception) {
                disconnect()
                throw error
            }
        }

        fun isSettling(): Boolean = synchronized(lock) {
            type == BleProfileFactory.TYPE_NBLE && settleDeadlineMs != 0L
        }

        fun throwIfSettling() = synchronized(lock) {
            if (type == BleProfileFactory.TYPE_NBLE && settleDeadlineMs != 0L) {
                throw settlingException()
            }
        }

        private fun beginNbleSettle(active: BleProfile) {
            val now = System.currentTimeMillis()
            if (settleDeadlineMs == 0L) {
                settleDeadlineMs = now + NBLE_SETTLE_MAX_MS
                nextSettleProbeMs = now + NBLE_SETTLE_QUIET_MS
                settleProbeGapMs = NBLE_SETTLE_INITIAL_PROBE_GAP_MS
                Log.i(TAG, "Releasing ${name} for the handset's proactive REFRESH")
            }
            runCatching { active.close() }
            if (profile === active) profile = null
        }

        /**
         * After the mandatory silent window, probe with bare STATUS only. Opening a logical
         * channel here would send SELECT while the modem is still resetting the card, which is
         * the sequence that leaves affected 9eSIM readers requiring a physical re-seat.
         */
        private fun settleNbleIfRequired() {
            if (type != BleProfileFactory.TYPE_NBLE || settleDeadlineMs == 0L) return
            val now = System.currentTimeMillis()
            if (now < nextSettleProbeMs) throw settlingException(now)

            var probeProfile: BleProfile? = null
            try {
                probeProfile = connectProfile()
                val response = probeProfile.transmit(NBLE_STATUS_APDU)
                if (BleProtocolCodec.nbleCardState(response) == BleProtocolCodec.NbleCardState.READY) {
                    Log.i(TAG, "9eSIM ${name} reported idle after profile switch")
                    settleDeadlineMs = 0L
                    nextSettleProbeMs = 0L
                    settleProbeGapMs = NBLE_SETTLE_INITIAL_PROBE_GAP_MS
                    profile = probeProfile
                    return
                }
                Log.i(TAG, "9eSIM ${name} is still settling (STATUS=${response.toHex()})")
            } catch (error: Exception) {
                Log.i(TAG, "9eSIM ${name} settle probe did not complete: ${error.message}")
            } finally {
                if (profile !== probeProfile) runCatching { probeProfile?.close() }
            }

            if (now >= settleDeadlineMs) {
                Log.w(TAG, "9eSIM ${name} did not report idle before the settle deadline")
                settleDeadlineMs = 0L
                nextSettleProbeMs = 0L
                settleProbeGapMs = NBLE_SETTLE_INITIAL_PROBE_GAP_MS
            } else {
                nextSettleProbeMs = System.currentTimeMillis() + settleProbeGapMs
                settleProbeGapMs = (settleProbeGapMs * 2).coerceAtMost(NBLE_SETTLE_MAX_PROBE_GAP_MS)
            }
            throw settlingException()
        }

        private fun settlingException(now: Long = System.currentTimeMillis()): BleCardSettlingException =
                BleCardSettlingException((nextSettleProbeMs - now).coerceAtLeast(0L))

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
            if (BleProtocolCodec.nbleCardState(response) in setOf(
                            BleProtocolCodec.NbleCardState.PROACTIVE_REFRESH,
                            BleProtocolCodec.NbleCardState.SETTLING
                    )) {
                throw BleCardSettlingException(NBLE_SETTLE_QUIET_MS)
            }
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
