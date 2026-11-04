package ee.nekoko.remoble

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import ee.nekoko.remoble.ble.BleProfileFactory
import ee.nekoko.remoble.ble.BleReaderSnapshot
import ee.nekoko.remoble.ble.BleSmartcardService
import ee.nekoko.remoble.ble.NbleAuth
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS = "remoble_prefs"
        private const val COLOR_ACCENT = "#7C4DFF"
        private const val COLOR_CARD = "#202738"
        private const val COLOR_BACKGROUND = "#090D18"
    }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var smartcardService: BleSmartcardService? = null
    private var ownsSmartcardService = false
    private var pendingServiceStart = false
    private var bridgeTransitionInProgress = false
    private var bridgeTransitionTargetRunning = false
    private var bridgeTransitionGeneration = 0
    private val passwordStore by lazy { HTTPPasswordStore(this) }

    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private lateinit var actionButton: Button
    private lateinit var portInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var passwordStatus: TextView
    private lateinit var minimumRssiValue: TextView
    private lateinit var minimumRssiSlider: SeekBar
    private lateinit var singleChannelCheckbox: CheckBox
    private lateinit var autoStartCheckbox: CheckBox
    private lateinit var readersContainer: LinearLayout
    private lateinit var clientsContainer: LinearLayout
    private lateinit var consolePanel: LinearLayout
    private lateinit var consoleScroll: ScrollView
    private lateinit var consoleText: TextView
    private lateinit var consoleSwitch: SwitchCompat
    private var renderedReadersKey: String? = null
    private var renderedConsoleVersion = -1L
    private val connectingReaderNames = mutableSetOf<String>()

    private val uiRefreshTask = object : Runnable {
        override fun run() {
            adoptServiceBackendIfAvailable()
            updateServerUi()
            refreshReaders()
            refreshClientList()
            uiHandler.postDelayed(this, 2_000)
        }
    }

    private val consoleRefreshTask = object : Runnable {
        override fun run() {
            refreshConsole()
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        requestBlePermissions(startServiceWhenGranted = false)
        ensureScanner()
        uiHandler.post(uiRefreshTask)
        uiHandler.post(consoleRefreshTask)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_start", true)) {
            pendingServiceStart = true
            startRemoService()
        }
    }

    private fun buildInterface() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#171C33"), Color.parseColor(COLOR_BACKGROUND))
            )
        }
        val root = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(48))
        }
        root.addView(content)
        screen.addView(
                root,
                LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        )

        content.addView(TextView(this).apply {
            text = "REMOBLE"
            textSize = 12f
            letterSpacing = 0.24f
            setTextColor(Color.parseColor("#B9A7FF"))
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Bluetooth Bridge"
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(22))
        })

        val statusCard = card().apply {
            statusText = valueText("Starting…", 19f)
            urlText = valueText("", 13f).apply { setTextColor(Color.parseColor("#AAB4C8")) }
            addView(statusText)
            addView(urlText)
            actionButton = primaryButton("Start bridge") {
                requestBridgeState(!RemoService.isRunning)
            }
            addView(actionButton, matchParams(top = 18))
        }
        content.addView(statusCard)

        sectionTitle("Remote bridge")
        val bridgeCard = card()
        val portRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Port"), LinearLayout.LayoutParams(0, dp(48), 1f))
            portInput = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(prefs.getInt("port", RemoService.DEFAULT_PORT).toString())
                setTextColor(Color.WHITE)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.TRANSPARENT)
                filters = arrayOf(InputFilter.LengthFilter(5))
                textSize = 16f
            }
            addView(portInput, LinearLayout.LayoutParams(dp(110), dp(48)))
        }
        bridgeCard.addView(portRow)
        bridgeCard.addView(caption("1024–65535. Applying a new port restarts the listener."))
        bridgeCard.addView(primaryButton("Apply port") { applyPort() }, matchParams(top = 12))
        content.addView(bridgeCard)

        sectionTitle("Remote security")
        val securityCard = card()
        passwordStatus = valueText("", 15f)
        securityCard.addView(passwordStatus)
        passwordInput = EditText(this).apply {
            hint = "Remote password"
            setHintTextColor(Color.parseColor("#778198"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            background = rounded("#151B29", 12)
            setPadding(dp(14), 0, dp(14), 0)
        }
        securityCard.addView(passwordInput, matchParams(height = 52, top = 14))
        val passwordButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton("Update") { updatePassword(false) }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(space(dp(10)))
            addView(secondaryButton("Remove") { updatePassword(true) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        securityCard.addView(passwordButtons, matchParams(top = 12))
        securityCard.addView(caption("Use the same password in the remote client. Card requests remain AES-256-CBC compatible."), matchParams(top = 10))
        content.addView(securityCard)

        sectionTitle("Bluetooth signal safety")
        val signalCard = card()
        val signalTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("Minimum RSSI"), LinearLayout.LayoutParams(0, dp(42), 1f))
            minimumRssiValue = valueText("", 15f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            addView(minimumRssiValue, LinearLayout.LayoutParams(dp(100), dp(42)))
        }
        signalCard.addView(signalTitle)
        minimumRssiSlider = SeekBar(this).apply {
            max = 70
            progress = prefs.getInt("minimum_rssi", -88).coerceIn(-120, -50) + 120
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress - 120
                    minimumRssiValue.text = "$value dBm"
                    if (fromUser) {
                        prefs.edit().putInt("minimum_rssi", value).apply()
                        notifyServiceSettingsChanged()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        minimumRssiValue.text = "${minimumRssiSlider.progress - 120} dBm"
        signalCard.addView(minimumRssiSlider)
        signalCard.addView(caption("A new secure transaction is refused below this threshold. Active operations are never interrupted."))
        content.addView(signalCard)

        sectionTitle("Operation")
        val operationCard = card()
        singleChannelCheckbox = checkbox(
                "Ensure one logical channel",
                prefs.getBoolean("single_channel", true)
        ) { saveGeneralSettings() }
        autoStartCheckbox = checkbox(
                "Start bridge when the phone boots",
                prefs.getBoolean("auto_start", true)
        ) { saveGeneralSettings() }
        operationCard.addView(singleChannelCheckbox)
        operationCard.addView(autoStartCheckbox)
        operationCard.addView(caption("When enabled, a new request from the same remote client closes its previous channel on that card. Other clients and the Bluetooth pairing are not interrupted."))
        operationCard.addView(caption("The foreground service and partial wake lock keep HTTP and Bluetooth available while the screen is off."))
        content.addView(operationCard)

        sectionTitle("Bluetooth cards")
        val readerCard = card()
        readerCard.addView(primaryButton("Scan for cards") {
            ensureScanner()
            val started = smartcardService?.startScan() == true
            Toast.makeText(this, if (started) "Scanning" else "Scan already active", Toast.LENGTH_SHORT).show()
        })
        readersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        readerCard.addView(readersContainer)
        readerCard.addView(caption("Tap a card name to connect. Check it to remember and expose it remotely. Swipe a card left to forget it."))
        content.addView(readerCard)

        sectionTitle("Diagnostics")
        val diagnostics = card()
        consoleSwitch = SwitchCompat(this).apply {
            text = "Console log"
            isChecked = prefs.getBoolean("console_enabled", false)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("console_enabled", enabled).apply()
                setConsoleVisible(enabled)
            }
        }
        diagnostics.addView(consoleSwitch)
        diagnostics.addView(caption("Shows a bounded in-memory diagnostic log. Drag the console handle to resize it."))
        clientsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        diagnostics.addView(divider().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })
        diagnostics.addView(clientsContainer)
        diagnostics.addView(caption("Compatible clients seen during the last ten minutes."), matchParams(top = 8))
        content.addView(diagnostics)

        sectionTitle("About")
        content.addView(card().apply {
            addView(valueText("Remoble 0.6.9", 16f))
            addView(caption("Android 5.0+ · Bluetooth Low Energy · foreground background operation"))
        })

        consolePanel = buildConsolePanel()
        screen.addView(
                consolePanel,
                LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.heightPixels * 0.30f).roundToInt()
                )
        )
        setContentView(screen)
        setConsoleVisible(consoleSwitch.isChecked)
        updatePasswordStatus()
    }

    private fun updateServerUi() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val running = RemoService.isRunning
        val port = RemoService.listeningPort.takeIf { it > 0 }
                ?: prefs.getInt("port", RemoService.DEFAULT_PORT)
        val addresses = ipAddresses()
        statusText.text = if (bridgeTransitionInProgress) {
            if (bridgeTransitionTargetRunning) "Starting bridge…" else "Stopping bridge…"
        } else if (running) "Bridge online" else "Bridge offline"
        statusText.setTextColor(Color.parseColor(
                if (bridgeTransitionInProgress) "#FFB74D"
                else if (running) "#62D98B" else "#AAB4C8"
        ))
        urlText.text = if (running) {
            val address = addresses.firstOrNull() ?: "0.0.0.0"
            "http://$address:$port${RemoService.serverStatusMessage?.let { "\n$it" }.orEmpty()}"
        } else "Start the bridge to accept remote connections"
        actionButton.text = if (bridgeTransitionInProgress) {
            if (bridgeTransitionTargetRunning) "Starting…" else "Stopping…"
        } else if (running) "Stop bridge" else "Start bridge"
        actionButton.isEnabled = !bridgeTransitionInProgress
        actionButton.alpha = if (bridgeTransitionInProgress) 0.62f else 1f
        updatePasswordStatus()
    }

    private fun requestBridgeState(targetRunning: Boolean) {
        if (bridgeTransitionInProgress ||
                (RemoService.isRunning == targetRunning &&
                        (!targetRunning || RemoService.listeningPort > 0))) return

        bridgeTransitionInProgress = true
        bridgeTransitionTargetRunning = targetRunning
        val generation = ++bridgeTransitionGeneration
        updateServerUi()

        val accepted = if (targetRunning) {
            saveGeneralSettings()
            startRemoService()
        } else {
            stopRemoService()
            true
        }
        if (!accepted) {
            finishBridgeTransition(generation)
            return
        }
        awaitBridgeState(targetRunning, generation, System.currentTimeMillis() + 12_000L)
    }

    private fun awaitBridgeState(targetRunning: Boolean, generation: Int, deadline: Long) {
        if (generation != bridgeTransitionGeneration) return
        val reached = if (targetRunning) {
            RemoService.isRunning && RemoService.listeningPort > 0
        } else {
            !RemoService.isRunning
        }
        if (reached) {
            finishBridgeTransition(generation)
        } else if (System.currentTimeMillis() >= deadline) {
            finishBridgeTransition(generation)
            Toast.makeText(
                    this,
                    RemoService.serverStatusMessage ?: "Bridge transition timed out",
                    Toast.LENGTH_LONG
            ).show()
        } else {
            uiHandler.postDelayed(
                    { awaitBridgeState(targetRunning, generation, deadline) },
                    200
            )
        }
    }

    private fun finishBridgeTransition(generation: Int) {
        if (generation != bridgeTransitionGeneration) return
        bridgeTransitionInProgress = false
        updateServerUi()
    }

    private fun refreshReaders() {
        if (!::readersContainer.isInitialized) return
        val backend = smartcardService ?: run {
            renderedReadersKey = null
            readersContainer.removeAllViews()
            return
        }
        val snapshots = backend.readerSnapshots()
        // Recreating every row every two seconds interrupts ScrollView gestures, and the old
        // snapshot implementation could wait on an in-progress BLE handshake. Render only when
        // a reader's identity or usable state changes; RSSI stays available to the server's
        // transaction preflight without forcing an expensive UI rebuild.
        val renderKey = snapshots.joinToString("|") { reader ->
            listOf(
                    reader.readerName,
                    reader.displayName,
                    reader.type,
                    reader.address,
                    reader.remembered,
                    reader.connected,
                    reader.settling,
                    connectingReaderNames.contains(reader.readerName),
                    isReaderVisible(reader)
            ).joinToString(";")
        }
        if (renderKey == renderedReadersKey) return
        renderedReadersKey = renderKey
        readersContainer.removeAllViews()
        if (snapshots.isEmpty()) {
            readersContainer.addView(caption("No compatible Bluetooth card found"))
            return
        }
        snapshots.forEachIndexed { index, reader ->
            if (index > 0) readersContainer.addView(divider())
            readersContainer.addView(readerView(reader))
        }
    }

    private fun readerView(reader: BleReaderSnapshot): View {
        val connecting = connectingReaderNames.contains(reader.readerName)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val enabled = checkbox("", isReaderVisible(reader)) { checked ->
            if (checked) {
                connectReader(reader, null)
            } else {
                smartcardService?.setReaderEnabled(reader.readerName, false)
                updateAllowedReader(reader, false)
                renderedReadersKey = null
                refreshReaders()
            }
        }.apply {
            setPadding(0, 0, 0, 0)
            isEnabled = !connecting
        }
        top.addView(enabled, LinearLayout.LayoutParams(dp(48), dp(48)))
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(valueText(reader.displayName, 16f).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(caption("${reader.type.uppercase()} · ${reader.address}"))
            setOnClickListener { connectReader(reader, null) }
        }
        top.addView(identity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val state = when {
            reader.connected -> "Connected"
            connecting -> "Connecting"
            reader.settling -> "Recovering"
            else -> "Ready"
        }
        top.addView(valueText(state, 13f).apply {
            gravity = Gravity.END
            setTextColor(Color.parseColor(if (reader.connected) "#62D98B" else if (connecting || reader.settling) "#FFB74D" else "#AAB4C8"))
        })
        row.addView(top)

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rssi = reader.rssi
        detail.addView(caption(if (rssi == null) "Signal unavailable" else "$rssi dBm · ${signalQuality(rssi)}"), LinearLayout.LayoutParams(0, dp(42), 1f))
        if (reader.type == BleProfileFactory.TYPE_NBLE) {
            val hasPin = smartcardService?.savedPin(reader.readerName).orEmpty().isNotEmpty()
            detail.addView(secondaryButton(if (hasPin) "Reconnect with PIN" else "Connect with PIN") {
                showPinDialog(reader)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)))
        }
        row.addView(detail)
        if (reader.type == BleProfileFactory.TYPE_NBLE) {
            row.addView(secondaryButton("Change Bluetooth name / PIN") {
                showCredentialUpdateDialog(reader)
            }, matchParams(top = 6))
        }

        return SwipeDeleteLayout(row) { swipe -> confirmForgetReader(reader, swipe) }
    }

    private fun confirmForgetReader(reader: BleReaderSnapshot, swipe: SwipeDeleteLayout) {
        swipe.revealForConfirmation()
        val dialog = AlertDialog.Builder(this)
                .setTitle("Remove Bluetooth card?")
                .setMessage("${reader.displayName}\n${reader.address}\n\nThe saved PIN and remote visibility setting will be removed.")
                .setNegativeButton("Cancel") { _, _ -> swipe.reset() }
                .setPositiveButton("Remove") { _, _ ->
                    swipe.dismissLeft {
                        smartcardService?.forgetReader(reader.readerName)
                        removeAllowedReader(reader)
                        renderedReadersKey = null
                        refreshReaders()
                        Toast.makeText(this, "Bluetooth card removed", Toast.LENGTH_SHORT).show()
                    }
                }
                .create()
        dialog.setOnCancelListener { swipe.reset() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(Color.parseColor("#FF5252"))
    }

    /**
     * iOS-style swipe action: the foreground row tracks a left drag and reveals a persistent red
     * removal action underneath. Vertical gestures remain owned by the surrounding ScrollView,
     * and child controls keep receiving ordinary taps.
     */
    private inner class SwipeDeleteLayout(
            private val foreground: View,
            private val onDeleteRequested: (SwipeDeleteLayout) -> Unit
    ) : FrameLayout(this@MainActivity) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        init {
            clipChildren = true
            clipToPadding = true
            background = rounded("#B3261E", 12)
            contentDescription = "Swipe left to remove"

            val removeLabel = TextView(context).apply {
                text = "Remove"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(22), 0, dp(22), 0)
                setCompoundDrawablesWithIntrinsicBounds(
                        android.R.drawable.ic_menu_delete,
                        0,
                        0,
                        0
                )
                compoundDrawablePadding = dp(8)
            }
            addView(
                    removeLabel,
                    FrameLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            Gravity.END
                    )
            )

            foreground.background = rounded(COLOR_CARD, 12)
            addView(
                    foreground,
                    FrameLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    )
            )
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragging = false
                    foreground.animate().cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!dragging && dx < -touchSlop &&
                            kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            return dragging
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        foreground.translationX =
                                (event.x - downX).coerceIn(-width.toFloat(), 0f)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val shouldConfirm = dragging &&
                            foreground.translationX <= -minOf(dp(72).toFloat(), width * 0.25f)
                    dragging = false
                    if (shouldConfirm) onDeleteRequested(this) else reset()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    dragging = false
                    reset()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        fun revealForConfirmation() {
            foreground.animate()
                    .translationX(-dp(104).toFloat())
                    .setDuration(180)
                    .start()
        }

        fun reset() {
            foreground.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .start()
        }

        fun dismissLeft(onFinished: () -> Unit) {
            foreground.animate()
                    .translationX(-width.toFloat())
                    .alpha(0.35f)
                    .setDuration(220)
                    .withEndAction(onFinished)
                    .start()
        }
    }

    private fun connectReader(reader: BleReaderSnapshot, pin: String?) {
        if (!connectingReaderNames.add(reader.readerName)) return
        renderedReadersKey = null
        refreshReaders()
        Toast.makeText(this, "Connecting to ${reader.displayName}", Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching { smartcardService?.connectReader(reader.readerName, pin) }
            runOnUiThread {
                if (result.isSuccess && result.getOrNull() != null) {
                    // Publish to /listSlots only after GATT and authentication succeeded.
                    smartcardService?.setReaderEnabled(reader.readerName, true)
                    updateAllowedReader(reader, true)
                    Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show()
                } else {
                    smartcardService?.setReaderEnabled(reader.readerName, false)
                    updateAllowedReader(reader, false)
                    Toast.makeText(this, result.exceptionOrNull()?.message ?: "Connection failed", Toast.LENGTH_LONG).show()
                }
                connectingReaderNames.remove(reader.readerName)
                renderedReadersKey = null
                refreshReaders()
            }
        }
    }

    private fun showPinDialog(reader: BleReaderSnapshot) {
        val input = EditText(this).apply {
            hint = "6 hexadecimal digits"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            filters = arrayOf(InputFilter { source, _, _, _, _, _ ->
                source.toString().uppercase().filter { it in "0123456789ABCDEF" }
            }, InputFilter.LengthFilter(6))
        }
        val dialog = AlertDialog.Builder(this)
                .setTitle("Connect with PIN")
                .setMessage("The saved PIN is replaced only after successful authentication.")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString().uppercase()
                if (!NbleAuth.isHexPin(pin)) {
                    input.error = "Enter exactly 6 hexadecimal characters"
                } else {
                    dialog.dismiss()
                    connectReader(reader, pin)
                }
            }
        }
        dialog.show()
    }

    private fun showCredentialUpdateDialog(reader: BleReaderSnapshot) {
        val nameInput = EditText(this).apply {
            hint = "Bluetooth name (6 hex characters)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(reader.displayName.takeLast(6).uppercase())
            filters = hexFilters()
        }
        val pinInput = EditText(this).apply {
            hint = "New PIN (6 hex characters)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            filters = hexFilters()
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(nameInput, matchParams())
            addView(pinInput, matchParams(top = 10))
        }
        val dialog = AlertDialog.Builder(this)
                .setTitle("Change Bluetooth name and PIN")
                .setMessage("Supported NBLE readers use a fixed prefix. Choose the final 6 hexadecimal characters and a new 6-character hexadecimal PIN. Keep the new PIN safe.")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val suffix = nameInput.text.toString().uppercase()
                val pin = pinInput.text.toString().uppercase()
                when {
                    !NbleAuth.isHexPin(suffix) -> nameInput.error = "Enter exactly 6 hexadecimal characters"
                    !NbleAuth.isHexPin(pin) -> pinInput.error = "Enter exactly 6 hexadecimal characters"
                    else -> {
                        dialog.dismiss()
                        Toast.makeText(this, "Updating reader…", Toast.LENGTH_SHORT).show()
                        worker.execute {
                            val result = runCatching {
                                smartcardService?.updateNbleCredentials(reader.readerName, suffix, pin)
                                        ?: error("Bluetooth service unavailable")
                            }
                            runOnUiThread {
                                if (result.isSuccess) {
                                    Toast.makeText(this, "Updated and reconnected as ${result.getOrNull()}", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this, result.exceptionOrNull()?.message ?: "Update failed", Toast.LENGTH_LONG).show()
                                }
                                refreshReaders()
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun hexFilters(): Array<InputFilter> = arrayOf(
            InputFilter { source, _, _, _, _, _ ->
                source.toString().uppercase().filter { it in "0123456789ABCDEF" }
            },
            InputFilter.LengthFilter(6)
    )

    private fun refreshClientList() {
        if (!::clientsContainer.isInitialized) return
        clientsContainer.removeAllViews()
        RemoService.cleanOldClients()
        val clients = RemoService.activeClients.toSortedMap()
        if (clients.isEmpty()) {
            clientsContainer.addView(caption("No active clients"))
            return
        }
        val now = System.currentTimeMillis()
        clients.forEach { (client, timestamp) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(valueText(client, 14f), LinearLayout.LayoutParams(0, dp(38), 1f))
            row.addView(caption("${(now - timestamp) / 1000}s ago"), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)))
            clientsContainer.addView(row)
        }
    }

    private fun applyPort() {
        val value = portInput.text.toString().toIntOrNull()
        if (value == null || value !in 1024..65535) {
            portInput.error = "Use a port between 1024 and 65535"
            return
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("port", value).apply()
        restartServiceIfRunning()
    }

    private fun updatePassword(remove: Boolean) {
        val value = if (remove) "" else passwordInput.text.toString().trim()
        if (!remove && value.isEmpty()) {
            passwordInput.error = "Enter a password"
            return
        }
        passwordStore.save(value)
        passwordInput.setText("")
        notifyServiceSettingsChanged()
        updatePasswordStatus()
    }

    private fun updatePasswordStatus() {
        if (!::passwordStatus.isInitialized) return
        val configured = passwordStore.load().isNotEmpty()
        passwordStatus.text = if (configured) "● Password protected" else "● No password configured"
        passwordStatus.setTextColor(Color.parseColor(if (configured) "#62D98B" else "#FFB74D"))
    }

    private fun saveGeneralSettings() {
        if (!::singleChannelCheckbox.isInitialized) return
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("single_channel", singleChannelCheckbox.isChecked)
                .putBoolean("auto_start", autoStartCheckbox.isChecked)
                .apply()
        notifyServiceSettingsChanged()
    }

    private fun updateAllowedReader(reader: BleReaderSnapshot, enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = if (prefs.contains("allowed_readers")) {
            prefs.getStringSet("allowed_readers", emptySet()).orEmpty().toMutableSet()
        } else {
            smartcardService?.readerSnapshots().orEmpty()
                    .filter { it.remembered }
                    .mapTo(mutableSetOf()) { "ble|${it.address.uppercase()}" }
        }
        val key = "ble|${reader.address.uppercase()}"
        values.removeAll { it.substringAfterLast('|').equals(reader.address, ignoreCase = true) }
        if (enabled) values += key
        prefs.edit().putStringSet("allowed_readers", values).apply()
        notifyServiceSettingsChanged()
    }

    private fun isReaderVisible(reader: BleReaderSnapshot): Boolean {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("allowed_readers")) return true
        return prefs.getStringSet("allowed_readers", emptySet()).orEmpty().any {
            it.substringAfterLast('|').equals(reader.address, ignoreCase = true)
        }
    }

    private fun removeAllowedReader(reader: BleReaderSnapshot) = updateAllowedReader(reader, false)

    private fun notifyServiceSettingsChanged() {
        if (RemoService.isRunning) {
            startService(Intent(this, RemoService::class.java).setAction(RemoService.ACTION_RELOAD_SETTINGS))
        }
    }

    private fun restartServiceIfRunning() {
        if (!RemoService.isRunning) return
        stopRemoService()
        uiHandler.postDelayed({ startRemoService() }, 500)
    }

    private fun startRemoService(): Boolean {
        if (!hasBlePermissions()) {
            pendingServiceStart = true
            requestBlePermissions(startServiceWhenGranted = true)
            return false
        }
        return runCatching {
            if (ownsSmartcardService) {
                smartcardService?.shutdown()
                smartcardService = null
                ownsSmartcardService = false
            }
            val intent = Intent(this, RemoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            pendingServiceStart = false
            true
        }.getOrElse {
            ensureScanner()
            Toast.makeText(this, it.message ?: "Unable to start bridge", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun stopRemoService() {
        stopService(Intent(this, RemoService::class.java))
        uiHandler.postDelayed({
            smartcardService = null
            ensureScanner()
            updateServerUi()
        }, 400)
    }

    private fun adoptServiceBackendIfAvailable() {
        val serviceBackend = RemoService.bleService
        if (serviceBackend != null && smartcardService !== serviceBackend) {
            if (ownsSmartcardService) smartcardService?.shutdown()
            smartcardService = serviceBackend
            ownsSmartcardService = false
        } else if (serviceBackend == null && smartcardService == null) {
            ensureScanner()
        }
    }

    private fun ensureScanner() {
        if (smartcardService != null || !hasBlePermissions()) return
        smartcardService = BleSmartcardService(this, worker) { runOnUiThread { refreshReaders() } }
        ownsSmartcardService = true
    }

    private fun hasBlePermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBlePermissions(startServiceWhenGranted: Boolean) {
        if (hasBlePermissions()) {
            ensureScanner()
            if (startServiceWhenGranted && pendingServiceStart) startRemoService()
            return
        }
        pendingServiceStart = pendingServiceStart || startServiceWhenGranted
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        androidx.core.app.ActivityCompat.requestPermissions(this, permissions, 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1001) return
        if (hasBlePermissions()) {
            ensureScanner()
            if (pendingServiceStart) startRemoService()
        } else {
            pendingServiceStart = false
            Toast.makeText(this, "Nearby devices permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildConsolePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080A10"))
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            setBackgroundColor(Color.parseColor("#171B25"))
        }
        val handle = TextView(this).apply {
            text = "━━━   CONSOLE"
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.parseColor("#AAB4C8"))
        }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(40), 1f))
        header.addView(secondaryButton("Clear") {
            ConsoleLog.clear()
            renderedConsoleVersion = -1
            refreshConsole(force = true)
        }, LinearLayout.LayoutParams(dp(76), dp(34)))
        panel.addView(
                header,
                LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(42)
                )
        )

        consoleText = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#D4E2FF"))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(14))
        }
        consoleScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                    consoleText,
                    FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                    )
            )
        }
        panel.addView(
                consoleScroll,
                LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        )

        var dragStartY = 0f
        var dragStartHeight = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    dragStartHeight = panel.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val screenHeight = resources.displayMetrics.heightPixels
                    val minHeight = maxOf(dp(120), (screenHeight * 0.15f).roundToInt())
                    val maxHeight = (screenHeight * 0.75f).roundToInt()
                    val requested = dragStartHeight + (dragStartY - event.rawY).roundToInt()
                    panel.layoutParams = panel.layoutParams.apply {
                        height = requested.coerceIn(minHeight, maxHeight)
                    }
                    true
                }
                else -> true
            }
        }
        return panel
    }

    private fun setConsoleVisible(visible: Boolean) {
        if (!::consolePanel.isInitialized) return
        consolePanel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            renderedConsoleVersion = -1
            refreshConsole(force = true)
        }
    }

    private fun refreshConsole(force: Boolean = false) {
        if (!::consolePanel.isInitialized || consolePanel.visibility != View.VISIBLE) return
        val version = ConsoleLog.version
        if (!force && version == renderedConsoleVersion) return
        val wasNearBottom = !::consoleScroll.isInitialized ||
                consoleScroll.scrollY + consoleScroll.height >= consoleText.height - dp(36)
        consoleText.text = ConsoleLog.snapshot().ifEmpty { "No diagnostic events yet." }
        renderedConsoleVersion = version
        if (wasNearBottom) consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val bounds = Rect()
                focused.getGlobalVisibleRect(bounds)
                if (!bounds.contains(event.rawX.roundToInt(), event.rawY.roundToInt())) {
                    focused.clearFocus()
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        bridgeTransitionGeneration += 1
        uiHandler.removeCallbacks(uiRefreshTask)
        uiHandler.removeCallbacks(consoleRefreshTask)
        if (ownsSmartcardService) smartcardService?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun ipAddresses(): List<String> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun signalQuality(rssi: Int): String = when {
        rssi >= -55 -> "Excellent"
        rssi >= -67 -> "Good"
        rssi >= -75 -> "Fair"
        else -> "Weak"
    }

    private fun sectionTitle(text: String) {
        content.addView(TextView(this).apply {
            this.text = text.uppercase()
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#9CA7BD"))
            setPadding(dp(4), dp(26), 0, dp(10))
        })
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(COLOR_CARD, 18)
        elevation = dp(3).toFloat()
    }

    private fun rounded(color: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.parseColor("#303A50"))
    }

    private fun label(text: String) = valueText(text, 15f)

    private fun valueText(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun caption(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Color.parseColor("#98A3B8"))
        gravity = Gravity.CENTER_VERTICAL
        setLineSpacing(0f, 1.12f)
    }

    private fun primaryButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        background = rounded(COLOR_ACCENT, 12)
        setOnClickListener { action() }
    }

    private fun secondaryButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.parseColor("#D6CCFF"))
        background = rounded("#2A3145", 11)
        setOnClickListener { action() }
    }

    private fun checkbox(text: String, checked: Boolean, action: (Boolean) -> Unit) = CheckBox(this).apply {
        this.text = text
        isChecked = checked
        setTextColor(Color.WHITE)
        buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, value -> action(value) }
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#344057"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun space(width: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(width, 1) }

    private fun matchParams(height: Int = -2, top: Int = 0) =
            LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    if (height == -2) LinearLayout.LayoutParams.WRAP_CONTENT else dp(height)
            ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
