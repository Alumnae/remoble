package ee.nekoko.remoble.ble

import android.bluetooth.BluetoothGattCharacteristic
import org.json.JSONObject
import java.util.UUID

internal interface BleProfile : AutoCloseable {
    fun connect()
    fun transmit(apdu: ByteArray): ByteArray
}

internal object BleProfileFactory {
    const val TYPE_NBLE = "nble"
    const val TYPE_SIMLINK = "simlink"
    const val TYPE_RED = "red"
    const val TYPE_RED_2 = "red2"
    private val NUS_SERVICE = uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val RED_SERVICE = uuid16("4553")
    private val NINE_ESIM_NAME = Regex("^9esim[0-9a-f]{6}$", RegexOption.IGNORE_CASE)

    fun supports(name: String, advertisedServices: Set<UUID>): Boolean {
        return detect(name, advertisedServices) != null
    }

    fun detect(name: String, advertisedServices: Set<UUID>): String? {
        val lower = name.lowercase()
        return when {
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
        TYPE_NBLE -> NbleProfile(client, displayName, configuredPin)
        TYPE_SIMLINK -> SimLinkProfile(client)
        else -> error("Unsupported BLE reader type: $type")
    }

    fun uuid(value: String): UUID = UUID.fromString(value)
    fun uuid16(value: String): UUID = uuid("0000${value.lowercase()}-0000-1000-8000-00805f9b34fb")
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
    }

    override fun close() = client.close()
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
        val response = client.exchange(tx, bytes) { current ->
            try {
                JSONObject(current.toString(Charsets.UTF_8))
                current.size
            } catch (_: Exception) { null }
        }
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
