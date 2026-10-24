package ee.nekoko.remoble

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import ee.nekoko.remoble.ble.BleSmartcardService
import ee.nekoko.remoble.ble.NbleAuth
import ee.nekoko.remoble.omapi.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var portInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var blePinInput: EditText
    private lateinit var singleChannelCheckbox: CheckBox
    private lateinit var autoStartCheckbox: CheckBox
    private lateinit var readerWhitelistContainer: LinearLayout
    private lateinit var clientsContainer: LinearLayout
    private var smartcardService: SmartcardService? = null
    private var pendingServiceStart = false

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiRefreshTask =
            object : Runnable {
                override fun run() {
                    refreshClientList()
                    refreshReaderWhitelist()
                    if (isServiceRunning) updateUi(true)
                    uiHandler.postDelayed(this, 5000)
                }
            }

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("remoble_prefs", Context.MODE_PRIVATE)
        val root =
                ScrollView(this).apply {
                    setBackgroundColor(Color.parseColor("#121212")) // Dark background
                    isFillViewport = true
                }

        val layout =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(64, 48, 64, 64)
                    gravity = Gravity.CENTER_HORIZONTAL
                }

        // Add Header/Title
        val titleText =
                TextView(this).apply {
                    text = "Remoble"
                    textSize = 34f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#FF9800")) // Orange
                    setPadding(0, 32, 0, 8)
                    gravity = Gravity.CENTER
                }
        val subtitleText =
                TextView(this).apply {
                    text = "REMOTE BLE eUICC BRIDGE"
                    textSize = 10f
                    letterSpacing = 0.2f
                    setTextColor(Color.parseColor("#FF9800"))
                    setPadding(0, 0, 0, 48)
                    gravity = Gravity.CENTER
                }
        layout.addView(titleText)
        layout.addView(subtitleText)

        statusText =
                TextView(this).apply {
                    text = "Remoble Server Offline"
                    textSize = 20f
                    setTextColor(Color.GRAY)
                    setPadding(0, 0, 0, 48)
                    gravity = Gravity.CENTER
                }

        actionButton =
                Button(this).apply {
                    text = "Start Server"
                    setAllCaps(false)
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { setMargins(0, 0, 0, 64) }
                    setOnClickListener {
                        if (isServiceRunning) {
                            stopRemoService()
                            updateUi(false)
                        } else {
                            saveSettings()
                            if (startRemoService()) updateUi(true)
                        }
                    }
                }

        // Settings section
        val settingsTitle =
                TextView(this).apply {
                    text = "SERVER SETTINGS"
                    textSize = 12f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 32, 0, 16)
                }

        portInput =
                createLabeledInput(
                        "Server Port",
                        RemoService.DEFAULT_PORT.toString(),
                        InputType.TYPE_CLASS_NUMBER
                )
        portInput.setText(prefs.getInt("port", RemoService.DEFAULT_PORT).toString())

        passwordInput =
                createLabeledInput(
                        "Connection Password",
                        "",
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
        passwordInput.setText(prefs.getString("password", ""))

        blePinInput =
                createLabeledInput(
                        "BLE eUICC PIN",
                        "6 hex characters; blank uses device default",
                        InputType.TYPE_CLASS_TEXT
                ).apply {
                    keyListener = DigitsKeyListener.getInstance("0123456789abcdefABCDEF")
                    filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                    transformationMethod = PasswordTransformationMethod.getInstance()
                    setText(prefs.getString(NbleAuth.PREF_KEY, ""))
                }

        singleChannelCheckbox =
                createCheckBox(
                        "Restrict to Single Channel",
                        prefs.getBoolean("single_channel", true)
                )
        singleChannelCheckbox.setOnCheckedChangeListener { _, _ -> saveSettings() }

        autoStartCheckbox =
                createCheckBox("Auto-start on Boot", prefs.getBoolean("auto_start", true))
        autoStartCheckbox.setOnCheckedChangeListener { _, _ -> saveSettings() }

        val whitelistTitle =
                TextView(this).apply {
                    text = "ALLOWED READERS (Defaults to all if none selected)"
                    textSize = 12f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 32, 0, 16)
                }
        readerWhitelistContainer =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 32)
                }

        val clientsTitle =
                TextView(this).apply {
                    text = "CONNECTED CLIENTS (Last 10m)"
                    textSize = 12f
                    setTextColor(Color.parseColor("#FF9800"))
                    setPadding(0, 32, 0, 16)
                }
        clientsContainer =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 32)
                }

        layout.addView(statusText)
        layout.addView(actionButton)
        layout.addView(settingsTitle)
        layout.addView(createLabel("Port"))
        layout.addView(portInput)
        layout.addView(createLabel("Password (Leave empty for none)"))
        layout.addView(passwordInput)
        layout.addView(createLabel("BLE eUICC PIN (6 hexadecimal characters)"))
        layout.addView(blePinInput)
        layout.addView(singleChannelCheckbox)
        layout.addView(autoStartCheckbox)
        layout.addView(whitelistTitle)
        layout.addView(readerWhitelistContainer)
        layout.addView(clientsTitle)
        layout.addView(clientsContainer)

        // Init SE Service to list readers
        smartcardService =
                BleSmartcardService(this, java.util.concurrent.Executors.newSingleThreadExecutor()) {
                            runOnUiThread { refreshReaderWhitelist() }
                        }

        // Add text change listeners for auto-save
        val textWatcher =
                object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        saveSettings()
                    }
                    override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                    ) {}
                    override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                    ) {}
                }
        portInput.addTextChangedListener(textWatcher)
        passwordInput.addTextChangedListener(textWatcher)
        blePinInput.addTextChangedListener(
                object : android.text.TextWatcher {
                    override fun afterTextChanged(value: android.text.Editable?) {
                        val pin = value?.toString().orEmpty()
                        blePinInput.error =
                                if (pin.isEmpty() || NbleAuth.isHexPin(pin)) null
                                else "PIN must contain exactly 6 hexadecimal characters"
                        saveSettings()
                    }

                    override fun beforeTextChanged(
                            value: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                    ) {}

                    override fun onTextChanged(
                            value: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                    ) {}
                }
        )

        root.addView(layout)
        setContentView(root)
        requestBlePermissions(startServiceWhenGranted = false)

        // Start UI refresh task
        uiHandler.post(uiRefreshTask)

        // Auto-start on launch if enabled
        if (prefs.getBoolean("auto_start", true)) {
            pendingServiceStart = true
            if (startRemoService()) updateUi(true)
        }
    }

    private fun createLabel(text: String) =
            TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(Color.LTGRAY)
                setPadding(0, 16, 0, 8)
            }

    private fun createLabeledInput(label: String, hint: String, inputType: Int): EditText {
        return EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setTextColor(Color.WHITE)
            setHintTextColor(Color.DKGRAY)
            background.setColorFilter(Color.GRAY, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
    }

    private fun createCheckBox(text: String, checked: Boolean) =
            CheckBox(this).apply {
                this.text = text
                this.isChecked = checked
                setTextColor(Color.LTGRAY)
                setPadding(0, 24, 0, 24)
                // Set checkbox tint to orange
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    buttonTintList =
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
                }
            }

    private fun refreshClientList() {
        if (!::clientsContainer.isInitialized) return
        clientsContainer.removeAllViews()
        RemoService.cleanOldClients()
        val clients = RemoService.activeClients
        val now = System.currentTimeMillis()

        if (clients.isEmpty()) {
            clientsContainer.addView(
                    TextView(this).apply {
                        text = "No active clients"
                        textSize = 13f
                        setTextColor(Color.DKGRAY)
                        gravity = Gravity.CENTER
                    }
            )
            return
        }

        clients.forEach { (ip, timestamp) ->
            val secondsAgo = (now - timestamp) / 1000
            val row =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 16, 0, 16)
                    }
            val ipText =
                    TextView(this).apply {
                        text = ip
                        weight = 1f
                        setTextColor(Color.WHITE)
                        textSize = 14f
                    }
            val timeText =
                    TextView(this).apply {
                        text = "${secondsAgo}s ago"
                        setTextColor(
                                if (secondsAgo < 30) Color.parseColor("#4CAF50") else Color.GRAY
                        )
                        textSize = 12f
                    }
            row.addView(ipText)
            row.addView(timeText)
            clientsContainer.addView(row)
        }
    }

    private fun LinearLayout.addView(view: View, weight: Float) {
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        view.layoutParams = params
        this.addView(view)
    }

    private var TextView.weight: Float
        get() = 0f
        set(value) {
            layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, value)
        }

    private fun refreshReaderWhitelist() {
        readerWhitelistContainer.removeAllViews()
        val readers = smartcardService?.readers ?: emptyArray<SmartcardReader>()
        val prefs = getSharedPreferences("remoble_prefs", Context.MODE_PRIVATE)
        // If "allowed_readers" is missing (null), it means "Allow All"
        val allowedSet = prefs.getStringSet("allowed_readers", null)

        readers.forEach { reader ->
            val row =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

            // Defaults to checked if no preference exists
            val readerAddress = reader.name.substringAfterLast('|', missingDelimiterValue = "")
            val isAllowed =
                    allowedSet == null ||
                            allowedSet.contains(reader.name) ||
                            (readerAddress.isNotEmpty() &&
                                    allowedSet.any {
                                        it.substringAfterLast('|', missingDelimiterValue = "") ==
                                                readerAddress
                                    })

            val cb = createCheckBox(reader.name, isAllowed)
            cb.setOnCheckedChangeListener { _, isChecked -> saveSettings() }
            cb.setPadding(0, 0, 0, 0)

            val eid = RemoService.slotEids[reader.name]
            val aid = RemoService.slotAids[reader.name]
            val error = RemoService.slotErrors[reader.name]

            val statusText =
                    if (!eid.isNullOrEmpty()) {
                        " EID: $eid"
                    } else if (!aid.isNullOrEmpty()) {
                        " Channel Open"
                    } else if (!error.isNullOrEmpty()) {
                        " $error"
                    } else {
                        if (isAllowed) " Connecting / No Card" else " Disabled"
                    }

            val infoText =
                    TextView(this).apply {
                        layoutParams =
                                android.widget.LinearLayout.LayoutParams(
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                        text = statusText
                        textSize = 12f
                        setTextColor(
                                if (!error.isNullOrEmpty() ||
                                                statusText.contains("Error") ||
                                                statusText.contains("Failed") ||
                                                statusText.contains("Denied")
                                )
                                        Color.RED
                                else Color.LTGRAY
                        )
                        setPadding(16, 0, 0, 0)
                    }

            row.addView(cb)
            row.addView(infoText)
            readerWhitelistContainer.addView(row)
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("remoble_prefs", Context.MODE_PRIVATE)
        val allowed = mutableSetOf<String>()
        for (i in 0 until readerWhitelistContainer.childCount) {
            val row = readerWhitelistContainer.getChildAt(i) as? android.widget.LinearLayout
            val cb = row?.getChildAt(0) as? CheckBox
            if (cb != null && cb.isChecked) {
                allowed.add(cb.text.toString())
            }
        }

        prefs.edit().apply {
            putInt(
                    "port",
                    portInput.text.toString().toIntOrNull() ?: RemoService.DEFAULT_PORT
            )
            putString("password", passwordInput.text.toString())
            val blePin = blePinInput.text.toString().trim()
            if (blePin.isEmpty()) remove(NbleAuth.PREF_KEY)
            else if (NbleAuth.isHexPin(blePin)) putString(NbleAuth.PREF_KEY, blePin.uppercase())
            if (readerWhitelistContainer.childCount == 0) remove("allowed_readers")
            else putStringSet("allowed_readers", allowed)
            putBoolean("single_channel", singleChannelCheckbox.isChecked)
            putBoolean("auto_start", autoStartCheckbox.isChecked)
            apply()
        }
    }

    private fun getIPAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ips
    }

    private fun updateUi(running: Boolean) {
        isServiceRunning = running
        val prefs = getSharedPreferences("remoble_prefs", Context.MODE_PRIVATE)
        val configuredPort = prefs.getInt("port", RemoService.DEFAULT_PORT)
        val port = RemoService.listeningPort.takeIf { it > 0 } ?: configuredPort
        if (running) {
            if (!portInput.hasFocus() && portInput.text.toString() != port.toString()) {
                portInput.setText(port.toString())
            }
            val ips = getIPAddresses()
            val ipText = if (ips.isEmpty()) "Unknown IP" else ips.joinToString(", ")
            val serverNotice = RemoService.serverStatusMessage
            statusText.text =
                    if (serverNotice.isNullOrEmpty()) {
                        "Server running on\n$ipText:$port"
                    } else {
                        "Server running on\n$ipText:$port\n$serverNotice"
                    }
            statusText.setTextColor(Color.parseColor("#4CAF50"))
            actionButton.text = "Stop Server"
            actionButton.setBackgroundColor(Color.parseColor("#E53935"))
        } else {
            statusText.text = "Server Offline"
            statusText.setTextColor(Color.GRAY)
            actionButton.text = "Start Server"
            actionButton.setBackgroundColor(Color.parseColor("#1E88E5"))
        }
    }

    private fun startRemoService(): Boolean {
        if (!hasBlePermissions()) {
            pendingServiceStart = true
            requestBlePermissions(startServiceWhenGranted = true)
            statusText.text = "Bluetooth permission required"
            statusText.setTextColor(Color.parseColor("#FF9800"))
            return false
        }

        val intent = Intent(this, RemoService::class.java)
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            pendingServiceStart = false
            true
        } catch (error: SecurityException) {
            pendingServiceStart = true
            statusText.text = "Bluetooth permission denied"
            statusText.setTextColor(Color.RED)
            false
        }
    }

    private fun stopRemoService() {
        val intent = Intent(this, RemoService::class.java)
        stopService(intent)
    }

    private fun hasBlePermissions(): Boolean {
        val permissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT
                    )
                } else {
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
        return permissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissions(startServiceWhenGranted: Boolean) {
        if (hasBlePermissions()) {
            if (startServiceWhenGranted && pendingServiceStart && startRemoService()) updateUi(true)
            return
        }

        pendingServiceStart = pendingServiceStart || startServiceWhenGranted
        val permissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT
                    )
                } else {
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
                }
        val missing = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1001) return

        if (hasBlePermissions()) {
            (smartcardService as? BleSmartcardService)?.startScan()
            if (pendingServiceStart && startRemoService()) updateUi(true)
        } else {
            pendingServiceStart = false
            updateUi(false)
            statusText.text = "Nearby devices permission denied"
            statusText.setTextColor(Color.RED)
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiRefreshTask)
        smartcardService?.shutdown()
        super.onDestroy()
    }
}
