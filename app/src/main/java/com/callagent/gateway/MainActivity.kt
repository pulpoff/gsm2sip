package com.callagent.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.net.wifi.WifiManager
import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.callagent.gateway.service.CallLogEntry
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.service.GatewayService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Settings-tab views
    private lateinit var statusDot: View
    private lateinit var tvStatusText: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnCopyLog: Button
    private lateinit var btnConfig: ImageButton
    private lateinit var btnInfo: ImageButton

    // Dialler-tab views
    private lateinit var tvDialNumber: TextView
    private lateinit var btnCall: ImageView

    // Calls-tab views
    private lateinit var callLogContainer: LinearLayout
    private lateinit var callLogScroll: ScrollView
    private lateinit var tvCallsEmpty: TextView
    private lateinit var btnFilterIn: ImageButton
    private lateinit var btnFilterOut: ImageButton
    private var callLogFilter = "IN"

    // Pre-cached call log lists (built once, swapped on filter change)
    private var cachedInEntries: List<CallLogEntry> = emptyList()
    private var cachedOutEntries: List<CallLogEntry> = emptyList()
    private var callLogBuiltIn = false
    private var callLogBuiltOut = false

    // Tab containers + bottom bar
    private lateinit var tabbedRoot: LinearLayout
    private lateinit var tabDialer: LinearLayout
    private lateinit var tabCalls: LinearLayout
    private lateinit var tabSettings: LinearLayout
    private lateinit var tabBtnDialer: View
    private lateinit var tabBtnCalls: View
    private lateinit var tabBtnSettings: View
    private lateinit var tabIconDialer: ImageView
    private lateinit var tabIconCalls: ImageView
    private lateinit var tabIconSettings: ImageView
    private lateinit var tabLabelDialer: TextView
    private lateinit var tabLabelCalls: TextView
    private lateinit var tabLabelSettings: TextView
    private lateinit var bottomTabBar: LinearLayout
    private var currentTab = "dialer"

    // In-call views
    private lateinit var inCallView: LinearLayout
    private lateinit var tvInCallStatus: TextView
    private lateinit var tvInCallNumber: TextView
    private lateinit var tvInCallTimer: TextView
    private lateinit var btnInCallEnd: Button
    private var inCallOpen = false
    private var inCallOpenTime = 0L
    private var viewBeforeInCall = "dialer"
    private var callStartTime = 0L
    private var lastGsmPollState = -1
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (callStartTime > 0) {
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                tvInCallTimer.text = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                callTimerHandler.postDelayed(this, 1000)
            }
        }
    }
    private val gsmPollRunnable = object : Runnable {
        override fun run() {
            if (!inCallOpen) return
            val call = com.callagent.gateway.gsm.GsmCallManager.activeCall
            val state = com.callagent.gateway.gsm.GsmCallManager.activeCallState
            if (call != null && state != lastGsmPollState) {
                lastGsmPollState = state
                when (state) {
                    android.telecom.Call.STATE_CONNECTING -> tvInCallStatus.text = "Calling..."
                    android.telecom.Call.STATE_DIALING -> tvInCallStatus.text = "Ringing..."
                    android.telecom.Call.STATE_RINGING -> tvInCallStatus.text = "Ringing..."
                    android.telecom.Call.STATE_ACTIVE -> {
                        if (running) {
                            tvInCallStatus.text = "Connecting..."
                        } else {
                            tvInCallStatus.text = "Connected"
                            if (callStartTime == 0L) {
                                callStartTime = System.currentTimeMillis()
                                tvInCallTimer.text = "00:00"
                                tvInCallTimer.visibility = View.VISIBLE
                                callTimerRunnable.run()
                            }
                        }
                    }
                    android.telecom.Call.STATE_DISCONNECTED -> {
                        scheduleInCallClose()
                        return
                    }
                }
            } else if (call == null && lastGsmPollState != -1) {
                // GSM call was seen by poll but is now gone — call ended
                scheduleInCallClose()
                return
            } else if (call == null && lastGsmPollState == -1) {
                // Never saw a GSM call — failed dial or slow setup
                // Safety timeout to avoid stuck screen
                if (System.currentTimeMillis() - inCallOpenTime > 8000) {
                    closeInCallScreen()
                    return
                }
            }
            callTimerHandler.postDelayed(this, 500)
        }
    }

    private var running = false
    private var gsmCallActive = false
    private var onlineSince = 0L

    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            if (onlineSince > 0) {
                val elapsed = (System.currentTimeMillis() - onlineSince) / 1000
                tvUptime.text = formatDuration(elapsed)
                uptimeHandler.postDelayed(this, 1000)
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GatewayService.STATUS_ACTION -> {
                    val state = intent.getStringExtra("state") ?: return
                    val info = intent.getStringExtra("info") ?: ""
                    updateStatus(state, info)

                    val newOnlineSince = intent.getLongExtra("online_since", 0L)
                    if (newOnlineSince != onlineSince) {
                        onlineSince = newOnlineSince
                        uptimeHandler.removeCallbacks(uptimeRunnable)
                        if (onlineSince > 0) {
                            uptimeRunnable.run()
                        } else {
                            tvUptime.text = ""
                        }
                    }

                    val wasRunning = running
                    running = state != "STOPPED" && state != "ERROR"
                    if (running != wasRunning) updateToggleButton()

                    val callActive = state in listOf(
                        "GSM_RINGING", "GSM_ANSWERED", "SIP_CALLING",
                        "SIP_RINGING", "BRIDGED", "GSM_DIALING", "TEARING_DOWN"
                    )
                    if (callActive != gsmCallActive) {
                        gsmCallActive = callActive
                        updateCallButton()
                    }

                    // Show in-call screen for incoming calls on Dialer or Calls tab
                    // On Settings tab the gateway still auto-answers — status + log is enough
                    if (!inCallOpen && state == "GSM_RINGING") {
                        val callerNum = info.removePrefix("GSM call from ")
                        if (currentTab == "dialer" || currentTab == "calls") {
                            openInCallScreen(callerNum)
                            tvInCallStatus.text = "Incoming call"
                        }
                    }

                    if (inCallOpen) {
                        when (state) {
                            "GSM_DIALING" -> tvInCallStatus.text = "Calling..."
                            "GSM_ANSWERED", "SIP_CALLING" -> tvInCallStatus.text = "Connecting..."
                            "SIP_RINGING" -> tvInCallStatus.text = "Ringing..."
                            "BRIDGED" -> {
                                tvInCallStatus.text = "Connected"
                                if (callStartTime == 0L) {
                                    callStartTime = System.currentTimeMillis()
                                    tvInCallTimer.text = "00:00"
                                    tvInCallTimer.visibility = View.VISIBLE
                                    callTimerRunnable.run()
                                }
                            }
                            "TEARING_DOWN" -> tvInCallStatus.text = "Ending..."
                            "IDLE" -> {
                                scheduleInCallClose()
                            }
                        }
                    }

                    appendLog("[$state] $info")
                }
                GatewayService.LOG_ACTION -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    appendLog(msg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Tab containers
        tabbedRoot = findViewById(R.id.tabbedRoot)
        tabDialer = findViewById(R.id.tabDialer)
        tabCalls = findViewById(R.id.tabCalls)
        tabSettings = findViewById(R.id.tabSettings)
        bottomTabBar = findViewById(R.id.bottomTabBar)

        // Tab bar buttons
        tabBtnDialer = findViewById(R.id.tabBtnDialer)
        tabBtnCalls = findViewById(R.id.tabBtnCalls)
        tabBtnSettings = findViewById(R.id.tabBtnSettings)
        tabIconDialer = findViewById(R.id.tabIconDialer)
        tabIconCalls = findViewById(R.id.tabIconCalls)
        tabIconSettings = findViewById(R.id.tabIconSettings)
        tabLabelDialer = findViewById(R.id.tabLabelDialer)
        tabLabelCalls = findViewById(R.id.tabLabelCalls)
        tabLabelSettings = findViewById(R.id.tabLabelSettings)

        tabBtnDialer.setOnClickListener { switchTab("dialer") }
        tabBtnCalls.setOnClickListener { switchTab("calls") }
        tabBtnSettings.setOnClickListener { switchTab("settings") }

        // Settings-tab views
        statusDot = findViewById(R.id.statusDot)
        tvStatusText = findViewById(R.id.tvStatusText)
        tvUptime = findViewById(R.id.tvUptime)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)

        // Fresh log on every app (re)start — clear both the view and the service buffer
        tvLog.text = ""
        GatewayService.drainLogBuffer()
        btnStart = findViewById(R.id.btnStart)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnConfig = findViewById(R.id.btnConfig)
        btnInfo = findViewById(R.id.btnInfo)

        // Dialler-tab views
        tvDialNumber = findViewById(R.id.tvDialNumber)
        btnCall = findViewById(R.id.btnCall)

        // Calls-tab views
        callLogContainer = findViewById(R.id.callLogContainer)
        callLogScroll = findViewById(R.id.callLogScroll)
        tvCallsEmpty = findViewById(R.id.tvCallsEmpty)
        btnFilterIn = findViewById(R.id.btnFilterIn)
        btnFilterOut = findViewById(R.id.btnFilterOut)

        // In-call views
        inCallView = findViewById(R.id.inCallView)
        tvInCallStatus = findViewById(R.id.tvInCallStatus)
        tvInCallNumber = findViewById(R.id.tvInCallNumber)
        tvInCallTimer = findViewById(R.id.tvInCallTimer)
        btnInCallEnd = findViewById(R.id.btnInCallEnd)
        btnInCallEnd.setOnClickListener { endCallFromInCallScreen() }

        // Settings-tab click listeners
        btnStart.setOnClickListener { if (running) stopGateway() else startGateway() }
        btnCopyLog.setOnClickListener { copyLog() }
        btnConfig.setOnClickListener { showConfigDialog() }
        btnInfo.setOnClickListener { showInfoDialog() }
        // Calls-tab click listeners
        findViewById<ImageButton>(R.id.btnCallLogClear).setOnClickListener { confirmClearCallLog() }
        btnFilterIn.setOnClickListener { setCallLogFilter("IN") }
        btnFilterOut.setOnClickListener { setCallLogFilter("OUT") }

        setupDialler()
        preloadCallLog()

        requestPermissions()
        requestBatteryOptimizationExemption()
        requestDefaultDialerRole()

        // Auto-start gateway if autoconnect enabled and credentials configured
        autoStartGateway()
    }

    private fun autoStartGateway() {
        if (running) return
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        if (!prefs.getBoolean("autoconnect", true)) return
        val server = prefs.getString("server", "") ?: ""
        val user = prefs.getString("user", "") ?: ""
        if (server.isEmpty() || user.isEmpty()) return
        val port = prefs.getInt("port", 5060)
        val pass = prefs.getString("pass", "") ?: ""
        GatewayService.start(this, server, port, user, pass)
        running = true
        updateToggleButton()
        appendLog("Auto-starting gateway: $user@$server:$port")
    }

    // ── Tab Navigation ───────────────────────────────────

    private fun switchTab(tab: String) {
        if (tab == currentTab) return
        currentTab = tab

        tabDialer.visibility = if (tab == "dialer") View.VISIBLE else View.GONE
        tabCalls.visibility = if (tab == "calls") View.VISIBLE else View.GONE
        tabSettings.visibility = if (tab == "settings") View.VISIBLE else View.GONE

        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_hint)

        tabIconDialer.setColorFilter(if (tab == "dialer") activeColor else inactiveColor)
        tabIconCalls.setColorFilter(if (tab == "calls") activeColor else inactiveColor)
        tabIconSettings.setColorFilter(if (tab == "settings") activeColor else inactiveColor)

        tabLabelDialer.setTextColor(if (tab == "dialer") activeColor else inactiveColor)
        tabLabelCalls.setTextColor(if (tab == "calls") activeColor else inactiveColor)
        tabLabelSettings.setTextColor(if (tab == "settings") activeColor else inactiveColor)

        // Refresh call log when switching to Calls tab
        if (tab == "calls") {
            refreshCallLog()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inCallOpen) {
            return // must use END CALL
        } else if (currentTab != "dialer") {
            switchTab("dialer")
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(GatewayService.STATUS_ACTION)
            addAction(GatewayService.LOG_ACTION)
        }
        registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)

        // Replay any log messages buffered while activity was paused
        val buffered = GatewayService.drainLogBuffer()
        for (msg in buffered) {
            appendLog(msg)
        }

        if (onlineSince > 0) {
            uptimeHandler.removeCallbacks(uptimeRunnable)
            uptimeRunnable.run()
        }

        if (inCallOpen) {
            if (com.callagent.gateway.gsm.GsmCallManager.activeCall == null &&
                System.currentTimeMillis() - inCallOpenTime > 2000) {
                closeInCallScreen()
            } else {
                if (callStartTime > 0) callTimerRunnable.run()
                callTimerHandler.removeCallbacks(gsmPollRunnable)
                callTimerHandler.postDelayed(gsmPollRunnable, 500)
            }
        } else if (currentTab == "dialer") {
            refreshCallButtonState()
        }

        // Refresh calls tab if visible
        if (currentTab == "calls") {
            refreshCallLog()
        }
    }

    override fun onPause() {
        super.onPause()
        uptimeHandler.removeCallbacks(uptimeRunnable)
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        unregisterReceiver(statusReceiver)
    }

    // ── Config Dialog ────────────────────────────────────

    private fun showConfigDialog() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_config, null)

        val etServer = view.findViewById<EditText>(R.id.etSipServer)
        val etPort = view.findViewById<EditText>(R.id.etSipPort)
        val etUser = view.findViewById<EditText>(R.id.etSipUser)
        val etPassword = view.findViewById<EditText>(R.id.etSipPassword)
        val cbAutoconnect = view.findViewById<CheckBox>(R.id.cbAutoconnect)

        etServer.setText(prefs.getString("server", "sip.callagent.pro"))
        etPort.setText(prefs.getInt("port", 5060).toString())
        etUser.setText(prefs.getString("user", ""))
        etPassword.setText(prefs.getString("pass", ""))
        cbAutoconnect.isChecked = prefs.getBoolean("autoconnect", true)

        AlertDialog.Builder(this)
            .setTitle("SIP Configuration")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val server = etServer.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull() ?: 5060
                val user = etUser.text.toString().trim()
                val pass = etPassword.text.toString().trim()
                prefs.edit()
                    .putString("server", server)
                    .putInt("port", port)
                    .putString("user", user)
                    .putString("pass", pass)
                    .putBoolean("autoconnect", cbAutoconnect.isChecked)
                    .apply()
                appendLog("Config saved: $user@$server:$port (autoconnect=${cbAutoconnect.isChecked})")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Info Dialog ─────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun showInfoDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_info, null)

        val tvGsmNetwork = view.findViewById<TextView>(R.id.tvGsmNetwork)
        val tvNetworkType = view.findViewById<TextView>(R.id.tvNetworkType)
        val tvGsmSignal = view.findViewById<TextView>(R.id.tvGsmSignal)
        val tvCellId = view.findViewById<TextView>(R.id.tvCellId)
        val tvImei = view.findViewById<TextView>(R.id.tvImei)
        val tvPhoneNumber = view.findViewById<TextView>(R.id.tvPhoneNumber)
        val tvWifiNetwork = view.findViewById<TextView>(R.id.tvWifiNetwork)
        val tvWifiType = view.findViewById<TextView>(R.id.tvWifiType)
        val tvWifiSignal = view.findViewById<TextView>(R.id.tvWifiSignal)
        val tvWifiIp = view.findViewById<TextView>(R.id.tvWifiIp)
        val tvPublicIp = view.findViewById<TextView>(R.id.tvPublicIp)

        val hasPhonePerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val hasLocationPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        tvGsmNetwork.text = tm.networkOperatorName.ifEmpty { "N/A" }

        if (hasPhonePerm) {
            @Suppress("DEPRECATION")
            val netType = tm.networkType
            tvNetworkType.text = networkTypeName(netType)
        } else {
            tvNetworkType.text = "No permission"
        }

        if (hasPhonePerm) {
            try {
                val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val subMgr = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                    subMgr.getPhoneNumber(SubscriptionManager.getDefaultSubscriptionId())
                } else {
                    @Suppress("DEPRECATION")
                    tm.line1Number
                }
                tvPhoneNumber.text = if (number.isNullOrEmpty()) "N/A" else number
            } catch (_: Exception) {
                tvPhoneNumber.text = "N/A"
            }

            try {
                val imei = tm.getImei(0)
                    ?: tm.getImei(1)
                    ?: tm.imei
                tvImei.text = imei ?: "N/A"
            } catch (_: SecurityException) {
                try {
                    val androidId = android.provider.Settings.Secure.getString(
                        contentResolver, android.provider.Settings.Secure.ANDROID_ID
                    )
                    tvImei.text = androidId ?: "N/A"
                } catch (_: Exception) {
                    tvImei.text = "N/A"
                }
            } catch (_: Exception) {
                tvImei.text = "N/A"
            }
        } else {
            tvPhoneNumber.text = "No permission"
            tvImei.text = "No permission"
        }

        if (hasLocationPerm) {
            try {
                val cellInfo = tm.allCellInfo?.firstOrNull()
                when (cellInfo) {
                    is CellInfoLte -> {
                        tvCellId.text = cellInfo.cellIdentity.ci.let {
                            if (it == Int.MAX_VALUE) "N/A" else it.toString()
                        }
                        tvGsmSignal.text = "${cellInfo.cellSignalStrength.level}/4 (${cellInfo.cellSignalStrength.dbm} dBm)"
                    }
                    is CellInfoGsm -> {
                        tvCellId.text = cellInfo.cellIdentity.cid.let {
                            if (it == Int.MAX_VALUE) "N/A" else it.toString()
                        }
                        tvGsmSignal.text = "${cellInfo.cellSignalStrength.level}/4 (${cellInfo.cellSignalStrength.dbm} dBm)"
                    }
                    is CellInfoWcdma -> {
                        tvCellId.text = cellInfo.cellIdentity.cid.let {
                            if (it == Int.MAX_VALUE) "N/A" else it.toString()
                        }
                        tvGsmSignal.text = "${cellInfo.cellSignalStrength.level}/4 (${cellInfo.cellSignalStrength.dbm} dBm)"
                    }
                    is CellInfoNr -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val id = cellInfo.cellIdentity as? android.telephony.CellIdentityNr
                            tvCellId.text = id?.nci?.let {
                                if (it == Long.MAX_VALUE) "N/A" else it.toString()
                            } ?: "N/A"
                            tvGsmSignal.text = "${cellInfo.cellSignalStrength.level}/4 (${cellInfo.cellSignalStrength.dbm} dBm)"
                        }
                    }
                    else -> {
                        tvCellId.text = "N/A"
                        tvGsmSignal.text = "N/A"
                    }
                }
            } catch (_: Exception) {
                tvCellId.text = "N/A"
                tvGsmSignal.text = "N/A"
            }
        } else {
            tvCellId.text = "No permission"
            tvGsmSignal.text = "No permission"
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifiInfo = wm.connectionInfo
        if (wifiInfo != null && wifiInfo.networkId != -1) {
            @Suppress("DEPRECATION")
            val ssid = wifiInfo.ssid?.replace("\"", "") ?: "N/A"
            tvWifiNetwork.text = if (ssid == "<unknown ssid>") "N/A (no location permission)" else ssid
            @Suppress("DEPRECATION")
            val freq = wifiInfo.frequency
            tvWifiType.text = when {
                freq in 2400..2500 -> "2.4 GHz"
                freq in 5000..5900 -> "5 GHz"
                freq in 5925..7125 -> "6 GHz"
                else -> "${freq} MHz"
            }
            @Suppress("DEPRECATION")
            val rssi = wifiInfo.rssi
            val level = WifiManager.calculateSignalLevel(rssi, 5)
            tvWifiSignal.text = "$level/4 ($rssi dBm)"

            @Suppress("DEPRECATION")
            val ip = wifiInfo.ipAddress
            if (ip != 0) {
                tvWifiIp.text = String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff, ip shr 8 and 0xff,
                    ip shr 16 and 0xff, ip shr 24 and 0xff
                )
            } else {
                tvWifiIp.text = "N/A"
            }
        } else {
            tvWifiNetwork.text = "Not connected"
            tvWifiType.text = "—"
            tvWifiSignal.text = "—"
            tvWifiIp.text = "—"
        }

        Thread {
            val pubIp = try {
                java.net.URL("https://api.ipify.org").readText().trim()
            } catch (_: Exception) {
                "N/A"
            }
            runOnUiThread { tvPublicIp.text = pubIp }
        }.start()

        val btnCheck = view.findViewById<Button>(R.id.btnCheckSupport)
        val resultsContainer = view.findViewById<LinearLayout>(R.id.checkResultsContainer)
        btnCheck.setOnClickListener {
            btnCheck.isEnabled = false
            btnCheck.text = "Checking…"
            resultsContainer.visibility = View.VISIBLE
            resultsContainer.removeAllViews()
            runGatewayChecks(resultsContainer) {
                btnCheck.text = "Check Gateway Support"
                btnCheck.isEnabled = true
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Device Info")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }

    // ── Gateway Support Checks ─────────────────────────

    private fun runGatewayChecks(container: LinearLayout, onDone: () -> Unit) {
        val dp = resources.displayMetrics.density
        val greenColor = Color.parseColor("#16A34A")
        val redColor = Color.parseColor("#DC2626")
        val grayColor = Color.parseColor("#6B7280")

        fun addSectionHeader(title: String) {
            val tv = TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, (8 * dp).toInt(), 0, (2 * dp).toInt())
            }
            container.addView(tv)
        }

        fun addResultRow(label: String, passed: Boolean, detail: String = "") {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            }
            val icon = TextView(this).apply {
                text = if (passed) "\u2713" else "\u2717"
                textSize = 14f
                setTextColor(if (passed) greenColor else redColor)
                layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tvLabel = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (passed) greenColor else redColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(icon)
            row.addView(tvLabel)
            if (detail.isNotEmpty()) {
                val tvDetail = TextView(this).apply {
                    text = detail
                    textSize = 11f
                    setTextColor(grayColor)
                }
                row.addView(tvDetail)
            }
            container.addView(row)
        }

        Thread {
            data class CheckResult(val label: String, val passed: Boolean, val detail: String = "")
            val results = mutableListOf<CheckResult>()

            val hasRecordAudio = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val hasPhoneState = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            val hasAnswerCalls = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED

            val hasCallPhone = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val hasCaptureOutput = ContextCompat.checkSelfPermission(
                this, "android.permission.CAPTURE_AUDIO_OUTPUT"
            ) == PackageManager.PERMISSION_GRANTED

            results.add(CheckResult("RECORD_AUDIO", hasRecordAudio))
            results.add(CheckResult("CAPTURE_AUDIO_OUTPUT", hasCaptureOutput, if (hasCaptureOutput) "Magisk" else "needs Magisk"))
            results.add(CheckResult("ANSWER_PHONE_CALLS", hasAnswerCalls))
            results.add(CheckResult("CALL_PHONE", hasCallPhone))
            results.add(CheckResult("READ_PHONE_STATE", hasPhoneState))

            val telecomMgr = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val isDefaultDialer = packageName == telecomMgr.defaultDialerPackage
            results.add(CheckResult("Default Dialer", isDefaultDialer, if (isDefaultDialer) "" else "required for InCallService"))

            data class SourceTest(val source: Int, val name: String, val rate: Int)
            val sources = listOf(
                SourceTest(MediaRecorder.AudioSource.VOICE_DOWNLINK, "VOICE_DOWNLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_UPLINK, "VOICE_UPLINK", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_CALL, "VOICE_CALL", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION", 8000),
                SourceTest(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION", 8000),
                SourceTest(MediaRecorder.AudioSource.MIC, "MIC", 8000)
            )

            val sourceResults = mutableListOf<CheckResult>()
            for (src in sources) {
                var ok = false
                var detail = ""
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        src.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf > 0) {
                        val rec = AudioRecord(
                            src.source, src.rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBuf.coerceAtLeast(4096)
                        )
                        if (rec.state == AudioRecord.STATE_INITIALIZED) {
                            try {
                                rec.startRecording()
                                val buf = ByteArray(320)
                                val read = rec.read(buf, 0, buf.size)
                                ok = read > 0
                                if (!ok) detail = "read=$read"
                                rec.stop()
                            } catch (e: Exception) {
                                detail = e.message?.take(30) ?: "start failed"
                            }
                        } else {
                            detail = "init failed"
                        }
                        rec.release()
                    } else {
                        detail = "invalid buffer"
                    }
                } catch (e: Exception) {
                    detail = e.message?.take(30) ?: "error"
                }
                sourceResults.add(CheckResult(src.name, ok, detail))
            }

            val aecAvail = AcousticEchoCanceler.isAvailable()
            val nsAvail = NoiseSuppressor.isAvailable()

            data class PropCheck(val prop: String, val expected: String, val label: String)
            val propChecks = listOf(
                PropCheck("voice.record.conc.disabled", "false", "Concurrent recording"),
                PropCheck("voice.playback.conc.disabled", "false", "Concurrent playback"),
                PropCheck("voice.voip.conc.disabled", "false", "Concurrent VoIP")
            )
            val propResults = mutableListOf<CheckResult>()
            for (pc in propChecks) {
                val value = try {
                    @Suppress("PrivateApi")
                    val cls = Class.forName("android.os.SystemProperties")
                    val get = cls.getMethod("get", String::class.java, String::class.java)
                    get.invoke(null, pc.prop, "") as String
                } catch (_: Exception) { "" }
                val ok = value == pc.expected
                propResults.add(CheckResult(pc.label, ok, if (value.isNotEmpty()) "$value" else "not set"))
            }

            val hasRoot = try {
                // Modern Magisk doesn't place su at fixed paths — try executing it.
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val exitCode = proc.waitFor()
                proc.destroy()
                exitCode == 0
            } catch (_: Exception) {
                // Fallback: check legacy paths
                try {
                    java.io.File("/system/bin/su").exists() ||
                        java.io.File("/system/xbin/su").exists() ||
                        java.io.File("/sbin/su").exists()
                } catch (_: Exception) { false }
            }

            val hasUsableSource = sourceResults.any { it.passed }
            val hasDownlink = sourceResults.firstOrNull { it.label == "VOICE_DOWNLINK" }?.passed == true

            runOnUiThread {
                addSectionHeader("Permissions")
                for (r in results) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("Audio Sources")
                for (r in sourceResults) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("Audio Effects")
                addResultRow("AcousticEchoCanceler", aecAvail)
                addResultRow("NoiseSuppressor", nsAvail)

                addSectionHeader("System Properties")
                for (r in propResults) addResultRow(r.label, r.passed, r.detail)

                addSectionHeader("System")
                addResultRow("Root (su)", hasRoot, if (hasRoot) "" else "needed for Magisk")

                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                    ).apply { topMargin = (8 * dp).toInt(); bottomMargin = (8 * dp).toInt() }
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.border_card))
                }
                container.addView(divider)

                val gatewayReady = hasRecordAudio && isDefaultDialer && hasUsableSource && hasCaptureOutput
                val verdict = TextView(this).apply {
                    text = if (gatewayReady) {
                        val src = if (hasDownlink) "VOICE_DOWNLINK" else
                            sourceResults.firstOrNull { it.passed }?.label ?: "?"
                        "\u2713 Gateway supported (capture: $src)"
                    } else {
                        val missing = mutableListOf<String>()
                        if (!hasRecordAudio) missing.add("RECORD_AUDIO")
                        if (!hasCaptureOutput) missing.add("CAPTURE_AUDIO_OUTPUT")
                        if (!isDefaultDialer) missing.add("Default Dialer")
                        if (!hasUsableSource) missing.add("audio source")
                        "\u2717 Not ready: missing ${missing.joinToString(", ")}"
                    }
                    textSize = 13f
                    setTextColor(if (gatewayReady) greenColor else redColor)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                container.addView(verdict)

                onDone()
            }
        }.start()
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        else -> "Unknown"
    }

    // ── Call Log (Calls Tab) ─────────────────────────────

    private fun setCallLogFilter(filter: String) {
        callLogFilter = filter

        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = 0xFF374151.toInt()
        val activeIconTint = ColorStateList.valueOf(Color.WHITE)
        val inactiveIconTint = ColorStateList.valueOf(0xFF9CA3AF.toInt())

        if (filter == "IN") {
            btnFilterIn.backgroundTintList = ColorStateList.valueOf(activeColor)
            btnFilterIn.imageTintList = activeIconTint
            btnFilterOut.backgroundTintList = ColorStateList.valueOf(inactiveColor)
            btnFilterOut.imageTintList = inactiveIconTint
        } else {
            btnFilterOut.backgroundTintList = ColorStateList.valueOf(activeColor)
            btnFilterOut.imageTintList = activeIconTint
            btnFilterIn.backgroundTintList = ColorStateList.valueOf(inactiveColor)
            btnFilterIn.imageTintList = inactiveIconTint
        }

        // Use cached data — instant switch, no I/O
        showCallLog()
    }

    /** Pre-load both IN and OUT lists off the main thread so tab switching is instant */
    private fun preloadCallLog() {
        val ctx = this
        Thread {
            val all = CallLogStore.getEntries(ctx)
            cachedInEntries = all.filter { it.direction == "IN" }.take(MAX_CALL_LOG)
            cachedOutEntries = all.filter { it.direction == "OUT" }.take(MAX_CALL_LOG)
            callLogBuiltIn = false
            callLogBuiltOut = false
            // Build current filter's UI
            runOnUiThread { showCallLog() }
        }.start()
    }

    private fun refreshCallLog() {
        preloadCallLog()
    }

    /** Show the already-cached list for the current filter — runs on UI thread, no I/O */
    private fun showCallLog() {
        val entries = if (callLogFilter == "IN") cachedInEntries else cachedOutEntries
        buildCallLogUI(entries)
    }

    private fun buildCallLogUI(entries: List<CallLogEntry>) {
        callLogContainer.removeAllViews()

        if (entries.isEmpty()) {
            callLogScroll.visibility = View.GONE
            tvCallsEmpty.visibility = View.VISIBLE
            return
        }

        callLogScroll.visibility = View.VISIBLE
        tvCallsEmpty.visibility = View.GONE

        val dp = resources.displayMetrics.density
        val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
        val cardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF374151.toInt())
            cornerRadius = 16 * dp
        }

        for (entry in entries) {
            val number = entry.number.ifEmpty { "Unknown" }

            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (4 * dp).toInt()
                }
                background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x40FFFFFF),
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF1F2937.toInt())
                        cornerRadius = 12 * dp
                    },
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFFFFFFF.toInt())
                        cornerRadius = 12 * dp
                    }
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { openDiallerWithNumber(number) }
            }

            // Two-line text block (number + details)
            val textBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvNum = TextView(this).apply {
                text = number
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFFFFFFF.toInt())
            }

            // Second line: direction icon + date + duration
            val detailRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * dp).toInt()
                }
            }

            val dirIcon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt())
                setImageResource(
                    if (entry.direction == "IN") R.drawable.ic_call_incoming
                    else R.drawable.ic_call_outgoing
                )
                setColorFilter(0xFF9CA3AF.toInt())
            }

            val tvDate = TextView(this).apply {
                text = dateFmt.format(Date(entry.timestamp))
                textSize = 13f
                maxLines = 1
                setTextColor(0xFF9CA3AF.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (6 * dp).toInt()
                }
            }

            val tvDur = TextView(this).apply {
                text = formatDurationCompact(entry.durationSec)
                textSize = 13f
                maxLines = 1
                setTextColor(0xFF9CA3AF.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (12 * dp).toInt()
                }
            }

            detailRow.addView(dirIcon)
            detailRow.addView(tvDate)
            detailRow.addView(tvDur)

            textBlock.addView(tvNum)
            textBlock.addView(detailRow)

            // Call button on right side
            val btnCall = ImageView(this).apply {
                val btnSize = (40 * dp).toInt()
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginStart = (12 * dp).toInt()
                }
                setImageResource(R.drawable.ic_phone_call)
                setColorFilter(0xFF10B981.toInt())
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x4010B981),
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF374151.toInt())
                        cornerRadius = 20 * dp
                    },
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFFFFFFF.toInt())
                        cornerRadius = 20 * dp
                    }
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { openDiallerWithNumber(number) }
            }

            rowView.addView(textBlock)
            rowView.addView(btnCall)
            callLogContainer.addView(rowView)
        }
    }

    private fun openDiallerWithNumber(number: String) {
        tvDialNumber.text = number
        switchTab("dialer")
    }

    private fun confirmClearCallLog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Call Log")
            .setMessage("Delete all call history? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                CallLogStore.clear(this)
                startService(Intent(this, GatewayService::class.java).apply {
                    action = GatewayService.ACTION_RELOAD_STATS
                })
                Toast.makeText(this, "Call log cleared", Toast.LENGTH_SHORT).show()
                refreshCallLog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Dialler ──────────────────────────────────────────

    private fun setupDialler() {
        val digitButtons = mapOf(
            R.id.btnDial0 to "0", R.id.btnDial1 to "1", R.id.btnDial2 to "2",
            R.id.btnDial3 to "3", R.id.btnDial4 to "4", R.id.btnDial5 to "5",
            R.id.btnDial6 to "6", R.id.btnDial7 to "7", R.id.btnDial8 to "8",
            R.id.btnDial9 to "9", R.id.btnDialStar to "*", R.id.btnDialHash to "#",
            R.id.btnDialPlus to "+"
        )
        for ((id, digit) in digitButtons) {
            findViewById<TextView>(id).setOnClickListener { appendDigit(digit) }
        }

        findViewById<TextView>(R.id.btnBackspace).setOnClickListener { deleteLastDigit() }
        findViewById<TextView>(R.id.btnBackspace).setOnLongClickListener {
            tvDialNumber.text = ""
            true
        }

        btnCall.setOnClickListener { onCallButtonPressed() }
    }

    private fun refreshCallButtonState() {
        gsmCallActive = com.callagent.gateway.gsm.GsmCallManager.activeCall != null
        updateCallButton()
    }

    private fun appendDigit(digit: String) {
        tvDialNumber.append(digit)
    }

    private fun deleteLastDigit() {
        val current = tvDialNumber.text.toString()
        if (current.isNotEmpty()) {
            tvDialNumber.text = current.dropLast(1)
        }
    }

    private fun onCallButtonPressed() {
        if (inCallOpen) return

        if (gsmCallActive || com.callagent.gateway.gsm.GsmCallManager.activeCall != null) {
            val num = com.callagent.gateway.gsm.GsmCallManager.currentNumber
                ?: tvDialNumber.text.toString().trim()
            if (num.isNotEmpty()) openInCallScreen(num)
            return
        }

        val number = tvDialNumber.text.toString().trim()
        if (number.isEmpty()) {
            Toast.makeText(this, "Enter a number to call", Toast.LENGTH_SHORT).show()
            return
        }

        openInCallScreen(number)

        if (running) {
            val intent = Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_DIAL
                putExtra(GatewayService.EXTRA_NUMBER, number)
            }
            startService(intent)
        } else {
            com.callagent.gateway.gsm.GsmCallManager.makeCall(this, number)
        }

        appendLog("Dialling $number")
    }

    // ── In-Call Screen ───────────────────────────────────

    private var inCallCloseScheduled = false

    /** Show "Call ended" briefly then close the in-call screen */
    private fun scheduleInCallClose() {
        if (!inCallOpen || inCallCloseScheduled) return
        inCallCloseScheduled = true
        tvInCallStatus.text = "Call ended"
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callTimerHandler.postDelayed({ closeInCallScreen() }, 1500)
    }

    private fun openInCallScreen(number: String) {
        inCallOpen = true
        inCallOpenTime = System.currentTimeMillis()
        inCallCloseScheduled = false
        callStartTime = 0L
        lastGsmPollState = -1
        tvInCallNumber.text = number
        tvInCallStatus.text = "Calling..."
        tvInCallTimer.visibility = View.GONE
        viewBeforeInCall = currentTab
        // Hide tabs, show in-call overlay
        tabbedRoot.visibility = View.GONE
        inCallView.visibility = View.VISIBLE
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callTimerHandler.postDelayed(gsmPollRunnable, 500)
    }

    private fun closeInCallScreen() {
        if (!inCallOpen) return
        inCallOpen = false
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callStartTime = 0L
        lastGsmPollState = -1
        inCallView.visibility = View.GONE
        tabbedRoot.visibility = View.VISIBLE
        gsmCallActive = false
        updateCallButton()
        // Refresh calls list if returning to calls tab
        if (currentTab == "calls") {
            refreshCallLog()
        }
    }

    private fun endCallFromInCallScreen() {
        val call = com.callagent.gateway.gsm.GsmCallManager.activeCall
        if (call != null) {
            tvInCallStatus.text = "Ending..."
            com.callagent.gateway.gsm.GsmCallManager.hangupCall()
        } else {
            closeInCallScreen()
        }
    }

    private fun updateCallButton() {
        runOnUiThread {
            if (gsmCallActive) {
                btnCall.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DC2626"))
            } else {
                btnCall.backgroundTintList = null
            }
        }
    }

    // ── Gateway Control ──────────────────────────────────

    private fun startGateway() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val server = prefs.getString("server", "") ?: ""
        val port = prefs.getInt("port", 5060)
        val user = prefs.getString("user", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""

        if (server.isEmpty() || user.isEmpty()) {
            appendLog("ERROR: Open config and set server + username first")
            return
        }

        GatewayService.start(this, server, port, user, pass)

        running = true
        updateToggleButton()

        appendLog("Starting gateway: $user@$server:$port")
    }

    private fun stopGateway() {
        GatewayService.stop(this)
        running = false
        updateToggleButton()
    }

    private fun updateToggleButton() {
        if (running) {
            btnStart.text = "STOP"
            btnStart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#DC2626"))
        } else {
            btnStart.text = "START"
            btnStart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#16A34A"))
        }
    }

    private fun updateStatus(state: String, info: String) {
        val dotColor = when (state) {
            "IDLE" -> "#16A34A"
            "BRIDGED" -> "#16A34A"
            "STARTING", "GSM_RINGING", "GSM_ANSWERED",
            "SIP_CALLING", "SIP_RINGING", "GSM_DIALING",
            "TEARING_DOWN" -> "#EAB308"
            else -> "#DC2626"
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor(dotColor))

        val text = when (state) {
            "IDLE" -> if (info == "SIP registered") "Registered — Ready" else info
            "BRIDGED" -> "Call active: $info"
            "STOPPED" -> "Stopped"
            "ERROR" -> info
            "STARTING" -> info
            else -> info
        }
        tvStatusText.text = text
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            tvLog.append("$ts  $msg\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun copyLog() {
        val logText = tvLog.text.toString()
        if (logText.isEmpty()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("callagent log", logText))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ── Helpers ──────────────────────────────────────────

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun formatDurationCompact(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return ContextCompat.getColor(this, tv.resourceId)
    }

    // ── Permissions ─────────────────────────────────────

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perms.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
        }
    }

    private fun requestDefaultDialerRole() {
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (packageName == tm.defaultDialerPackage) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
            if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !rm.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_DEFAULT_DIALER)
            }
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivityForResult(intent, REQ_DEFAULT_DIALER)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DEFAULT_DIALER) {
            if (resultCode == RESULT_OK) {
                appendLog("Set as default phone app")
            } else {
                appendLog("WARN: Not set as default phone app — GSM call handling disabled")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first.substringAfterLast('.') }
            if (denied.isNotEmpty()) {
                appendLog("WARN: Denied permissions: ${denied.joinToString()}")
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 100
        private const val REQ_DEFAULT_DIALER = 101
        private const val MAX_CALL_LOG = 20
    }
}
