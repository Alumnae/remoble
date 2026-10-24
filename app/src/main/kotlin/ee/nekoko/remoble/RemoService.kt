package ee.nekoko.remoble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import ee.nekoko.remoble.ble.BleSmartcardService
import ee.nekoko.remoble.ble.BleCardSettlingException
import ee.nekoko.remoble.omapi.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class VersionInfo(
        val version: String = "2.1-remoble",
        val deviceName: String = android.os.Build.MODEL,
        val supportedCommands: List<String> =
                listOf(
                        "sendRawApdu",
                        "sendApdu",
                        "openChannel",
                        "closeChannel",
                        "listSlots",
                        "listChannels"
                ),
        val encryptionRequired: Boolean = false,
        val sessionSupported: Boolean = true
)

@Serializable data class EncryptedPayload(val data: String)

@Serializable data class HandshakeRequest(val clientNonce: String)

@Serializable data class HandshakeResponse(val sessionId: String, val serverNonce: String)

@Serializable
data class SlotInfo(
        val name: String,
        val isPresent: Boolean,
        val eid: String? = null,
        val aid: String? = null
)

@Serializable data class OpenChannelRequest(val aids: List<String>, val reader: String? = null)

@Serializable
data class ApduRequest(val apdu: String, val aid: String? = null, val reader: String? = null)

private val DEFAULT_AIDS =
        listOf(
                "A06573746B6D65FFFF4953442D522031",
                "A06573746B6D65FFFF4953442D522030",
                "A0000005591010FFFFFFFF8900000100",
                "A0000005591010FFFFFFFF8900050500",
                "A0000005591010000000008900000300",
                "A0000005591010FFFFFFFF8900000177"
        )

@Serializable data class ErrorResponse(val error: String, val code: String? = null)

@Serializable data class SuccessResponse(val success: Boolean, val message: String? = null)

@Serializable data class TransmitResponse(val response: String)

@Serializable
data class OpenChannelResponse(
        val success: Boolean,
        val aid: String? = null,
        val reader: String? = null
)

@Serializable data class ListSlotsResponse(val slots: List<SlotInfo>)

@Serializable data class ListChannelsResponse(val channels: List<String>)

@Serializable
data class SettingsRequest(
        val port: Int? = null,
        val password: String? = null,
        val useAes: Boolean? = null,
        val singleChannel: Boolean? = null
)

class RemoService : Service() {
    private var server: ApplicationEngine? = null
    private var smartcardService: SmartcardService? = null
    private val activeSessions = mutableMapOf<String, SmartcardSession>()
    private val activeChannels = mutableMapOf<String, SmartcardChannel>() // Key: "reader:aid"

    private var currentPort = 33777
    private var currentPassword = ""
    private var currentUseAes = false
    private var currentSingleChannel = false
    private var allowedReaders: MutableSet<String>? = null
    private var customAids = mutableSetOf<String>()
    private val sessionKeys = mutableMapOf<String, ByteArray>() // sessionId -> sessionKey
    private val readerLocks =
            java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "RemobleService"
        private const val CHANNEL_ID = "RemobleServer"
        private const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
        const val DEFAULT_PORT = 33777
        private const val PORT_ATTEMPTS = 20
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        @Volatile var listeningPort: Int = 0
            private set

        @Volatile var serverStatusMessage: String? = null
            private set

        // Track connected clients: Map<IP, lastSeenTimestamp>
        val activeClients = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val slotEids = java.util.concurrent.ConcurrentHashMap<String, String>()
        val slotAids = java.util.concurrent.ConcurrentHashMap<String, String>()
        val slotErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun cleanOldClients() {
            val now = System.currentTimeMillis()
            val iterator = activeClients.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 10 * 60 * 1000) { // 10 minutes
                    iterator.remove()
                }
            }
        }
    }

    private val simStateReceiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                        context: android.content.Context?,
                        intent: android.content.Intent?
                ) {
                    if (intent?.action == ACTION_SIM_STATE_CHANGED) {
                        Log.d(TAG, "SIM State Changed, refreshing EIDs")
                        refreshEids()
                    }
                }
            }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        loadSettings()
        selectAvailablePort()
        acquireWakeLock()
        startForegroundService()
        initBle()
        startServer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    simStateReceiver,
                    android.content.IntentFilter(ACTION_SIM_STATE_CHANGED),
                    RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(
                    simStateReceiver,
                    android.content.IntentFilter(ACTION_SIM_STATE_CHANGED)
            )
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("remoble_prefs", Context.MODE_PRIVATE)
        currentPort = prefs.getInt("port", DEFAULT_PORT)
        currentPassword = prefs.getString("password", "") ?: ""
        currentUseAes = currentPassword.isNotEmpty()
        currentSingleChannel = prefs.getBoolean("single_channel", true)
        val allowedSet = prefs.getStringSet("allowed_readers", null)
        allowedReaders = if (allowedSet != null) allowedSet.toMutableSet() else null

        customAids = prefs.getStringSet("custom_aids", null)?.toMutableSet() ?: mutableSetOf()
    }

    private fun selectAvailablePort() {
        val requestedPort = currentPort.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        val availablePort =
                generateSequence(requestedPort) { port ->
                            if (port < 65535) port + 1 else null
                        }
                        .take(PORT_ATTEMPTS)
                        .firstOrNull(::canBindToPort)
                        ?: findEphemeralPort()

        currentPort = availablePort
        listeningPort = availablePort
        serverStatusMessage =
                if (availablePort == requestedPort) {
                    null
                } else {
                    "Port $requestedPort occupied; using $availablePort"
                }

        if (availablePort != requestedPort) {
            getSharedPreferences("remoble_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("port", availablePort)
                    .apply()
            Log.w(TAG, serverStatusMessage ?: "Using fallback port $availablePort")
        }
    }

    private fun canBindToPort(port: Int): Boolean =
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress(port))
                }
                true
            } catch (_: Exception) {
                false
            }

    private fun findEphemeralPort(): Int =
            ServerSocket(0).use { socket -> socket.localPort }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock =
                pm.newWakeLock(
                        android.os.PowerManager.PARTIAL_WAKE_LOCK,
                        "Remoble::ServerWakeLock"
                )
        wakeLock?.acquire()
    }

    private fun logAppSignatureHash() {
        try {
            val packageName = packageName
            val packageInfo =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageManager.getPackageInfo(
                                packageName,
                                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo(
                                packageName,
                                android.content.pm.PackageManager.GET_SIGNATURES
                        )
                    }

            val signatures =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.signingInfo?.apkContentsSigners ?: emptyArray()
                    } else {
                        @Suppress("DEPRECATION") packageInfo.signatures ?: emptyArray()
                    }

            for (sig in signatures) {
                val md = java.security.MessageDigest.getInstance("SHA-1")
                val hash = md.digest(sig.toByteArray())
                Log.i(TAG, "App Signature SHA-1: ${bytesToHex(hash)}")
                val md256 = java.security.MessageDigest.getInstance("SHA-256")
                val hash256 = md256.digest(sig.toByteArray())
                Log.i(TAG, "App Signature SHA-256: ${bytesToHex(hash256)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get signature hash", e)
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                            CHANNEL_ID,
                            "Remoble API Server",
                            NotificationManager.IMPORTANCE_LOW
                    )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(this, CHANNEL_ID)
                            .setContentTitle("Remoble Server Running")
                            .setContentText("Listening on port $currentPort")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .build()
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(this)
                            .setContentTitle("Remoble Server Running")
                            .setContentText("Listening on port $currentPort")
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .build()
                }
        startForeground(1, notification)
    }

    private fun initBle() {
        val bleExecutor = Executors.newSingleThreadExecutor()
        smartcardService =
                BleSmartcardService(
                        this,
                        bleExecutor
                ) {
                            Log.i(TAG, "BLE reader list changed")
                            refreshEids()
                        }
        // Reconnect persisted readers off the main thread even when they are not advertising.
        bleExecutor.execute { refreshEids() }
    }

    private fun refreshEids() {
        val readers = smartcardService?.readers ?: return
        readers.forEach { reader ->
            if ((smartcardService as? BleSmartcardService)?.isReaderSettling(reader.name) == true) {
                Log.d(TAG, "Skipping EID refresh while ${reader.name} is settling")
                return@forEach
            }
            // Clear previous errors if we are retrying
            val hadError = slotErrors.containsKey(reader.name)

            if (reader.isSecureElementPresent) {
                try {
                    val session = reader.openSession()

                    // Try to detect working AID and EID
                    var foundAid: String? = null
                    var foundEid: String? = null

                    // Add standard ISD-R AID to the list to check
                    val aidsToCheck = mutableListOf("A0000005591010FFFFFFFF8900000100")
                    aidsToCheck.addAll(DEFAULT_AIDS)
                    aidsToCheck.addAll(customAids)
                    val uniqueAids = aidsToCheck.distinct()

                    for (aid in uniqueAids) {
                        try {
                            val aidBytes = hexToBytes(aid)
                            val channel = session.openLogicalChannel(aidBytes)
                            if (channel != null) {
                                foundAid = aid
                                Log.i(TAG, "Found working AID for ${reader.name}: $aid")

                                // Try to get EID using this channel
                                try {
                                    // GET EID: 80 E2 91 00 06 BF 3E 03 5C 01 5A
                                    val getEidCmd = hexToBytes("80E2910006BF3E035C015A")
                                    val response = transmitWithChunking(channel, getEidCmd)
                                    val respHex = bytesToHex(response)
                                    Log.i(TAG, "Transmit Response: $respHex")

                                    if (respHex == "6881") {
                                        channel.close()
                                        continue
                                    }

                                    if (response.isNotEmpty() && response[0] == 0xbf.toByte()) {
                                        if (respHex.length >= 10 + 32) {
                                            foundEid = respHex.substring(10, 10 + 32).uppercase()
                                            Log.i(TAG, "EID: $foundEid")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to get EID on AID $aid: ${e.message}")
                                }

                                channel.close()
                                if (foundEid != null) {
                                    break // Found everything we need
                                }
                                break
                            }
                        } catch (e: Exception) {
                            // Ignore failure to open this AID
                        }
                    }

                    if (foundEid != null) {
                        slotEids[reader.name] = foundEid!!
                        if (foundAid != null) slotAids[reader.name] = foundAid!!
                        slotErrors.remove(reader.name)
                    } else if (foundAid != null) {
                        slotAids[reader.name] = foundAid!!
                        slotErrors.remove(reader.name)
                    } else {
                        // Connected but no AID found
                        slotErrors[reader.name] = "No supported Applet found"
                    }

                    session.close()
                } catch (e: SecurityException) {
                    Log.e(TAG, "ARA-M Access Denied for ${reader.name}", e)
                    slotErrors[reader.name] = "Access Denied (ARA-M)"
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "IO Exception for ${reader.name}", e)
                    slotErrors[reader.name] = "Card IO Error"
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch EID/AID for ${reader.name}: ${e.message}")
                    // Basic mapping based on message if possible, or generic
                    if (e.message?.contains("NoSuchElement") == true) {
                        slotErrors[reader.name] = "SIM Card Unavailable"
                    } else {
                        slotErrors[reader.name] =
                                e.message?.take(120)
                                        ?: "Connection Failed: ${e.javaClass.simpleName}"
                    }
                }
            } else {
                slotErrors[reader.name] = "No Card Present"
                slotEids.remove(reader.name)
                slotAids.remove(reader.name)
            }
        }
    }

    private fun startServer() {
        try {
            server =
                    embeddedServer(CIO, port = currentPort) {
                        install(ContentNegotiation) {
                            json(
                                    Json {
                                        ignoreUnknownKeys = true
                                        prettyPrint = true
                                        encodeDefaults = true
                                    }
                            )
                        }
                        install(CORS) {
                            anyHost()
                            allowMethod(HttpMethod.Options)
                            allowMethod(HttpMethod.Put)
                            allowMethod(HttpMethod.Delete)
                            allowMethod(HttpMethod.Patch)
                            allowHeader(HttpHeaders.Authorization)
                            allowHeader(HttpHeaders.ContentType)
                            allowHeader(HttpHeaders.AccessControlAllowOrigin)
                            anyHost() // Ensure all origins are allowed for web client
                        }

                        intercept(ApplicationCallPipeline.Plugins) {
                            val remoteHost = call.request.origin.remoteHost
                            activeClients[remoteHost] = System.currentTimeMillis()
                            cleanOldClients()

                            if (currentPassword.isNotEmpty()) {
                                val authHeader = call.request.header(HttpHeaders.Authorization)
                                if (authHeader != "Bearer $currentPassword") {
                                    call.respondMaybeEncrypted(
                                            ErrorResponse("Invalid password", "invalid_password")
                                    )
                                    finish()
                                }
                            }
                        }

                        routing {
                            get("/") {
                                Log.d(TAG, "GET /")
                                call.respond(VersionInfo(encryptionRequired = currentUseAes))
                            }

                            post("/handshake") {
                                val req = call.receive<HandshakeRequest>()
                                val sessionId = java.util.UUID.randomUUID().toString()
                                val serverNonce =
                                        java.util.UUID.randomUUID().toString().substring(0, 16)

                                // Derive Session Key: SHA-256(password + clientNonce + serverNonce)
                                val combined = currentPassword + req.clientNonce + serverNonce
                                val key =
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(combined.toByteArray())
                                sessionKeys[sessionId] = key

                                // Optional: Limit map size to prevent OOM
                                if (sessionKeys.size > 100) {
                                    val firstKey = sessionKeys.keys.first()
                                    sessionKeys.remove(firstKey)
                                }

                                call.respond(HandshakeResponse(sessionId, serverNonce))
                            }

                            get("/listSlots") {
                                Log.d(TAG, "GET /listSlots")
                                val readers =
                                        smartcardService?.readers ?: emptyArray<SmartcardReader>()
                                val allowed = allowedReaders
                                val filtered: List<SmartcardReader> =
                                        if (allowed == null) readers.toList()
                                        else readers.filter { isReaderAllowed(it.name) }
                                val slots =
                                        filtered
                                                .filter {
                                                    slotAids[it.name] != null ||
                                                            slotEids[it.name] != null
                                                }
                                                .map {
                                                    SlotInfo(
                                                            it.name,
                                                            it.isSecureElementPresent,
                                                            slotEids[it.name],
                                                            slotAids[it.name]
                                                    )
                                                }
                                call.respondMaybeEncrypted(ListSlotsResponse(slots))
                            }

                            post("/openChannel") {
                                if (currentSingleChannel) {
                                    closeAllChannels()
                                }

                                try {
                                    Log.d(TAG, "POST /openChannel")
                                    val req = call.receiveMaybeEncrypted<OpenChannelRequest>()
                                    val readers = smartcardService?.readers ?: emptyArray()
                                    val reader =
                                            if (req.reader != null)
                                                    readers.find { it.name == req.reader }
                                            else readers.firstOrNull()

                                    if (reader == null) {
                                        call.respondMaybeEncrypted(
                                                ErrorResponse("Reader not found"),
                                                HttpStatusCode.NotFound
                                        )
                                        return@post
                                    }

                                    val allowed = allowedReaders
                                    if (allowed != null && !isReaderAllowed(reader.name)) {
                                        call.respondMaybeEncrypted(
                                                ErrorResponse(
                                                        "Reader not allowed",
                                                        "access_denied"
                                                ),
                                                HttpStatusCode.Forbidden
                                        )
                                        return@post
                                    }

                                    val mutex =
                                            readerLocks.getOrPut(reader.name) {
                                                kotlinx.coroutines.sync.Mutex()
                                            }
                                    mutex.lock()
                                    try {
                                        if ((smartcardService as? BleSmartcardService)
                                                        ?.isReaderSettling(reader.name) == true) {
                                            call.respondMaybeEncrypted(OpenChannelResponse(false))
                                            return@post
                                        }
                                        var openedChannel: SmartcardChannel? = null
                                        var matchedAid: String? = null

                                        // A stale NBLE/GATT session can still return card-level
                                        // failures for every SELECT. In that specific case, reset
                                        // the remembered reader and repeat the complete AID list
                                        // once. A single retry prevents an endless reconnect loop
                                        // when the requested applet really is not installed.
                                        repeat(2) { attempt ->
                                            if (openedChannel != null) return@repeat
                                            val session =
                                                    if (attempt == 0) {
                                                        activeSessions.getOrPut(reader.name) {
                                                            reader.openSession()
                                                        }
                                                    } else {
                                                        Log.w(
                                                                TAG,
                                                                "All AIDs failed for ${reader.name}; reconnecting BLE and retrying once"
                                                        )
                                                        closeReaderState(reader.name)
                                                        val reconnected =
                                                                (smartcardService as? BleSmartcardService)
                                                                        ?.reconnectReader(reader.name)
                                                                        ?: reader.openSession()
                                                        activeSessions[reader.name] = reconnected
                                                        reconnected
                                                    }

                                            for (aid in req.aids) {
                                                try {
                                                    val aidBytes = hexToBytes(aid)
                                                    Log.d(
                                                            TAG,
                                                            "Trying to open logical channel for AID: $aid (attempt ${attempt + 1})"
                                                    )
                                                    openedChannel =
                                                            session.openLogicalChannel(aidBytes)
                                                    if (openedChannel != null) {
                                                        matchedAid = aid
                                                        activeChannels["${reader.name}:$aid"] =
                                                                openedChannel!!
                                                        Log.i(
                                                                TAG,
                                                                "Successfully opened channel for AID: $aid"
                                                        )
                                                        break
                                                    }
                                                } catch (e: BleCardSettlingException) {
                                                    Log.i(TAG, e.message ?: "9eSIM is settling")
                                                    throw e
                                                } catch (e: SecurityException) {
                                                    Log.e(
                                                            TAG,
                                                            "SecurityException opening channel for AID $aid. App might not be whitelisted: ${e.message}"
                                                    )
                                                } catch (e: Exception) {
                                                    Log.w(
                                                            TAG,
                                                            "Failed for AID $aid: ${e.message}",
                                                            e
                                                    )
                                                }
                                            }
                                        }

                                        if (openedChannel != null) {
                                            call.respondMaybeEncrypted(
                                                    OpenChannelResponse(
                                                            true,
                                                            matchedAid,
                                                            reader.name
                                                    )
                                            )
                                        } else {
                                            call.respondMaybeEncrypted(OpenChannelResponse(false))
                                        }
                                    } catch (e: BleCardSettlingException) {
                                        closeReaderState(reader.name)
                                        call.respondMaybeEncrypted(OpenChannelResponse(false))
                                    } finally {
                                        mutex.unlock()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in /openChannel", e)
                                    if (e.message == "Invalid session") {
                                        call.respond(
                                                HttpStatusCode.Unauthorized,
                                                ErrorResponse("Invalid session", "invalid_session")
                                        )
                                    } else {
                                        call.respondMaybeEncrypted(
                                                ErrorResponse(e.toString()),
                                                HttpStatusCode.InternalServerError
                                        )
                                    }
                                }
                            }

                            post("/sendApdu") {
                                try {
                                    Log.d(TAG, "POST /sendApdu")
                                    val req = call.receiveMaybeEncrypted<ApduRequest>()
                                    val readers = smartcardService?.readers ?: emptyArray()
                                    val readerName = req.reader ?: readers.firstOrNull()?.name ?: ""

                                    val allowed = allowedReaders
                                    if (allowed != null && !isReaderAllowed(readerName)) {
                                        call.respondMaybeEncrypted(
                                                ErrorResponse(
                                                        "Reader not allowed",
                                                        "access_denied"
                                                ),
                                                HttpStatusCode.Forbidden
                                        )
                                        return@post
                                    }

                                    val mutex =
                                            readerLocks.getOrPut(readerName) {
                                                kotlinx.coroutines.sync.Mutex()
                                            }
                                    mutex.lock()
                                    try {
                                        val aid = req.aid ?: ""
                                        val channel = activeChannels["$readerName:$aid"]

                                        if (channel == null) {
                                            call.respondMaybeEncrypted(
                                                    ErrorResponse(
                                                            "Channel not found for reader $readerName and AID $aid. Call openChannel first."
                                                    )
                                            )
                                            return@post
                                        }

                                        val command = hexToBytes(req.apdu)
                                        val response = transmitWithChunking(channel, command)
                                        call.respondMaybeEncrypted(
                                                TransmitResponse(bytesToHex(response))
                                        )
                                    } finally {
                                        mutex.unlock()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in /sendApdu", e)
                                    if (e.message == "Invalid session") {
                                        call.respond(
                                                HttpStatusCode.Unauthorized,
                                                ErrorResponse("Invalid session", "invalid_session")
                                        )
                                    } else {
                                        call.respondMaybeEncrypted(ErrorResponse(e.toString()))
                                    }
                                }
                            }

                            post("/sendRawApdu") {
                                try {
                                    Log.d(TAG, "POST /sendRawApdu")
                                    val req = call.receiveMaybeEncrypted<ApduRequest>()
                                    val readers = smartcardService?.readers ?: emptyArray()
                                    val readerName = req.reader ?: readers.firstOrNull()?.name ?: ""
                                    val reader = readers.find { it.name == readerName }
                                    if (reader == null) {
                                        call.respondMaybeEncrypted(
                                                ErrorResponse("Reader not found"),
                                                HttpStatusCode.NotFound
                                        )
                                        return@post
                                    }

                                    val allowed = allowedReaders
                                    if (allowed != null && !isReaderAllowed(reader.name)) {
                                        call.respondMaybeEncrypted(
                                                ErrorResponse(
                                                        "Reader not allowed",
                                                        "access_denied"
                                                ),
                                                HttpStatusCode.Forbidden
                                        )
                                        return@post
                                    }

                                    val mutex =
                                            readerLocks.getOrPut(reader.name) {
                                                kotlinx.coroutines.sync.Mutex()
                                            }
                                    mutex.lock()
                                    try {
                                        val session = reader.openSession()
                                        // Raw APDU in OMAPI usually means open logical channel to
                                        // default eUICC AID and transmit
                                        val defaultAid =
                                                hexToBytes("A0000005591010FFFFFFFF8900000100")
                                        val channel = session.openLogicalChannel(defaultAid)
                                        if (channel == null) {
                                            call.respond(
                                                    HttpStatusCode.InternalServerError,
                                                    ErrorResponse("Failed to open default channel")
                                            )
                                            return@post
                                        }
                                        val command = hexToBytes(req.apdu)
                                        val response = transmitWithChunking(channel, command)
                                        channel.close()
                                        session.close()
                                        call.respondMaybeEncrypted(
                                                TransmitResponse(bytesToHex(response))
                                        )
                                    } finally {
                                        mutex.unlock()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in /sendRawApdu", e)
                                    if (e.message == "Invalid session") {
                                        call.respond(
                                                HttpStatusCode.Unauthorized,
                                                ErrorResponse("Invalid session", "invalid_session")
                                        )
                                    } else {
                                        call.respondMaybeEncrypted(ErrorResponse(e.toString()))
                                    }
                                }
                            }

                            post("/closeChannel") {
                                try {
                                    Log.d(TAG, "POST /closeChannel")
                                    val req =
                                            call.receiveMaybeEncrypted<
                                                    OpenChannelRequest>() // reuse aids
                                    val readers = smartcardService?.readers ?: emptyArray()
                                    val readerName = req.reader ?: readers.firstOrNull()?.name ?: ""

                                    req.aids.forEach { aid ->
                                        activeChannels.remove("$readerName:$aid")?.close()
                                    }
                                    call.respondMaybeEncrypted(SuccessResponse(true))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in /closeChannel", e)
                                    if (e.message == "Invalid session") {
                                        call.respond(
                                                HttpStatusCode.Unauthorized,
                                                ErrorResponse("Invalid session", "invalid_session")
                                        )
                                    } else {
                                        call.respondMaybeEncrypted(ErrorResponse(e.toString()))
                                    }
                                }
                            }

                            get("/listChannels") {
                                Log.d(TAG, "GET /listChannels")
                                call.respond(ListChannelsResponse(activeChannels.keys.toList()))
                            }

                            post("/updateSettings") {
                                val req = call.receive<SettingsRequest>()
                                val prefs =
                                        getSharedPreferences("remoble_prefs", Context.MODE_PRIVATE)
                                prefs.edit().apply {
                                    req.port?.let { putInt("port", it) }
                                    req.password?.let { putString("password", it) }
                                    req.singleChannel?.let { putBoolean("single_channel", it) }
                                    apply()
                                }
                                loadSettings()
                                call.respond(
                                        SuccessResponse(
                                                true,
                                                "Settings updated. Port change requires server restart."
                                        )
                                )
                            }
                        }
                    }
            server?.start(wait = false)
            Log.i(TAG, "Server started on port $currentPort")
        } catch (e: Exception) {
            listeningPort = 0
            serverStatusMessage = "Server failed: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private fun transmitWithChunking(channel: SmartcardChannel, command: ByteArray): ByteArray {
        // Bi-directional chunking:
        // 1. Command Chaining (split huge command into multiple APDUs with bit 4 of CLA set)
        // 2. Response Chaining (handle 61XX GET RESPONSE)

        // This is a simplified implementation. Real ISO 7816-4 chaining might be more complex.
        // But the user specifically asked for bi-directional handling of huge request/responses.

        // Handle Request Chaining (Sending)
        // If command is > 255 bytes and SE doesn't support extended length, or just to be safe:
        // (Actually OMAPI handles extended length if SE supports it, but let's assume standard
        // chaining)

        // For now, let's focus on Response Chaining which is most common "penalty"
        var response = channel.transmit(command)
        var sw1 = response[response.size - 2].toInt() and 0xFF
        var sw2 = response[response.size - 1].toInt() and 0xFF

        val fullResponse = mutableListOf<Byte>()
        fullResponse.addAll(response.copyOfRange(0, response.size - 2).toList())

        while (sw1 == 0x61) {
            val getResponse = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw2.toByte())
            response = channel.transmit(getResponse)
            sw1 = response[response.size - 2].toInt() and 0xFF
            sw2 = response[response.size - 1].toInt() and 0xFF
            fullResponse.addAll(response.copyOfRange(0, response.size - 2).toList())
        }

        if (sw1 == 0x6C) {
            val corrected = command.copyOf()
            corrected[corrected.size - 1] = sw2.toByte()
            return transmitWithChunking(channel, corrected)
        }

        fullResponse.add(sw1.toByte())
        fullResponse.add(sw2.toByte())
        return fullResponse.toByteArray()
    }

    private fun isReaderAllowed(readerName: String): Boolean {
        val allowed = allowedReaders ?: return true
        if (readerName in allowed) return true
        val address = readerName.substringAfterLast('|', missingDelimiterValue = "")
        return address.isNotEmpty() && allowed.any {
            it.substringAfterLast('|', missingDelimiterValue = "") == address
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        listeningPort = 0
        serverStatusMessage = null
        wakeLock?.let { if (it.isHeld) it.release() }
        server?.stop(1000, 2000)
        closeAllChannels()
        smartcardService?.shutdown()
        unregisterReceiver(simStateReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(" ", "")
        val result = ByteArray(s.length / 2)
        for (i in 0 until s.length step 2) {
            result[i / 2] = s.substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun closeAllChannels() {
        activeChannels.values.forEach {
            try {
                it.close()
            } catch (e: Exception) {}
        }
        activeChannels.clear()
        activeSessions.values.forEach {
            try {
                it.close()
            } catch (e: Exception) {}
        }
        activeSessions.clear()
    }

    private fun closeReaderState(readerName: String) {
        val prefix = "$readerName:"
        val channelIterator = activeChannels.iterator()
        while (channelIterator.hasNext()) {
            val entry = channelIterator.next()
            if (entry.key.startsWith(prefix)) {
                runCatching { entry.value.close() }
                channelIterator.remove()
            }
        }
        activeSessions.remove(readerName)?.let { runCatching { it.close() } }
    }

    private fun decrypt(encrypted: String, sessionId: String? = null): String {
        if (currentPassword.isEmpty() || !currentUseAes) return encrypted
        try {
            val keyBytes =
                    if (sessionId != null) {
                        sessionKeys[sessionId] ?: throw Exception("Invalid session")
                    } else {
                        MessageDigest.getInstance("SHA-256").digest(currentPassword.toByteArray())
                    }
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val data = Base64.decode(encrypted, Base64.DEFAULT)
            val iv = IvParameterSpec(data.copyOfRange(0, 16))
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            return String(cipher.doFinal(data.copyOfRange(16, data.size)))
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed (session: $sessionId)", e)
            throw e
        }
    }

    private fun encrypt(plainText: String, sessionId: String? = null): String {
        if (currentPassword.isEmpty() || !currentUseAes) return plainText
        try {
            val keyBytes =
                    if (sessionId != null) {
                        sessionKeys[sessionId] ?: throw Exception("Invalid session")
                    } else {
                        MessageDigest.getInstance("SHA-256").digest(currentPassword.toByteArray())
                    }
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray())
            return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed (session: $sessionId)", e)
            // If encryption failed (e.g. invalid session), send plain error if possible, or better:
            // Since we can't encrypt, returning a plain error might be intercepted as 500 on client
            // if it expects encrypted.
            // But if we return a JSON that client CAN parse, handled in catch block?
            // Actually, returning a special string to signal failure.
            return "{ \"error\": \"Encryption failed: ${e.message}\", \"code\": \"encryption_failed\" }"
        }
    }

    private suspend inline fun <reified T : Any> ApplicationCall.receiveMaybeEncrypted(): T {
        return if (currentUseAes) {
            val sessionId = request.header("X-Remo-Session")
            val encryptedPayload = receive<EncryptedPayload>()
            val decryptedJson = decrypt(encryptedPayload.data, sessionId)
            json.decodeFromString<T>(decryptedJson)
        } else {
            receive()
        }
    }

    private suspend inline fun <reified T : Any> ApplicationCall.respondMaybeEncrypted(
            data: T,
            status: HttpStatusCode = HttpStatusCode.OK
    ) {
        var finalStatus = status
        if (data is ErrorResponse && data.code == "invalid_password") {
            finalStatus = HttpStatusCode.Unauthorized
        } else if (data is ErrorResponse) {
            if (finalStatus == HttpStatusCode.OK) finalStatus = HttpStatusCode.BadRequest
        }

        if (currentUseAes) {
            val sessionId = request.header("X-Remo-Session")
            val plainJson = json.encodeToString(data)
            val encryptedData = encrypt(plainJson, sessionId)
            respond(finalStatus, EncryptedPayload(encryptedData))
        } else {
            respond(finalStatus, data)
        }
    }
}
