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
import ee.nekoko.remoble.ConsoleLog as Log
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

data class BleReaderSnapshot(
        val readerName: String,
        val displayName: String,
        val type: String,
        val address: String,
        val rssi: Int?,
        val remembered: Boolean,
        val connected: Boolean,
        val settling: Boolean
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

        private fun mapCla(cla: Byte, channel: Int): Byte {
            require(channel in 0..19) { "Invalid logical channel $channel" }
            val value = cla.toInt() and 0xff
            return if (channel < 4) {
                ((value and 0xfc) or channel).toByte()
            } else {
                ((value and 0xf0) or 0x40 or (channel - 4)).toByte()
            }
        }
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pinStore = BLEPinStore(context)
    private val foundReaders = ConcurrentHashMap<String, BleReader>()
    private val pendingRenamedReaders = ConcurrentHashMap<String, String>()
    private val settleExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val settleTasks =
            ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()
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

    val isScanning: Boolean
        get() = scanning

    fun readerSnapshots(): List<BleReaderSnapshot> =
            readers.mapNotNull { smartcardReader ->
                val reader = smartcardReader as? BleReader ?: return@mapNotNull null
                BleReaderSnapshot(
                        readerName = reader.name,
                        displayName = reader.displayName,
                        type = reader.type,
                        address = reader.device.address.uppercase(),
                        rssi = reader.lastRssi,
                        remembered = reader.remembered,
                        connected = reader.isProfileConnected(),
                        settling = reader.isSettling()
                )
            }

    fun savedPin(readerName: String): String =
            findReader(readerName)?.let { reader ->
                pinStore.load(reader.device.address, prefs, pinKey(reader))
            }.orEmpty()

    /**
     * Changes the six hexadecimal characters advertised after the fixed device prefix and the
     * six-character hexadecimal PIN. The command is intentionally limited to NBLE readers: B1
     * derives its authentication material from its advertised UID and has no equivalent command.
     */
    fun updateNbleCredentials(readerName: String, nameSuffix: String, pin: String): String {
        val reader = findReader(readerName) ?: error("Bluetooth reader not found")
        require(reader.type == BleProfileFactory.TYPE_NBLE) {
            "This Bluetooth reader does not support changing its name or PIN"
        }
        val newSuffix = nameSuffix.trim().uppercase()
        val newPin = pin.trim().uppercase()
        require(NbleAuth.isHexPin(newSuffix)) { "Enter 6 hexadecimal characters for the Bluetooth name" }
        require(NbleAuth.isHexPin(newPin)) { "Enter a 6-character hexadecimal PIN" }

        synchronized(reader.lock) {
            reader.settleNbleIfRequired()
            val active = reader.profile ?: reader.connectProfile()
            active.updateCredentials(newSuffix, newPin)

            val oldName = reader.displayName
            val prefix = oldName.dropLast(oldName.length.coerceAtMost(6))
            val newName = prefix + newSuffix

            // Match NekokoLPA2: keep the already authenticated transport alive after the accepted
            // management APDU. Forcing an immediate reconnect can race the writer's configuration
            // commit and leave its card application temporarily unavailable to SELECT AID.
            pinStore.save(reader.device.address, newPin)
            reader.displayName = newName
            pendingRenamedReaders[reader.device.address.uppercase()] = newName
            remember(reader)
            executor.execute { onReadersChanged() }
            return newName
        }
    }

    fun connectReader(readerName: String, pin: String? = null): SmartcardSession? {
        val reader = findReader(readerName) ?: return null
        reader.throwIfSettling()
        reader.disconnect()
        Thread.sleep(350)
        reader.connectExplicit(pin)
        return BleSession(reader)
    }

    fun setReaderEnabled(readerName: String, enabled: Boolean) {
        val reader = findReader(readerName) ?: return
        reader.enabledForLocalUse = enabled
        if (enabled) {
            if (!reader.remembered) remember(reader)
            if (reader.isSettling()) {
                scheduleSettleRecovery(reader, reader.settleRetryDelay())
            }
        } else {
            // Closing GATT is essential: many writers stop advertising while a central remains
            // connected, so another phone cannot discover them until this handle is released.
            cancelSettleRecovery(reader)
            reader.disconnect()
        }
        executor.execute { onReadersChanged() }
    }

    fun forgetReader(readerName: String) {
        val reader = findReader(readerName) ?: return
        cancelSettleRecovery(reader)
        reader.disconnect()
        reader.remembered = false
        removeRemembered(reader)
        pinStore.delete(reader.device.address)
        prefs.edit().remove(pinKey(reader)).apply()
        foundReaders.remove(reader.device.address.uppercase(), reader)
        executor.execute { onReadersChanged() }
    }

    fun currentRssi(readerName: String): Int? = findReader(readerName)?.lastRssi

    fun startScan(): Boolean {
        // On Android 12+, even querying adapter state requires BLUETOOTH_CONNECT.
        if (closed || scanning || !hasPermissions() || adapter?.isEnabled != true) return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        // This scanner is kept alive by the foreground bridge. Balanced mode remains responsive
        // enough for remembered readers while avoiding the controller/UI pressure of an
        // indefinite low-latency scan.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
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
        settleTasks.values.forEach { it.cancel(false) }
        settleTasks.clear()
        settleExecutor.shutdownNow()
        foundReaders.values.forEach { it.disconnect() }
        foundReaders.clear()
    }

    /**
     * Drops the current GATT/profile state and establishes a fresh authenticated session for a
     * reader that is already known to Remoble. This is intentionally explicit rather than hidden
     * inside APDU transmission: a rejected AID is a valid card response, so only the HTTP
     * open-channel fallback should decide that every candidate AID has failed.
     */
    suspend fun reconnectReader(readerName: String): SmartcardSession? {
        val reader = findReader(readerName) ?: return null
        Log.w(TAG, "Resetting GATT connection for $readerName")
        reader.disconnect()
        // Some Android Bluetooth stacks need a short quiet period after BluetoothGatt.close()
        // before the same peripheral can be connected again.
        kotlinx.coroutines.delay(500)
        return reader.openSession()
    }

    fun isReaderSettling(readerName: String): Boolean =
            findReader(readerName)?.isSettling() == true

    /**
     * Keeps card authorization tied to the BLE transport instead of the mutable display name.
     * A 9eSIM B0/B1 remains eligible after its name is changed through the management APDU.
     * The PSK service remains authoritative and may return no key for another compatible peer.
     */
    fun isNineEsimReader(readerName: String): Boolean =
            findReader(readerName)?.type in
                    setOf(BleProfileFactory.TYPE_NBLE, BleProfileFactory.TYPE_TYBLE)

    private fun findReader(readerName: String): BleReader? {
        foundReaders.values.firstOrNull { it.name == readerName }?.let { return it }
        val address = readerName.substringAfterLast('|', missingDelimiterValue = "")
        return address.takeIf { it.isNotEmpty() }?.let { foundReaders[it.uppercase()] }
    }

    private fun accept(result: ScanResult) {
        val advertised = result.scanRecord?.deviceName.orEmpty()
        val platform = runCatching { result.device.name.orEmpty() }.getOrDefault("")
        // Android can cache an older six-character 9eSIM name. TYBLE/B1
        // authentication needs the live advertised eight-digit UID.
        val scannedName = listOf(advertised, platform)
                .firstOrNull(TybleProtocol::isDeviceName)
                ?: platform.ifBlank { advertised }.ifBlank { "BLE eUICC" }
        val address = result.device.address.uppercase()
        val displayName = pendingRenamedReaders[address]?.let { expected ->
            if (scannedName.equals(expected, ignoreCase = true)) {
                pendingRenamedReaders.remove(address, expected)
                scannedName
            } else expected
        } ?: scannedName
        val services = result.scanRecord?.serviceUuids.orEmpty().map(ParcelUuid::getUuid).toSet()
        val type = BleProfileFactory.detect(displayName, services) ?: return

        // The advertised name can change and is not an identity. Keep the
        // RemoCard slot stable and unique by Bluetooth MAC.
        val readerName = "ble:$type|$address"
        val existing = foundReaders[address]
        if (existing == null) {
            foundReaders[address] = BleReader(
                    readerName, displayName, type, result.device, lastRssi = result.rssi
            )
            executor.execute { onReadersChanged() }
        } else if (existing.type != type ||
                (type == BleProfileFactory.TYPE_TYBLE && existing.displayName != displayName)) {
            // Migrate a previously remembered six-character/NBLE identity to
            // the live eight-digit B1 advertisement before authenticating.
            val wasRemembered = existing.remembered
            cancelSettleRecovery(existing)
            existing.disconnect()
            val replacement = BleReader(
                    readerName, displayName, type, result.device, lastRssi = result.rssi
            )
            foundReaders[address] = replacement
            if (wasRemembered) remember(replacement)
            executor.execute { onReadersChanged() }
        } else {
            if (existing.displayName != displayName && type == BleProfileFactory.TYPE_NBLE) {
                existing.displayName = displayName
                if (existing.remembered) remember(existing)
                executor.execute { onReadersChanged() }
            }
            existing.lastSeen = System.currentTimeMillis()
            existing.lastRssi = result.rssi
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
                val address = record.getString("address").uppercase()
                val displayName = record.getString("name")
                val storedType = record.getString("type")
                val type = BleProfileFactory.detect(displayName, emptySet()) ?: storedType
                val device = bluetoothAdapter.getRemoteDevice(address)
                val readerName = "ble:$type|$address"
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
        reader.remembered = true
        val current = prefs.getStringSet(PREF_REMEMBERED_READERS, emptySet()).orEmpty().toMutableSet()
        current.removeAll { encoded ->
            runCatching {
                JSONObject(encoded).optString("address")
                        .equals(reader.device.address, ignoreCase = true)
            }
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

    private fun removeRemembered(reader: BleReader) {
        val current = prefs.getStringSet(PREF_REMEMBERED_READERS, emptySet()).orEmpty().toMutableSet()
        current.removeAll { encoded ->
            runCatching {
                JSONObject(encoded).optString("address")
                        .equals(reader.device.address, ignoreCase = true)
            }.getOrDefault(false)
        }
        prefs.edit().putStringSet(PREF_REMEMBERED_READERS, current).apply()
    }

    /**
     * A proactive REFRESH starts with a mandatory silent period. NekokoLPA2's HTTP timeout is
     * shorter than that period, so the reader must advance its STATUS probes independently of a
     * later /openChannel request. Only one recovery task is active per physical reader.
     */
    private fun scheduleSettleRecovery(reader: BleReader, delayMs: Long) {
        val key = reader.device.address.uppercase()
        if (closed || !reader.enabledForLocalUse || foundReaders[key] !== reader) return
        val existing = settleTasks[key]
        if (existing != null && !existing.isDone) return

        val task = settleExecutor.schedule(
                {
                    settleTasks.remove(key)
                    if (closed || !reader.enabledForLocalUse ||
                            foundReaders[key] !== reader || !reader.isSettling()) {
                        return@schedule
                    }
                    try {
                        synchronized(reader.lock) { reader.settleNbleIfRequired() }
                        Log.i(TAG, "Background STATUS recovery completed for ${reader.name}")
                        executor.execute { onReadersChanged() }
                    } catch (settling: BleCardSettlingException) {
                        if (reader.isSettling()) {
                            scheduleSettleRecovery(
                                    reader,
                                    settling.retryAfterMillis.coerceAtLeast(100L)
                            )
                        } else {
                            executor.execute { onReadersChanged() }
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Background STATUS recovery failed for ${reader.name}", error)
                        if (reader.isSettling()) scheduleSettleRecovery(reader, 1_000L)
                    }
                },
                delayMs.coerceAtLeast(100L),
                java.util.concurrent.TimeUnit.MILLISECONDS
        )
        settleTasks[key] = task
    }

    private fun cancelSettleRecovery(reader: BleReader) {
        settleTasks.remove(reader.device.address.uppercase())?.cancel(false)
    }

    private fun pinKey(reader: BleReader): String =
            "nble.pin.${reader.device.address.uppercase().replace(":", "")}"

    private inner class BleReader(
            override val name: String,
            @Volatile var displayName: String,
            val type: String,
            val device: BluetoothDevice,
            @Volatile var remembered: Boolean = false,
            @Volatile var lastRssi: Int? = null
    ) : SmartcardReader {
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        @Volatile var enabledForLocalUse: Boolean = true
        val lock = Any()
        @Volatile var profile: BleProfile? = null
        @Volatile private var settleDeadlineMs = 0L
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

        // UI snapshots must never wait for a BLE handshake: connect/discover can block for up to
        // the GATT timeout and used to freeze scrolling when the screen refreshed.
        fun isProfileConnected(): Boolean = profile != null

        fun connectExplicit(pin: String?) = synchronized(lock) {
            settleNbleIfRequired()
            profile = connectProfile(pin?.trim()?.uppercase()?.takeIf(NbleAuth::isHexPin))
        }

        fun connectProfile(pinOverride: String? = null): BleProfile {
            val client = GattClient(context, device)
            val configuredPin = pinOverride
                    ?: pinStore.load(device.address, prefs, pinKey(this)).takeIf(NbleAuth::isHexPin)
            val newProfile = BleProfileFactory.create(type, client, displayName, configuredPin)
            try {
                newProfile.connect()
                remember(this)
                if (pinOverride != null) {
                    pinStore.save(device.address, pinOverride)
                }
                return newProfile
            } catch (error: Throwable) {
                runCatching { newProfile.close() }
                if (configuredPin != null && isPinFailure(error)) {
                    pinStore.delete(device.address)
                    prefs.edit().remove(pinKey(this)).apply()
                }
                throw error
            }
        }

        private fun isPinFailure(error: Throwable): Boolean {
            val message = generateSequence(error) { it.cause }
                    .joinToString(" ") { it.message.orEmpty().lowercase() }
            return listOf("pin", "password", "authenticate", "authentication", "6982", "63c")
                    .any(message::contains)
        }

        fun transceive(command: ByteArray): ByteArray = synchronized(lock) {
            val active = profile ?: error("BLE reader is not connected")
            try {
                val beeSimRefresh = type == BleProfileFactory.TYPE_BEESIM &&
                        BleProtocolCodec.nbleRequestsProactiveRefresh(command)
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
                if (beeSimRefresh && BleProtocolCodec.nbleCardState(response) in setOf(
                                BleProtocolCodec.NbleCardState.READY,
                                BleProtocolCodec.NbleCardState.PROACTIVE_REFRESH
                        ) && active.onProfileSwitchCommitted()) {
                    if (profile === active) profile = null
                    Log.i(TAG, "Reset BeeSIM ${name} after profile switch")
                }
                response
            } catch (error: Exception) {
                disconnect()
                throw error
            }
        }

        fun isSettling(): Boolean =
                type == BleProfileFactory.TYPE_NBLE && settleDeadlineMs != 0L

        fun settleRetryDelay(): Long = synchronized(lock) {
            (nextSettleProbeMs - System.currentTimeMillis()).coerceAtLeast(100L)
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
                scheduleSettleRecovery(this, NBLE_SETTLE_QUIET_MS)
            }
            runCatching { active.close() }
            if (profile === active) profile = null
        }

        /**
         * After the mandatory silent window, probe with bare STATUS only. Opening a logical
         * channel here would send SELECT while the modem is still resetting the card, which is
         * the sequence that leaves affected 9eSIM readers requiring a physical re-seat.
         */
        fun settleNbleIfRequired() {
            if (type != BleProfileFactory.TYPE_NBLE || settleDeadlineMs == 0L) return
            val now = System.currentTimeMillis()
            if (now < nextSettleProbeMs) throw settlingException(now)

            var probeProfile: BleProfile? = null
            try {
                probeProfile = connectProfile()
                // Match NekokoLPA2's control-APDU flow. 9eSIM commonly answers the first
                // STATUS with 6C39; that is a request to resend with Le=39, not a signal that
                // the modem is still uninitialised. Treating 6C39 as a terminal response kept
                // the reader in Recovering until every deadline expired.
                val response = transmitControl(probeProfile, NBLE_STATUS_APDU)
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

        /** Reassembles ISO 7816 response correction/chaining for the recovery STATUS probe. */
        private fun transmitControl(active: BleProfile, command: ByteArray): ByteArray {
            var current = command.copyOf()
            val output = java.io.ByteArrayOutputStream()
            repeat(8) {
                val response = active.transmit(current)
                require(response.size >= 2) { "Truncated STATUS response" }
                val sw1 = response[response.size - 2].toInt() and 0xff
                val sw2 = response.last().toInt() and 0xff
                when (sw1) {
                    0x61, 0x9f -> {
                        output.write(response, 0, response.size - 2)
                        current = byteArrayOf(current[0], 0xc0.toByte(), 0x00, 0x00, sw2.toByte())
                    }
                    0x6c -> {
                        require(current.isNotEmpty()) { "Invalid STATUS correction" }
                        current = current.copyOf().also { corrected ->
                            corrected[corrected.lastIndex] = sw2.toByte()
                        }
                    }
                    else -> {
                        output.write(response)
                        return output.toByteArray()
                    }
                }
            }
            error("STATUS response did not finish reassembling")
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
            // Match NekokoLPA2's BaseAdapter.openLogicalChannel path. A lost
            // HTTP session can leave a physical channel allocated even though
            // Remoble no longer has a handle for it.
            for (channel in 1..3) {
                runCatching {
                    transceiveControl(byteArrayOf(0x00, 0x70, 0x80.toByte(), channel.toByte(), 0x00))
                }
            }
            runCatching {
                transceiveControl("80AA00000AA908810082010183010700".hexToBytes())
            }

            var channelNumber = 0
            val manage = runCatching {
                transceiveControl(byteArrayOf(0x00, 0x70, 0x00, 0x00, 0x00))
            }.getOrNull()
            if (manage != null && manage.size >= 3 &&
                    manage[manage.size - 2] == 0x90.toByte() && manage.last() == 0x00.toByte()) {
                val allocated = manage[0].toInt() and 0xff
                if (allocated in 1..19) channelNumber = allocated
            }

            val select = BleProtocolCodec.selectAid(aid).also {
                it[0] = mapCla(it[0], channelNumber)
            }
            // SELECT may return 61xx. Consume GET RESPONSE here just as
            // NekokoLPA2's APDU transport does before reporting the channel
            // as usable to the remote client.
            val response = transceiveControl(select)
            if (response.size < 2) return null
            val sw1 = response[response.size - 2].toInt() and 0xff
            val sw2 = response.last().toInt() and 0xff
            if (BleProtocolCodec.nbleCardState(response) in setOf(
                            BleProtocolCodec.NbleCardState.PROACTIVE_REFRESH,
                            BleProtocolCodec.NbleCardState.SETTLING
                    )) {
                throw BleCardSettlingException(NBLE_SETTLE_QUIET_MS)
            }
            if ((sw1 == 0x90 && sw2 == 0x00) || sw1 == 0x61) {
                return BleChannel(this, reader, channelNumber)
            }
            if (channelNumber > 0) {
                runCatching {
                    reader.transceive(byteArrayOf(0x00, 0x70, 0x80.toByte(), channelNumber.toByte(), 0x00))
                }
            }
            return null
        }

        private fun transceiveControl(command: ByteArray): ByteArray {
            var current = command
            val output = java.io.ByteArrayOutputStream()
            repeat(8) {
                val response = reader.transceive(current)
                require(response.size >= 2) { "Truncated BLE APDU response" }
                val sw1 = response[response.size - 2].toInt() and 0xff
                val sw2 = response.last().toInt() and 0xff
                if (sw1 == 0x61) {
                    output.write(response, 0, response.size - 2)
                    current = byteArrayOf(current[0], 0xc0.toByte(), 0x00, 0x00, sw2.toByte())
                    return@repeat
                }
                if (sw1 == 0x6c) {
                    current = current.copyOf().also { corrected -> corrected[corrected.lastIndex] = sw2.toByte() }
                    return@repeat
                }
                output.write(response)
                return output.toByteArray()
            }
            error("BLE control response did not finish reassembling")
        }
    }

    private class BleChannel(
            override val session: SmartcardSession,
            private val reader: BleReader,
            private val channelNumber: Int
    ) : SmartcardChannel {
        @Volatile private var closed = false
        override fun transmit(command: ByteArray): ByteArray {
            check(!closed) { "BLE channel is closed" }
            val mapped = command.copyOf()
            if (mapped.isNotEmpty()) mapped[0] = mapCla(mapped[0], channelNumber)
            return reader.transceive(mapped)
        }
        override fun close() {
            if (closed) return
            closed = true
            if (channelNumber > 0) {
                runCatching {
                    reader.transceive(byteArrayOf(0x00, 0x70, 0x80.toByte(), channelNumber.toByte(), 0x00))
                }
            }
        }
    }

}
