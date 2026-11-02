package ee.nekoko.remoble.ble

import android.bluetooth.BluetoothGattCharacteristic
import org.json.JSONObject
import java.util.UUID
import java.util.Timer
import java.util.TimerTask

internal interface BleProfile : AutoCloseable {
    fun connect()
    fun transmit(apdu: ByteArray): ByteArray
    fun updateCredentials(nameSuffix: String, password: String) {
        error("This BLE reader does not support changing its name or PIN")
    }
    fun onProfileSwitchCommitted(): Boolean = false
}

internal object BleProfileFactory {
    const val TYPE_NBLE = "nble"
    const val TYPE_TYBLE = "tyble"
    const val TYPE_SIMLINK = "simlink"
    const val TYPE_RED = "red"
    const val TYPE_RED_2 = "red2"
    const val TYPE_BEESIM = "beesim"
    private val NUS_SERVICE = uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val TYBLE_SERVICE = uuid("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    private val RED_SERVICE = uuid16("4553")
    private val BEESIM_SERVICE = uuid16("ae30")
    private val NINE_ESIM_NAME = Regex("^9esim[0-9a-f]{6}$", RegexOption.IGNORE_CASE)

    fun supports(name: String, advertisedServices: Set<UUID>): Boolean {
        return detect(name, advertisedServices) != null
    }

    fun detect(name: String, advertisedServices: Set<UUID>): String? {
        val lower = name.lowercase()
        return when {
            TybleProtocol.isDeviceName(name) || TYBLE_SERVICE in advertisedServices -> TYPE_TYBLE
            lower.contains("beesim") || BEESIM_SERVICE in advertisedServices -> TYPE_BEESIM
            lower.contains("estkme red") -> TYPE_RED_2
            NINE_ESIM_NAME.matches(name.trim()) || lower.contains("glocal") -> TYPE_NBLE
            lower.contains("estkme") || RED_SERVICE in advertisedServices -> TYPE_RED
            lower.contains("esim_writer") || NUS_SERVICE in advertisedServices -> TYPE_SIMLINK
            else -> null
        }
    }

    fun create(
            type: String,
            client: GattClient,
            displayName: String,
            configuredPin: String?
    ): BleProfile = when (type) {
        TYPE_RED_2 -> RedCcidProfile(client)
        TYPE_RED -> RedProfile(client)
        TYPE_BEESIM -> BeeSimProfile(client)
        TYPE_NBLE -> NbleProfile(client, displayName, configuredPin)
        TYPE_TYBLE -> TybleProfile(client, displayName)
        TYPE_SIMLINK -> SimLinkProfile(client)
        else -> error("Unsupported BLE reader type: $type")
    }

    fun uuid(value: String): UUID = UUID.fromString(value)
    fun uuid16(value: String): UUID = uuid("0000${value.lowercase()}-0000-1000-8000-00805f9b34fb")
}

private class TybleProfile(
        private val client: GattClient,
        private val displayName: String
) : BleProfile {
    private val serviceUuid = BleProfileFactory.uuid("49535343-fe7d-4ae5-8fa9-9fafd205e455")
    private val notifyUuid = BleProfileFactory.uuid("49535343-1e4d-4bd9-ba61-23c647249616")
    private val writeUuid = BleProfileFactory.uuid("49535343-8841-43f4-a8d4-ecbe34729bb3")
    private lateinit var tx: BluetoothGattCharacteristic

    override fun connect() {
        client.connect()
        val service = client.service(serviceUuid)
        tx = service.getCharacteristic(writeUuid) ?: error("TYBLE write characteristic not found")
        val rx = service.getCharacteristic(notifyUuid) ?: error("TYBLE notify characteristic not found")
        client.clearNotifications()
        client.enableNotifications(rx)
        val response = exchange(TybleProtocol.authenticationApdu(displayName), 10)
        when (response.toHex().uppercase()) {
            "9000" -> Unit
            "6982" -> error("TYBLE authentication rejected (SW=6982)")
            else -> error("TYBLE authentication failed (SW=${response.toHex().uppercase()})")
        }
    }

    @Synchronized
    override fun transmit(apdu: ByteArray): ByteArray = exchange(apdu, 100)

    private fun exchange(payload: ByteArray, timeoutSeconds: Long): ByteArray {
        val frame = client.exchange(
                tx,
                TybleProtocol.frame(payload),
                TybleProtocol::frameLength,
                timeoutSeconds
        )
        return TybleProtocol.payload(frame)
    }

    override fun close() = client.close()
}

internal class BeeSimCommandPacer(
        private val sleeper: (Long) -> Unit = Thread::sleep
) {
    companion object {
        const val COMMANDS_PER_BURST = 21
        const val COOLDOWN_MS = 1_200L
    }

    private var commandsSinceCooldown = 0

    fun beforeCommand(): Boolean {
        if (commandsSinceCooldown >= COMMANDS_PER_BURST) {
            sleeper(COOLDOWN_MS)
            commandsSinceCooldown = 1
            return true
        }
        commandsSinceCooldown++
        return false
    }

    fun reset() {
        commandsSinceCooldown = 0
    }
}

private class BeeSimProfile(private val client: GattClient) : BleProfile {
    companion object {
        private val TX_UUID = BleProfileFactory.uuid16("ae01")
        private val RX_UUID = BleProfileFactory.uuid16("ae02")
        private val SET_POWER = "A03E040000".hexToBytes()
        private val RESET = "A03F000000".hexToBytes()
        private const val MAX_FRAME_LENGTH = 244
    }

    private lateinit var tx: BluetoothGattCharacteristic
    private val pacer = BeeSimCommandPacer()

    override fun connect() {
        client.connect()
        val characteristics = client.characteristics()
        fun BluetoothGattCharacteristic.canWrite(): Boolean =
                properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                        properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        fun BluetoothGattCharacteristic.canNotify(): Boolean =
                properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                        properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

        tx = characteristics.firstOrNull { it.uuid == TX_UUID && it.canWrite() }
                ?: characteristics.firstOrNull { it.canWrite() }
                ?: error("BeeSIM write characteristic not found")
        val rx = characteristics.firstOrNull { it.uuid == RX_UUID && it.canNotify() }
                ?: characteristics.firstOrNull { it !== tx && it.canNotify() }
                ?: error("BeeSIM notify characteristic not found")

        client.clearNotifications()
        client.enableNotifications(rx)
        Thread.sleep(200)
        pacer.reset()
        exchange(SET_POWER)
    }

    @Synchronized
    override fun transmit(apdu: ByteArray): ByteArray = exchange(apdu)

    private fun exchange(command: ByteArray): ByteArray {
        pacer.beforeCommand()
        client.clearNotifications()
        writeFrames(command)

        val frames = mutableListOf<ByteArray>()
        var expectedTotal = -1
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(100)
        while (expectedTotal < 0 || frames.size < expectedTotal) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw java.util.concurrent.TimeoutException("BeeSIM response timeout")
            val remainingSeconds = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toSeconds(remaining).coerceAtLeast(1)
            val frame = client.awaitNotification(remainingSeconds)
            require(frame.size >= 2) { "Truncated BeeSIM response frame" }
            val total = frame[0].toInt() and 0xff
            val index = frame[1].toInt() and 0xff
            if (expectedTotal < 0) expectedTotal = total
            require(total == expectedTotal && index == frames.size + 1) {
                "BeeSIM response frame mismatch"
            }
            frames += frame
        }
        return BleProtocolCodec.decodeBeeSimFrames(frames)
    }

    private fun writeFrames(command: ByteArray) {
        val frameLength = (client.mtu - 3).coerceIn(20, MAX_FRAME_LENGTH)
        val framePayload = frameLength - 2
        val frames = BleProtocolCodec.beeSimFrames(command, framePayload)
        frames.forEachIndexed { index, frame ->
            // A BeeSIM frame must be delivered by one ATT write. Splitting after adding the
            // [total,index] header makes the peripheral interpret the fragments as new frames.
            client.writeValue(tx, frame, maxChunkSize = frameLength)
            val usesWriteResponse =
                    tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            if (usesWriteResponse && index < frames.lastIndex) Thread.sleep(10)
        }
    }

    override fun onProfileSwitchCommitted(): Boolean {
        runCatching {
            pacer.beforeCommand()
            client.clearNotifications()
            writeFrames(RESET)
        }
        // The writer normally drops the link while rebooting. Release the Android GATT handle
        // immediately so the next Nekoko retry creates a genuinely fresh connection.
        client.close()
        return true
    }

    override fun close() = client.close()
}

private class NbleProfile(
        private val client: GattClient,
        private val displayName: String,
        configuredPin: String?
) : BleProfile {
    private val serviceUuid = BleProfileFactory.uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val txUuid = BleProfileFactory.uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val rxUuid = BleProfileFactory.uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val pin = configuredPin?.trim()?.takeIf { it.isNotEmpty() }
            ?: NbleAuth.defaultPasswordForDeviceName(displayName)
    private lateinit var tx: BluetoothGattCharacteristic
    private var nextCounter = 0
    private val idleTimer = Timer("remoble-nble-idle", true)
    private var idleTask: TimerTask? = null
    @Volatile private var closed = false

    override fun connect() {
        var switchedMode = false
        repeat(3) { attempt ->
            try {
                connectAndAuthenticate()
                val mode = runCatching { transmit("FE0D000001".hexToBytes()) }
                        .getOrNull()
                        ?.firstOrNull()
                        ?.toInt()
                        ?.and(0xff)
                        ?: 0
                if (mode == 1 && !switchedMode) {
                    runCatching { transmit("FE0C020001".hexToBytes()) }
                    client.close()
                    switchedMode = true
                    Thread.sleep(4_000)
                } else {
                    return
                }
            } catch (error: Throwable) {
                client.close()
                if (error.message?.contains("wrong BLE PIN", ignoreCase = true) == true || attempt == 2) {
                    throw error
                }
                Thread.sleep(700L * (attempt + 1))
            }
        }
        error("Unable to initialize NBLE reader")
    }

    private fun connectAndAuthenticate() {
        client.connect()
        val service = client.service(serviceUuid)
        tx = service.getCharacteristic(txUuid) ?: error("NBLE TX characteristic not found")
        val rx = service.getCharacteristic(rxUuid) ?: error("NBLE RX characteristic not found")
        client.clearNotifications()
        client.enableNotifications(rx)
        authenticate()
    }

    private fun authenticate() {
        val challenge = client.awaitNotification(5) { value ->
            value.size >= 17 && value[0] == 0xE0.toByte()
        }
        val key = NbleAuth.buildTripleDesKey(displayName, pin)
        val plain = NbleAuth.transform(challenge.copyOfRange(1, 17), key, decrypt = true)
        val counter = (((plain[8].toInt() and 0xff) shl 8) or (plain[7].toInt() and 0xff)) + 1
        plain[7] = counter.toByte()
        plain[8] = (counter ushr 8).toByte()
        val response = byteArrayOf(0xE0.toByte()) + NbleAuth.transform(plain, key, decrypt = false)
        client.writeValue(tx, response, maxChunkSize = 180)

        val acknowledgement = client.awaitNotification(5) { value ->
            value.size >= 3 && value[0] == 0xE0.toByte() &&
                    (value[1] == 0x90.toByte() || value[1] == 0x6A.toByte())
        }
        val status = "%02X%02X".format(
                acknowledgement[1].toInt() and 0xff,
                acknowledgement[2].toInt() and 0xff
        )
        when (status) {
            "9000" -> nextCounter = 0
            "6A86" -> error("Wrong BLE PIN; enter the 6-digit hexadecimal PIN and restart the server")
            else -> error("NBLE authentication failed (SW=$status)")
        }
    }

    @Synchronized
    override fun transmit(apdu: ByteArray): ByteArray {
        idleTask?.cancel()
        try {
            val counter = nextCounter
            nextCounter = (nextCounter + 1) and 0xff
            client.clearNotifications()
            BleProtocolCodec.nbleFrames(counter, apdu).forEach { frame ->
                client.writeValue(tx, frame, maxChunkSize = 101)
            }

            val responseFrames = mutableListOf<ByteArray>()
            do {
                val frame = client.awaitNotification(30)
                responseFrames += frame
            } while ((frame[0].toInt() and 0x20) == 0)

            val response = BleProtocolCodec.decodeNbleFrames(responseFrames)
            check(response.counter == counter) {
                "NBLE response counter mismatch: expected $counter, received ${response.counter}"
            }
            return response.apdu
        } finally {
            scheduleIdlePing()
        }
    }

    @Synchronized
    override fun updateCredentials(nameSuffix: String, password: String) {
        var response = transmit(NbleAuth.credentialUpdateApdu(0x30, nameSuffix, password))
        if (response.toHex().uppercase().endsWith("6E00")) {
            response = transmit(NbleAuth.credentialUpdateApdu(0x80, nameSuffix, password))
        }
        val status = response.toHex().uppercase()
        check(status.endsWith("9000") || status.endsWith("6100") || status.contains("9000")) {
            "Name/PIN update failed (response=$status)"
        }
    }

    private fun scheduleIdlePing() {
        idleTask?.cancel()
        if (closed) return
        idleTask = object : TimerTask() {
            override fun run() {
                if (!closed) runCatching { transmit("99980000FB0000".hexToBytes()) }
            }
        }.also { idleTimer.schedule(it, 12_000) }
    }

    override fun close() {
        closed = true
        idleTask?.cancel()
        idleTimer.cancel()
        client.close()
    }
}

private class SimLinkProfile(private val client: GattClient) : BleProfile {
    private val serviceUuid = BleProfileFactory.uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val txUuid = BleProfileFactory.uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val rxUuid = BleProfileFactory.uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private lateinit var tx: BluetoothGattCharacteristic

    override fun connect() {
        client.connect()
        val service = client.service(serviceUuid)
        tx = service.getCharacteristic(txUuid) ?: error("SimLink TX characteristic not found")
        val rx = service.getCharacteristic(rxUuid) ?: error("SimLink RX characteristic not found")
        client.enableNotifications(rx)
        runCatching { command(JSONObject().put("cmd", "APDU").put("action", 2)) }
        command(JSONObject().put("cmd", "APDU").put("action", 0))
    }

    override fun transmit(apdu: ByteArray): ByteArray {
        var command = apdu
        if (apdu.size == 5 && (apdu[1].toInt() and 0xff) == 0xc0) {
            val le = apdu[4].toInt() and 0xff
            if (le == 0 || le > 0x4f) command = apdu.copyOf().also { it[4] = 0x4f }
        }
        val request = JSONObject().put("cmd", "APDU").put("data", command.toHex()).put("action", 1)
        val response = command(request, allowChannelRecovery = true)
        return response.optString("data").hexToBytes()
    }

    private fun command(json: JSONObject, allowChannelRecovery: Boolean = false): JSONObject {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val response = client.exchange(
                tx,
                bytes,
                expectedLength = { current ->
                    try {
                        JSONObject(current.toString(Charsets.UTF_8))
                        current.size
                    } catch (_: Exception) { null }
                }
        )
        val result = JSONObject(response.toString(Charsets.UTF_8))
        if (result.has("error")) {
            val errorMessage = result.get("error").toString()
            if (allowChannelRecovery && errorMessage.contains("channel not open", ignoreCase = true)) {
                command(JSONObject().put("cmd", "APDU").put("action", 0))
                return command(json)
            }
            error(errorMessage)
        }
        return result
    }

    override fun close() = client.close()
}

private class RedProfile(private val client: GattClient) : BleProfile {
    private lateinit var tx: BluetoothGattCharacteristic

    override fun connect() {
        client.connect()
        val service = client.service(BleProfileFactory.uuid16("4553"))
        tx = service.getCharacteristic(BleProfileFactory.uuid16("6d65"))
                ?: error("RED TX characteristic not found")
        val rx = service.getCharacteristic(BleProfileFactory.uuid16("544b"))
                ?: error("RED RX characteristic not found")
        client.enableNotifications(rx)
        exchange(0x02, "ESTKme".toByteArray(Charsets.US_ASCII))
        exchange(0x03, byteArrayOf(0x01, 0x01))
    }

    override fun transmit(apdu: ByteArray): ByteArray {
        val response = exchange(0x04, apdu)
        require(response.size >= 3) { "Invalid RED response" }
        return response.copyOfRange(3, response.size)
    }

    private fun exchange(command: Int, payload: ByteArray): ByteArray =
            client.exchange(tx, BleProtocolCodec.redFrame(command, payload), BleProtocolCodec::redFrameLength)

    override fun close() = client.close()
}

private class RedCcidProfile(private val client: GattClient) : BleProfile {
    private lateinit var tx: BluetoothGattCharacteristic

    override fun connect() {
        client.connect()
        val service = client.service(BleProfileFactory.uuid16("4553"))
        tx = service.getCharacteristic(BleProfileFactory.uuid16("6d65"))
                ?: error("RED 2 TX characteristic not found")
        val rx = service.getCharacteristic(BleProfileFactory.uuid16("544b"))
                ?: error("RED 2 RX characteristic not found")
        client.enableNotifications(rx)
        runCatching { exchange(0x6f, byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00, 0x02, 0x00, 0x00)) }
        exchange(0x62, byteArrayOf(0x01))
    }

    override fun transmit(apdu: ByteArray): ByteArray = exchange(0x6f, apdu)

    private fun exchange(messageType: Int, payload: ByteArray): ByteArray {
        val response = client.exchange(
                tx,
                BleProtocolCodec.ccidFrame(messageType, payload),
                BleProtocolCodec::ccidFrameLength
        )
        return BleProtocolCodec.ccidPayload(response)
    }

    override fun close() = client.close()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    val clean = trim().replace(" ", "")
    require(clean.length % 2 == 0) { "Invalid hex response" }
    return ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
