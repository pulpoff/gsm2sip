package com.callagent.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioManager
import android.widget.SeekBar
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.net.wifi.WifiManager
import android.app.role.RoleManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.callagent.gateway.service.CallLogEntry
import com.callagent.gateway.service.CallLogStore
import com.callagent.gateway.sms.SmsOutbox
import com.callagent.gateway.service.GatewayService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Settings-tab views
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView

    // Dialler-tab views

    // Calls-tab views
    private var callLogFilter = "IN"

    // Pre-cached call log lists (built once, swapped on filter change)
    private var cachedInEntries: List<CallLogEntry> = emptyList()
    private var cachedOutEntries: List<CallLogEntry> = emptyList()
    private var callLogBuiltIn = false
    private var callLogBuiltOut = false

    // Tab containers + bottom bar
    private lateinit var tabbedRoot: LinearLayout
    private lateinit var tabHome: View
    private lateinit var tabConfig: View
    private lateinit var tvHomeStatusPill: TextView
    private lateinit var tvHomeTlsBadge: TextView
    private lateinit var tvHomeSrtpBadge: TextView
    private lateinit var tvNetMobile: TextView
    private lateinit var tvNetWifi: TextView
    private val netHandler = Handler(Looper.getMainLooper())
    private val netRunnable = object : Runnable {
        override fun run() {
            refreshNetworkInfo()
            // Signal and link speed drift constantly; five seconds is often
            // enough to be useful without being a battery drain.
            netHandler.postDelayed(this, 5000)
        }
    }
    private lateinit var homeCallCard: View
    private lateinit var tvHomeCallDirection: TextView
    private lateinit var tvHomeCallTimer: TextView
    private lateinit var tvHomeCallFrom: TextView
    private lateinit var tvHomeCallTo: TextView
    private lateinit var btnHomeMute: Button
    private lateinit var btnHomeSnoop: Button
    private lateinit var btnHomeEnd: Button
    private lateinit var homeTrafficList: LinearLayout
    private lateinit var tvHomeTrafficEmpty: TextView
    private lateinit var btnFilterAll: Button
    private lateinit var btnFilterIncoming: Button
    private lateinit var btnFilterOutgoing: Button
    /** "all" | "in" | "out" — which calls the home list shows.  A missed call
     *  is an inbound one that never carried audio, so it belongs under
     *  Incoming rather than in a category of its own. */
    private var callFilter = "all"
    private var agentMuted = false

    private lateinit var tabLogs: LinearLayout
    private var currentTab = ""

    // In-call views
    private lateinit var inCallView: LinearLayout
    private lateinit var tvInCallStatus: TextView
    private lateinit var tvInCallNumber: TextView
    private lateinit var tvInCallTimer: TextView
    private lateinit var btnInCallEnd: Button
    private lateinit var btnInCallMonitor: Button
    private var monitoring = false
    /** True while a call is bridged, so SNOOP is only offered when it can work. */
    private var callLive = false
    /** Whether the gateway is actually registered, as opposed to merely
     *  running.  Drives the pill and gates the manual retry. */
    private var gatewayOnline = false
    /** Registration state as reported by the service, rather than guessed from
     *  the status text.  See GatewayService.broadcastStatus. */
    private var sipRegistered = false
    private var inCallOpen = false
    private var inCallOpenTime = 0L
    private var viewBeforeInCall = "dialer"
    private var callStartTime = 0L
    /** When the service says the bridge came up.  Authoritative: the activity
     *  can be started, stopped and restarted several times during one call. */
    private var serviceCallStart = 0L
    private var lastGsmPollState = -1
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            if (callStartTime > 0) {
                val elapsedMs = System.currentTimeMillis() - callStartTime
                val elapsed = elapsedMs / 1000
                val t = if (elapsed >= 3600) {
                    String.format(
                        "%d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60
                    )
                } else {
                    String.format("%02d:%02d", elapsed / 60, elapsed % 60)
                }
                tvInCallTimer.text = t
                if (::tvHomeCallTimer.isInitialized) tvHomeCallTimer.text = t
                // Tick on the second boundary of the call, not 1000ms after
                // whenever this happened to run: a flat delay accumulates the
                // handler's own latency, so the display drifts off the real
                // elapsed time and eventually skips or repeats a second.
                callTimerHandler.postDelayed(this, 1000 - (elapsedMs % 1000))
            }
        }
    }
    /**
     * Arm the call timer against the service's start time.
     *
     * Two things were wrong with starting it from the UI's own clock.  The
     * activity only hears about a call when a state change is broadcast, so
     * reopening the app mid-call restarted the count at 00:00; and the ticker
     * is cancelled in onPause but was only ever re-armed when callStartTime
     * was still zero, so coming back to a live call showed a frozen number.
     * Re-arming unconditionally is safe — the pending callback is removed
     * first — and the elapsed time is now real in both cases.
     */
    /** Keep a call button's icon in step with its label — the two describe
     *  the same action, so they have to change together. */
    private fun setCallButtonState(button: Button, label: String, iconRes: Int) {
        button.text = label
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, iconRes, 0, 0)
    }

    private fun startCallTimer() {
        callStartTime = if (serviceCallStart > 0) serviceCallStart else System.currentTimeMillis()
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerRunnable.run()
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
                    sipRegistered = intent.getBooleanExtra("registered", sipRegistered)
                    serviceCallStart = intent.getLongExtra("call_start", 0L)
                    updateStatus(state, info)

                    val newOnlineSince = intent.getLongExtra("online_since", 0L)
                    if (newOnlineSince != onlineSince) {
                        onlineSince = newOnlineSince
                        uptimeHandler.removeCallbacks(uptimeRunnable)
                        if (onlineSince > 0) {
                            uptimeRunnable.run()
                        } else {
                        }
                    }

                    val wasRunning = running
                    running = state != "STOPPED" && state != "ERROR"

                    val callActive = state in listOf(
                        "GSM_RINGING", "GSM_ANSWERED", "SIP_CALLING",
                        "SIP_RINGING", "BRIDGED", "GSM_DIALING", "TEARING_DOWN"
                    )
                    if (callActive != gsmCallActive) {
                        gsmCallActive = callActive
                    }

                    // No pop-up on an incoming call: the home view's live call
                    // card is the in-call UI now, and the old full-screen view
                    // appearing over it was just confusing.

                    if (inCallOpen) {
                        when (state) {
                            "GSM_DIALING" -> tvInCallStatus.text = "Calling..."
                            "GSM_ANSWERED", "SIP_CALLING" -> tvInCallStatus.text = "Connecting..."
                            "SIP_RINGING" -> tvInCallStatus.text = "Ringing..."
                            "BRIDGED" -> {
                                tvInCallStatus.text = "Connected"
                                tvInCallTimer.visibility = View.VISIBLE
                                startCallTimer()
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
        // Portrait lock, enforced at runtime as well as in the manifest.
        // A priv-app APK replaced in place is not always re-parsed by
        // PackageManager, so the manifest's screenOrientation can silently
        // stay at its previous value (dumpsys reports UNSPECIFIED).  Asking
        // for it here is immune to that staleness.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)

        // Tab containers
        tabbedRoot = findViewById(R.id.tabbedRoot)
        tabHome = findViewById(R.id.tabHome)
        tabConfig = findViewById(R.id.tabConfig)
        findViewById<View>(R.id.btnConfigBack).setOnClickListener { switchTab("home") }
        findViewById<View>(R.id.btnCfgSave).setOnClickListener { saveConfigFromView() }
        findViewById<View>(R.id.btnCfgClearRecents).setOnClickListener { confirmClearRecents() }
        tvHomeStatusPill = findViewById(R.id.tvHomeStatusPill)
        tvHomeTlsBadge = findViewById(R.id.tvHomeTlsBadge)
        tvHomeSrtpBadge = findViewById(R.id.tvHomeSrtpBadge)
        tvNetMobile = findViewById(R.id.tvNetMobile)
        tvNetWifi = findViewById(R.id.tvNetWifi)
        homeCallCard = findViewById(R.id.homeCallCard)
        tvHomeCallDirection = findViewById(R.id.tvHomeCallDirection)
        tvHomeCallTimer = findViewById(R.id.tvHomeCallTimer)
        tvHomeCallFrom = findViewById(R.id.tvHomeCallFrom)
        tvHomeCallTo = findViewById(R.id.tvHomeCallTo)
        btnHomeMute = findViewById(R.id.btnHomeMute)
        btnHomeSnoop = findViewById(R.id.btnHomeSnoop)
        btnHomeEnd = findViewById(R.id.btnHomeEnd)
        homeTrafficList = findViewById(R.id.homeTrafficList)
        tvHomeTrafficEmpty = findViewById(R.id.tvHomeTrafficEmpty)
        btnFilterAll = findViewById(R.id.btnFilterAll)
        btnFilterIncoming = findViewById(R.id.btnFilterIncoming)
        btnFilterOutgoing = findViewById(R.id.btnFilterOutgoing)
        btnFilterAll.setOnClickListener { setCallFilter("all") }
        btnFilterIncoming.setOnClickListener { setCallFilter("in") }
        btnFilterOutgoing.setOnClickListener { setCallFilter("out") }
        tvNetMobile.setOnClickListener { showLinkDetails(mobile = true) }
        tvNetWifi.setOnClickListener { showLinkDetails(mobile = false) }
        findViewById<View>(R.id.btnHomeMenu).setOnClickListener { openConfigView() }
        // Tapping the status pill retries the connection, the way the old
        // settings screen's reconnect button did.
        tvHomeStatusPill.setOnClickListener {
            if (gatewayOnline) {
                // Already registered — reconnecting would drop a working
                // registration and send SIP the server did not need.
                Toast.makeText(this, "Registered", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            appendLog("Reconnect requested")
            Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
            startService(Intent(this, GatewayService::class.java).apply {
                action = GatewayService.ACTION_RECONNECT
            })
        }
        btnHomeMute.setOnClickListener { toggleAgentMute() }
        btnHomeSnoop.setOnClickListener { toggleMonitor() }
        btnHomeEnd.setOnClickListener { endCallFromInCallScreen() }


        // Named and versioned at the top of settings.  It is the first thing
        // asked for when a change appears not to have taken, and this deploy
        // path can leave the running build and the file on disk disagreeing.
        findViewById<TextView>(R.id.tvCfgVersion).text = "v${BuildConfig.VERSION_NAME}"

        // Logs view — opened from the Settings header, back returns there
        // rather than to home, so the icon behaves like a drill-down.
        tabLogs = findViewById(R.id.tabLogs)
        findViewById<View>(R.id.btnCfgLogs).setOnClickListener { switchTab("logs") }
        findViewById<View>(R.id.btnLogsBack).setOnClickListener { switchTab("config") }
        findViewById<View>(R.id.btnLogsCopy).setOnClickListener { copyLog() }
        findViewById<View>(R.id.btnLogsClear).setOnClickListener { clearLog() }

        // Logs-view views
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)

        // Clear the view, but keep whatever the service has buffered: onResume
        // drains it into the view a moment later.  Discarding it here threw
        // away exactly the lines worth reading — the ones from before anyone
        // opened the app, which is when the gateway runs unattended.
        tvLog.text = ""

        // In-call views
        inCallView = findViewById(R.id.inCallView)
        tvInCallStatus = findViewById(R.id.tvInCallStatus)
        tvInCallNumber = findViewById(R.id.tvInCallNumber)
        tvInCallTimer = findViewById(R.id.tvInCallTimer)
        btnInCallEnd = findViewById(R.id.btnInCallEnd)
        btnInCallEnd.setOnClickListener { endCallFromInCallScreen() }
        btnInCallMonitor = findViewById(R.id.btnInCallMonitor)
        btnInCallMonitor.setOnClickListener { toggleMonitor() }

        requestPermissions()
        requestBatteryOptimizationExemption()
        requestDefaultDialerRole()

        // Nothing is visible until a tab is selected — switchTab() returns
        // early when the requested tab is already current, so the initial
        // state has to be applied explicitly.
        switchTab("home")
        setCallFilter("all")

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
        appendLog("Auto-starting gateway: $user@$server:$port")
    }

    // ── Tab Navigation ───────────────────────────────────

    /**
     * Show one of the top-level views.  Navigation is the header menu on the
     * home view now; there is no bottom bar.
     */
    private fun switchTab(tab: String) {
        if (tab == currentTab) return
        currentTab = tab

        tabHome.visibility = if (tab == "home") View.VISIBLE else View.GONE
        tabConfig.visibility = if (tab == "config") View.VISIBLE else View.GONE
        tabLogs.visibility = if (tab == "logs") View.VISIBLE else View.GONE

        when (tab) {
            "home" -> refreshHome()
            // The view scrolls as lines arrive, but only while it is visible;
            // opening it has to jump to the newest entry itself.
            "logs" -> svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /**
     * Settings as a full screen rather than a dialog — there are enough fields
     * that a modal is cramped, and a screen gives room to explain them.
     */
    // ── Own number, per SIM ─────────────────────────────

    /** One active SIM, as Settings needs to describe it. */
    private data class SimInfo(val slot: Int, val subId: Int, val caption: String)

    /** Own-number fields on screen, paired with the SIM slot each belongs to.
     *  Slot -1 is the no-SIM-readable case, bound to the legacy key. */
    private val ownNumberFields = mutableListOf<Pair<Int, EditText>>()

    private fun ownNumberKey(slot: Int) =
        if (slot < 0) "own_number" else "own_number_slot_$slot"

    /**
     * The SIMs that are actually live, in slot order.
     *
     * Slots the hardware has but which hold no SIM are deliberately absent:
     * a triple-SIM handset carrying one SIM needs one number, not three
     * fields two of which can only ever be wrong.
     */
    @SuppressLint("MissingPermission")
    private fun activeSims(): List<SimInfo> = try {
        val sm = getSystemService(SubscriptionManager::class.java)
        val tm = getSystemService(TelephonyManager::class.java)
        (sm?.activeSubscriptionInfoList ?: emptyList()).map { info ->
            val slot = info.simSlotIndex
            val carrier = (info.carrierName ?: info.displayName ?: "").toString().trim()
            // The IMEI belongs to the radio, so it names the slot; the ICCID
            // names the card in it.  Either tells two otherwise identical
            // rows apart, so show whichever the platform will hand over.
            val ident = runCatching { tm?.getImei(slot) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { "IMEI $it" }
                ?: info.iccId?.takeIf { it.isNotBlank() }
                    ?.let { "ICCID \u2026${it.takeLast(6)}" }
                ?: ""
            SimInfo(
                slot = slot,
                subId = info.subscriptionId,
                caption = listOfNotNull(
                    "SIM ${slot + 1}",
                    carrier.ifEmpty { null },
                    ident.ifEmpty { null }
                ).joinToString("  \u00b7  ")
            )
        }.sortedBy { it.slot }
    } catch (e: Exception) {
        android.util.Log.w("MainActivity", "Could not enumerate SIMs: ${e.message}")
        emptyList()
    }

    private fun buildOwnNumberFields(prefs: android.content.SharedPreferences) {
        val container = findViewById<LinearLayout>(R.id.llCfgOwnNumbers)
        container.removeAllViews()
        ownNumberFields.clear()

        val sims = activeSims()
        if (sims.isEmpty()) {
            // Nothing readable — still offer one field on the legacy key, so a
            // device that will not describe its SIMs stays configurable.
            val row = layoutInflater.inflate(R.layout.item_sim_number, container, false)
            val et = row.findViewById<EditText>(R.id.etSimNumber)
            et.setText(prefs.getString("own_number", ""))
            row.findViewById<TextView>(R.id.tvSimCaption).text = "No active SIM detected"
            container.addView(row)
            ownNumberFields += -1 to et
            return
        }

        val lowest = sims.first().slot
        for (sim in sims) {
            val row = layoutInflater.inflate(R.layout.item_sim_number, container, false)
            val et = row.findViewById<EditText>(R.id.etSimNumber)
            val stored = prefs.getString(ownNumberKey(sim.slot), "").orEmpty()
            // First run after upgrading there are no per-slot values yet; the
            // one legacy number belongs to whichever SIM was in use, so offer
            // it on the lowest active slot rather than making it be retyped.
            et.setText(
                stored.ifEmpty {
                    if (sim.slot == lowest) prefs.getString("own_number", "").orEmpty() else ""
                }
            )
            row.findViewById<TextView>(R.id.tvSimCaption).text = sim.caption
            container.addView(row)
            ownNumberFields += sim.slot to et
        }
    }

    private fun openConfigView() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        findViewById<EditText>(R.id.etCfgServer).setText(prefs.getString("server", "callagent.pro"))
        findViewById<EditText>(R.id.etCfgPort).setText(prefs.getInt("port", 5060).toString())
        findViewById<EditText>(R.id.etCfgUser).setText(prefs.getString("user", ""))
        findViewById<EditText>(R.id.etCfgPass).setText(prefs.getString("pass", ""))
        buildOwnNumberFields(prefs)
        findViewById<CheckBox>(R.id.cbCfgAutoconnect).isChecked =
            prefs.getBoolean("autoconnect", true)
        findViewById<CheckBox>(R.id.cbCfgUseStun).isChecked =
            prefs.getBoolean("use_stun", true)
        findViewById<CheckBox>(R.id.cbCfgTranslit).isChecked =
            prefs.getBoolean("translit_ascii", false)
        val cbTls = findViewById<CheckBox>(R.id.cbCfgTls)
        val cbSrtp = findViewById<CheckBox>(R.id.cbCfgSrtp)
        cbTls.isChecked = prefs.getBoolean("sip_tls", false)
        cbSrtp.isChecked = prefs.getBoolean("srtp_enabled", false)

        // SRTP follows TLS in the UI as well as in the code.  The setting is
        // disabled rather than hidden so it is visible that audio encryption
        // exists and what it depends on -- a hidden control just looks like a
        // missing feature.
        fun syncSrtpEnabled() {
            cbSrtp.isEnabled = cbTls.isChecked
            findViewById<TextView>(R.id.tvCfgSrtpHint).text = if (cbTls.isChecked) {
                "SDES-keyed SRTP (RFC 3711). Audio is encrypted when the server agrees; " +
                    "if it answers without SRTP the call continues unencrypted and the log says so."
            } else {
                "Requires TLS. The keys travel inside the SIP signalling, so over plain UDP " +
                    "they would be readable by anyone on the path."
            }
        }
        syncSrtpEnabled()
        cbTls.setOnCheckedChangeListener { _, _ -> syncSrtpEnabled() }
        findViewById<RadioButton>(
            when (prefs.getString("codec", "g722")) {
                "g711" -> R.id.rbCodecG711
                "both" -> R.id.rbCodecBoth
                else -> R.id.rbCodecG722
            }
        ).isChecked = true

        // -3..+3 as a 0..6 slider, so 0 sits in the middle.
        val agentVol = findViewById<SeekBar>(R.id.sbCfgAgentVolume)
        val agentVolLabel = findViewById<TextView>(R.id.tvCfgAgentVolume)
        fun stepText(step: Int) = if (step > 0) "+$step" else step.toString()
        agentVol.progress = prefs.getInt("agent_vol_step", 0).coerceIn(-3, 3) + 3
        agentVolLabel.text = stepText(agentVol.progress - 3)
        agentVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                agentVolLabel.text = stepText(value - 3)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        switchTab("config")
    }

    /**
     * Empty the traffic list — calls and messages together, since both are
     * rows in the same log.
     *
     * Confirmed first: it is not recoverable, and the log is the only record
     * the gateway keeps of what it has handled.
     */
    private fun confirmClearRecents() {
        val count = try { CallLogStore.getEntries(this).size } catch (_: Exception) { 0 }
        if (count == 0) {
            Toast.makeText(this, "Nothing to clear", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear recents?")
            .setMessage("Removes all $count calls and messages from the list. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                // Off the UI thread: clearing rewrites the stored blob, and
                // the list is rebuilt from disk straight afterwards.
                Thread {
                    try { CallLogStore.clear(this) } catch (_: Exception) {}
                    runOnUiThread {
                        if (currentTab == "home") refreshHome()
                        Toast.makeText(this, "Recents cleared", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveConfigFromView() {
        val server = findViewById<EditText>(R.id.etCfgServer).text.toString().trim()
        val port = findViewById<EditText>(R.id.etCfgPort).text.toString().trim().toIntOrNull() ?: 5060
        val user = findViewById<EditText>(R.id.etCfgUser).text.toString().trim()
        val pass = findViewById<EditText>(R.id.etCfgPass).text.toString().trim()
        // The lowest active SIM's number is the one everything that does not
        // know which SIM it is dealing with will use.
        val own = ownNumberFields.firstOrNull()?.second?.text?.toString()?.trim().orEmpty()
        val auto = findViewById<CheckBox>(R.id.cbCfgAutoconnect).isChecked
        val useStun = findViewById<CheckBox>(R.id.cbCfgUseStun).isChecked
        val translit = findViewById<CheckBox>(R.id.cbCfgTranslit).isChecked
        val tls = findViewById<CheckBox>(R.id.cbCfgTls).isChecked
        // Stored as asked for, but only ever acted on with TLS — so turning
        // TLS off and on again does not silently lose the audio setting.
        val srtp = findViewById<CheckBox>(R.id.cbCfgSrtp).isChecked
        val agentVolStep = findViewById<SeekBar>(R.id.sbCfgAgentVolume).progress - 3
        val codec = when (findViewById<RadioGroup>(R.id.rgCfgCodec).checkedRadioButtonId) {
            R.id.rbCodecG711 -> "g711"
            R.id.rbCodecBoth -> "both"
            else -> "g722"
        }

        if (server.isEmpty() || user.isEmpty()) {
            Toast.makeText(this, "Server and username are required", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("gateway", MODE_PRIVATE).edit()
            .putString("server", server)
            .putInt("port", port)
            .putString("user", user)
            .putString("pass", pass)
            .putString("own_number", own)
            .also { ed ->
                ownNumberFields.forEach { (slot, et) ->
                    ed.putString(ownNumberKey(slot), et.text.toString().trim())
                }
            }
            .putBoolean("autoconnect", auto)
            .putBoolean("use_stun", useStun)
            .putBoolean("translit_ascii", translit)
            .putBoolean("sip_tls", tls)
            .putBoolean("srtp_enabled", srtp)
            .putString("codec", codec)
            .putInt("agent_vol_step", agentVolStep)
            .apply()
        appendLog(
            "Config saved: $user@$server:$port (own=${own.ifEmpty { "auto" }}, " +
                "codec=$codec, stun=${if (useStun) "on" else "off"}, " +
                "tls=${if (tls) "on" else "off"}, " +
                "srtp=${if (srtp && tls) "on" else "off"}, " +
                "ascii=${if (translit) "on" else "off"}, " +
                    "agent volume ${if (agentVolStep > 0) "+$agentVolStep" else "$agentVolStep"})"
        )
        Toast.makeText(this, "Saved — reconnecting", Toast.LENGTH_SHORT).show()
        // Apply immediately rather than waiting for the next restart.  This
        // has to rebuild the client, not just re-register: the server, port,
        // credentials and STUN choice are all read at bring-up.
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_APPLY_CONFIG
        })
        switchTab("home")
    }

    /** Cut the agent's audio to the caller.  The call stays up; this only
     *  silences what the agent is sending, for when it says something wrong. */
    private fun toggleAgentMute() {
        agentMuted = !agentMuted
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MUTE_AGENT
            putExtra(GatewayService.EXTRA_MUTE_ON, agentMuted)
        })
        setCallButtonState(
            btnHomeMute,
            if (agentMuted) "Unmute" else "Mute",
            if (agentMuted) R.drawable.ic_fa_volume_high else R.drawable.ic_fa_volume_xmark
        )
        appendLog(if (agentMuted) "Agent muted to caller" else "Agent unmuted")
    }

    /**
     * Show or hide the active-call card and keep the header pill in step with
     * the gateway's state.
     */
    private fun updateHomeCall(state: String, info: String) {
        if (!::homeCallCard.isInitialized) return

        // "Online" means registered, not merely running: while connecting or
        // retrying the gateway cannot take a call, and the pill should say so
        // — it is also the tap target for a manual retry.
        val online = when (state) {
            "STOPPED", "ERROR", "STARTING" -> false
            "IDLE" -> sipRegistered
            else -> true            // any call state means registration held
        }
        gatewayOnline = online
        tvHomeStatusPill.text = if (online) "● Online" else "● Offline"
        tvHomeStatusPill.setTextColor(
            Color.parseColor(if (online) "#34D399" else "#F87171")
        )

        // The badge says what the signalling is carried over, which is a
        // property of the configuration rather than of the current state --
        // so it is shown whenever TLS is switched on, not only while
        // registered.  It reads from prefs each time because the setting can
        // change under the Activity while it is alive.
        val cfg = getSharedPreferences("gateway", MODE_PRIVATE)
        val tlsOn = cfg.getBoolean("sip_tls", false)
        tvHomeTlsBadge.visibility = if (tlsOn) View.VISIBLE else View.GONE

        // Shown only when SRTP can actually apply.  The setting is stored
        // independently of TLS so it survives toggling the transport, but a
        // badge claiming encrypted audio on a UDP gateway would be a lie --
        // the same AND the SIP client enforces.
        tvHomeSrtpBadge.visibility =
            if (tlsOn && cfg.getBoolean("srtp_enabled", false)) View.VISIBLE else View.GONE

        if (state == "BRIDGED") {
            homeCallCard.visibility = View.VISIBLE
            val number = com.callagent.gateway.gsm.GsmCallManager.currentNumber ?: info
            tvHomeCallFrom.text = number
            val dest = getSharedPreferences("gateway", MODE_PRIVATE)
                .getString("own_number", "") ?: ""
            tvHomeCallTo.text = if (dest.isNotEmpty()) "Connected to $dest" else "Connected"
            // Inbound is the normal direction for a gateway; a dialler-initiated
            // call is the other way round.
            tvHomeCallDirection.text =
                if (com.callagent.gateway.gsm.GsmCallManager.activeCallState ==
                    android.telecom.Call.STATE_ACTIVE && gsmCallActive) "GSM → SIP" else "GSM → SIP"
            startCallTimer()
        } else {
            homeCallCard.visibility = View.GONE
            if (!inCallOpen) {
                callStartTime = 0L
                callTimerHandler.removeCallbacks(callTimerRunnable)
            }
            // Mute is per-call; do not carry it into the next one.
            if (agentMuted) {
                agentMuted = false
                if (::btnHomeMute.isInitialized) {
                    setCallButtonState(btnHomeMute, "Mute", R.drawable.ic_fa_volume_xmark)
                }
            }
            // Snoop likewise — the monitor lives with the RTP session and dies
            // with the call, but the flag was only ever cleared by the old
            // full-screen in-call view, which nothing opens any more.  So the
            // button came up saying "Stop" on the next call and the first tap
            // turned off something that was already off.
            if (monitoring) {
                monitoring = false
                updateMonitorButtons()
            }
            renderHomeTraffic()
        }
    }

    /** Repaint the home view from current state. */
    private fun refreshHome() {
        renderHomeTraffic()
        refreshNetworkInfo()
    }

    /**
     * Both network legs the gateway depends on: the modem carries the GSM call,
     * WiFi carries SIP and RTP.  A problem on either shows up as a broken call,
     * so it is worth seeing them side by side.
     */
    /**
     * Signal strength in dBm, or null when the modem has no usable reading.
     *
     * SignalStrength.getCellSignalStrengths() is API 29 and minSdk here is 26,
     * so below Q the method does not exist and calling it throws
     * NoSuchMethodError.  That is an Error, not an Exception, so the try/catch
     * around the call sites never contained it — on Android 9 this took the
     * whole Activity down in onCreate, and the app could not be opened at all.
     *
     * The pre-Q reading is getGsmSignalStrength(), which reports ASU rather
     * than dBm: 0..31 maps linearly onto -113..-51 dBm, and 99 means unknown.
     */
    @Suppress("DEPRECATION")
    private fun signalDbm(ss: android.telephony.SignalStrength?): Int? {
        if (ss == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ss.cellSignalStrengths.firstOrNull()?.dbm
        }
        val asu = ss.gsmSignalStrength
        return if (asu in 0..31) -113 + 2 * asu else null
    }

    @SuppressLint("MissingPermission")
    private fun refreshNetworkInfo() {
        if (!::tvNetMobile.isInitialized) return

        tvNetMobile.text = try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val name = tm.networkOperatorName?.ifEmpty { "No service" } ?: "No service"
            val type = when (tm.dataNetworkType) {
                android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP,
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPA,
                android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                android.telephony.TelephonyManager.NETWORK_TYPE_EDGE,
                android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN -> "—"
                else -> "?"
            }
            val dbm = signalDbm(tm.signalStrength)
            if (dbm != null && dbm != Int.MAX_VALUE) "$type $name ${dbm}dBm"
            else "$type $name"
        } catch (e: Exception) {
            "mobile: n/a"
        }

        tvNetWifi.text = try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            when {
                !wm.isWifiEnabled -> "WiFi off"
                // networkId is NOT a connectivity test: without location
                // permission getConnectionInfo() comes back redacted with
                // networkId = -1 even on a healthy connection, which is what
                // made this read "WiFi off" while WiFi was up.  Connectivity
                // comes from NetworkCapabilities; link metrics are not
                // location-gated and are still readable here.
                !onWifi -> "WiFi not connected"
                else -> {
                    @Suppress("DEPRECATION")
                    val raw = info?.ssid?.trim('"').orEmpty()
                    val ssid =
                        if (raw.isEmpty() || raw.contains("unknown", true)) "WiFi" else raw
                    @Suppress("DEPRECATION")
                    val freq = info?.frequency ?: 0
                    @Suppress("DEPRECATION")
                    val speed = info?.linkSpeed ?: -1
                    @Suppress("DEPRECATION")
                    val rssi = info?.rssi ?: 0
                    val band = if (freq > 4000) "5G" else "2.4G"
                    if (speed > 0) "$ssid $band ${speed}Mbps ${rssi}dBm"
                    else "$ssid $band ${rssi}dBm"
                }
            }
        } catch (e: Exception) {
            "wifi: n/a"
        }
    }

    /**
     * Everything we can learn about one of the two links.
     *
     * What the framework can answer is shown immediately; the parts that need
     * a shell — MAC addresses, cell identity — and the pings are appended as
     * they arrive, so the dialog is never blank while a ping runs.
     *
     * SSID, BSSID and cell identity are gated behind location permission for
     * an ordinary app.  This one has root instead, so it reads them from the
     * system rather than holding a permission a gateway has no business with.
     */
    /**
     * Everything known about one message, on tapping its row.
     *
     * Read from the call-log entry rather than [SmsOutbox]: the outbox is
     * pruned as soon as a message is finally reported, so for exactly the
     * messages that succeeded it holds nothing.  The outbox is still consulted
     * for one that is mid-flight, and for rows written before the log carried
     * these fields.
     */
    private fun showSmsDetails(entry: CallLogEntry) {
        val outgoing = entry.direction != "IN"
        val own = getSharedPreferences("gateway", MODE_PRIVATE)
            .getString("own_number", "").orEmpty()

        // A message still in flight has fresher paperwork in the outbox.
        val live = entry.smsId.takeIf { it.isNotEmpty() }
            ?.let { runCatching { SmsOutbox.get(this, it) }.getOrNull() }

        val parts = maxOf(entry.parts, live?.parts ?: 0)
        val status = entry.status.ifEmpty {
            when {
                live == null -> ""
                live.deliveredOk > 0 -> "delivered"
                live.sentOk > 0 -> "sent"
                live.dispatched -> "pending"
                else -> "queued"
            }
        }
        val error = entry.error.ifEmpty { live?.lastError.orEmpty() }

        fun dash(v: String) = v.ifEmpty { "\u2014" }
        fun row(label: String, value: String) =
            label.padEnd(9) + ": " + dash(value) + "\n"

        val stamp = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(entry.timestamp))

        val header = StringBuilder()
            .append(row("Direction", if (outgoing) "Outgoing" else "Incoming"))
            .append(row("From", if (outgoing) own else entry.number))
            .append(row("To", if (outgoing) entry.number else own))
            .append(row("SMSC", entry.smsc))
            .append(row("Date", stamp))
            .append(row("Format", entry.encoding))
            .append(
                row(
                    "Length",
                    "${entry.text.length} chars" +
                        if (parts > 0) ", $parts part${if (parts == 1) "" else "s"}" else ""
                )
            )

        // Delivery status is the outbound half of the story; an inbound
        // message has already arrived by definition, so claiming a state for
        // it would be inventing one.
        if (outgoing) {
            header.append(row("Status", status))
            if (error.isNotEmpty()) header.append(row("Error", error))
        }

        val body = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
            // A blank line is enough to separate the fields from the message;
            // the fields are aligned and the body is not, so the boundary
            // reads without a rule.
            text = header.toString() + "\n" + entry.text.ifEmpty { "(no text)" }
        }

        AlertDialog.Builder(this)
            .setTitle("Message detail")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText("SMS detail", body.text)
                )
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showLinkDetails(mobile: Boolean) {
        val body = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(48, 24, 48, 24)
            text = if (mobile) mobileDetailsFast() else wifiDetailsFast()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (mobile) "Mobile network" else "WiFi")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Close", null)
            .show()

        fun append(line: String) = runOnUiThread {
            if (dialog.isShowing) body.append(line)
        }

        Thread({
            if (mobile) {
                // Cell identity is location data as far as Android is
                // concerned: dumpsys blanks it even for root, so the only way
                // to read it is getAllCellInfo() with ACCESS_FINE_LOCATION.
                // The Magisk module grants that on boot, so no prompt appears.
                append("\n" + describeCells())
            } else {
                val extra = RootShell.execForOutput(
                    "echo MAC=$(cat /sys/class/net/wlan0/address 2>/dev/null); " +
                    "echo GW=$(ip route get 8.8.8.8 2>/dev/null | grep -oE 'via [0-9.]+' | awk '{print $2}')",
                    timeoutMs = 8000
                )
                val f = extra.lines().mapNotNull {
                    val i = it.indexOf('=')
                    if (i > 0) it.substring(0, i) to it.substring(i + 1).trim() else null
                }.toMap()
                val gw = f["GW"].orEmpty()
                append(
                    "Phone MAC    : ${f["MAC"]?.ifEmpty { null } ?: "—"}\n" +
                    "Router IP    : ${gw.ifEmpty { "—" }}\n"
                )
                if (gw.isNotEmpty()) {
                    val mac = RootShell.execForOutput(
                        "ip neigh show $gw 2>/dev/null | grep -oE '([0-9a-f]{2}:){5}[0-9a-f]{2}' | head -1",
                        timeoutMs = 5000
                    ).trim()
                    append("Router MAC   : ${mac.ifEmpty { "—" }}\n")
                }

                append("\n— reachability —\n")
                if (gw.isNotEmpty()) append("Router  : ${pingAvg(gw)}\n")
                val server = getSharedPreferences("gateway", MODE_PRIVATE)
                    .getString("server", "") ?: ""
                if (server.isNotEmpty()) append("SIP srv : ${pingAvg(server)}  ($server)\n")
            }
        }, "link-details").start()
    }

    /** Mobile facts the framework answers instantly. */
    @SuppressLint("MissingPermission")
    private fun mobileDetailsFast(): String = buildString {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            appendLine("Operator     : ${tm.networkOperatorName.ifEmpty { "—" }}")
            appendLine("MCC/MNC      : ${tm.networkOperator.ifEmpty { "—" }}")
            appendLine("Country      : ${tm.networkCountryIso.uppercase().ifEmpty { "—" }}")
            appendLine("SIM operator : ${tm.simOperatorName.ifEmpty { "—" }}")
            appendLine("SIM state    : ${simStateName(tm.simState)}")
            appendLine("Roaming      : ${if (tm.isNetworkRoaming) "yes" else "no"}")
            appendLine("Data network : ${networkTypeName(tm.dataNetworkType)}")
            appendLine("Voice network: ${networkTypeName(tm.voiceNetworkType)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tm.signalStrength?.cellSignalStrengths?.forEachIndexed { i, c ->
                    appendLine(
                        "Signal[$i]    : ${c.dbm} dBm, level ${c.level}/4 " +
                            "(${c.javaClass.simpleName.removePrefix("CellSignalStrength")})"
                    )
                }
            } else {
                signalDbm(tm.signalStrength)?.let { appendLine("Signal       : $it dBm") }
            }
        } catch (e: Exception) {
            appendLine("telephony: ${e.message}")
        }
    }

    /** WiFi facts the framework answers instantly. */
    private fun wifiDetailsFast(): String = buildString {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE)
                as android.net.wifi.WifiManager
            appendLine("Enabled      : ${if (wm.isWifiEnabled) "yes" else "no"}")
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            // Real SSID/BSSID: these read "<unknown ssid>" / 02:00:00:00:00:00
            // without location, which the Magisk module now grants on boot.
            @Suppress("DEPRECATION")
            val ssidRaw = info?.ssid?.trim('"').orEmpty()
            appendLine(
                "SSID         : " +
                    if (ssidRaw.isEmpty() || ssidRaw.contains("unknown", true)) "—" else ssidRaw
            )
            @Suppress("DEPRECATION")
            val bssid = info?.bssid.orEmpty()
            appendLine(
                "BSSID (AP)   : " +
                    if (bssid.isEmpty() || bssid.startsWith("02:00:00")) "—" else bssid
            )
            appendLine("Security     : ${wifiSecurityName(info)}")
            @Suppress("DEPRECATION")
            val freq = info?.frequency ?: 0
            @Suppress("DEPRECATION")
            appendLine("Link speed   : ${info?.linkSpeed ?: -1} Mbps")
            appendLine("Frequency    : $freq MHz (${if (freq > 4000) "5 GHz" else "2.4 GHz"})")
            @Suppress("DEPRECATION")
            appendLine("RSSI         : ${info?.rssi ?: 0} dBm")

            // IP and DNS come from LinkProperties: no root, no permission, and
            // it reports what the network actually resolved with.
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            val ip = lp?.linkAddresses?.firstOrNull { it.address.hostAddress?.contains('.') == true }
            appendLine("IP address   : ${ip?.address?.hostAddress ?: "—"}")
            val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.joinToString(", ")
            appendLine("DNS          : ${dns?.ifEmpty { null } ?: "—"}")
        } catch (e: Exception) {
            appendLine("wifi: ${e.message}")
        }
    }

    /**
     * Serving cell and neighbours.  Can take a moment — the radio is polled —
     * so it is called off the main thread.
     */
    @SuppressLint("MissingPermission")
    private fun describeCells(): String {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "needs location permission (granted by the Magisk module on boot)"

        return try {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val cells = tm.allCellInfo
            if (cells.isNullOrEmpty()) return "none reported"
            cells.take(6).joinToString("\n") { describeCell(it) }
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /**
     * One cell as aligned `name : value` lines, matching the rest of the
     * dialog.  Int.MAX_VALUE is the API's "unknown", not a real reading, so it
     * is shown as a dash rather than a nine-digit number.
     */
    private fun describeCell(info: android.telephony.CellInfo): String = buildString {
        fun row(name: String, value: String) = appendLine(name.padEnd(13) + ": " + value)
        fun num(x: Int) = if (x == Int.MAX_VALUE) "—" else x.toString()
        fun sig(s: android.telephony.CellSignalStrength) = "${s.dbm} dBm (${s.level}/4)"

        val role = if (info.isRegistered) "serving cell" else "neighbour"
        // Subject-less `when` so the CellInfoNr branch can carry an SDK guard.
        // The class is API 29, and an `is` test against a class the platform
        // does not have is itself the hazard — it resolves the type before any
        // guard inside the branch could run.
        when {
            info is android.telephony.CellInfoLte -> {
                val id = info.cellIdentity
                row("Type", "LTE ($role)")
                row("Cell ID", num(id.ci))
                row("PCI", num(id.pci))
                row("TAC", num(id.tac))
                row("EARFCN", num(id.earfcn))
                row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                id.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }
                    ?.let { row("Carrier", it) }
                row("Signal", sig(info.cellSignalStrength))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                info is android.telephony.CellInfoNr -> {
                val id = info.cellIdentity as? android.telephony.CellIdentityNr
                row("Type", "5G NR ($role)")
                row("NCI", id?.nci?.toString() ?: "—")
                row("PCI", num(id?.pci ?: Int.MAX_VALUE))
                row("TAC", num(id?.tac ?: Int.MAX_VALUE))
                row("NRARFCN", num(id?.nrarfcn ?: Int.MAX_VALUE))
                row("MCC/MNC", "${id?.mccString ?: "—"}/${id?.mncString ?: "—"}")
                row("Signal", sig(info.cellSignalStrength))
            }
            info is android.telephony.CellInfoWcdma -> {
                val id = info.cellIdentity
                row("Type", "WCDMA ($role)")
                row("Cell ID", num(id.cid))
                row("LAC", num(id.lac))
                row("PSC", num(id.psc))
                row("UARFCN", num(id.uarfcn))
                row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                row("Signal", sig(info.cellSignalStrength))
            }
            info is android.telephony.CellInfoGsm -> {
                val id = info.cellIdentity
                row("Type", "GSM ($role)")
                row("Cell ID", num(id.cid))
                row("LAC", num(id.lac))
                row("ARFCN", num(id.arfcn))
                row("BSIC", num(id.bsic))
                row("MCC/MNC", "${id.mccString ?: "—"}/${id.mncString ?: "—"}")
                row("Signal", sig(info.cellSignalStrength))
            }
            else -> {
                row("Type", "${info.javaClass.simpleName.removePrefix("CellInfo")} ($role)")
                // The CellInfo base class only grew getCellSignalStrength() in
                // API 30; every branch above reads it off its own subclass,
                // which has had it since 17.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    row("Signal", sig(info.cellSignalStrength))
                }
            }
        }
    }

    /**
     * The link's security type — the cipher in use, not the passphrase.
     * The stored key is deliberately not shown: it is the network's actual
     * credential and a diagnostics panel is the wrong place for it.
     */
    private fun wifiSecurityName(info: android.net.wifi.WifiInfo?): String {
        if (info == null) return "—"
        // getCurrentSecurityType() is API 31.  The catch below does not stand in
        // for a version check: a missing method raises NoSuchMethodError, which
        // is an Error rather than an Exception, so it would pass straight
        // through and take the dialog down.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "—"
        return try {
            when (info.currentSecurityType) {
                android.net.wifi.WifiInfo.SECURITY_TYPE_OPEN -> "open (none)"
                android.net.wifi.WifiInfo.SECURITY_TYPE_WEP -> "WEP"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PSK -> "WPA/WPA2-PSK"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP -> "WPA-EAP"
                android.net.wifi.WifiInfo.SECURITY_TYPE_SAE -> "WPA3-SAE"
                android.net.wifi.WifiInfo.SECURITY_TYPE_OWE -> "OWE (enhanced open)"
                android.net.wifi.WifiInfo.SECURITY_TYPE_WAPI_PSK -> "WAPI-PSK"
                android.net.wifi.WifiInfo.SECURITY_TYPE_WAPI_CERT -> "WAPI-CERT"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE -> "WPA3-Enterprise"
                android.net.wifi.WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT ->
                    "WPA3-Enterprise 192-bit"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PASSPOINT_R1_R2 -> "Passpoint R1/R2"
                android.net.wifi.WifiInfo.SECURITY_TYPE_PASSPOINT_R3 -> "Passpoint R3"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "—"
        }
    }

    /** Average round-trip to a host, or why it failed. */
    private fun pingAvg(host: String): String {
        val out = RootShell.execForOutput("ping -c 3 -W 2 $host 2>&1 | tail -2", timeoutMs = 12000)
        val avg = Regex("= [0-9.]+/([0-9.]+)/").find(out)?.groupValues?.getOrNull(1)
        val loss = Regex("([0-9]+)% packet loss").find(out)?.groupValues?.getOrNull(1)
        return when {
            avg != null -> "$avg ms avg" + (loss?.let { ", $it% loss" } ?: "")
            loss == "100" -> "no reply (100% loss)"
            else -> out.lines().firstOrNull { it.isNotBlank() } ?: "unreachable"
        }
    }

    private fun simStateName(state: Int): String = when (state) {
        android.telephony.TelephonyManager.SIM_STATE_READY -> "ready"
        android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "absent"
        android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN required"
        android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK required"
        android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network locked"
        android.telephony.TelephonyManager.SIM_STATE_NOT_READY -> "not ready"
        else -> "unknown($state)"
    }

    private fun setCallFilter(filter: String) {
        callFilter = filter
        val on = ContextCompat.getColor(this, R.color.accent)
        val onText = ContextCompat.getColor(this, R.color.accent_on)
        val off = ContextCompat.getColor(this, R.color.btn_secondary)
        val offText = ContextCompat.getColor(this, R.color.text_primary)
        for ((btn, name) in listOf(
            btnFilterAll to "all", btnFilterIncoming to "in", btnFilterOutgoing to "out"
        )) {
            val active = name == filter
            btn.backgroundTintList = ColorStateList.valueOf(if (active) on else off)
            btn.setTextColor(if (active) onText else offText)
        }
        renderHomeTraffic()
    }

    /** Recent calls on the home view, newest first. */
    private fun renderHomeTraffic() {
        val all = try { CallLogStore.getEntries(this) } catch (_: Exception) { emptyList() }
        val entries = when (callFilter) {
            "in" -> all.filter { it.direction == "IN" }
            "out" -> all.filter { it.direction != "IN" }
            else -> all
        }
        homeTrafficList.removeAllViews()
        tvHomeTrafficEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

        for (e in entries.take(30)) {
            val row = layoutInflater.inflate(R.layout.item_call_row, homeTrafficList, false)
            val incoming = e.direction == "IN"
            row.findViewById<ImageView>(R.id.ivRowIcon).setImageResource(
                if (incoming) R.drawable.ic_call_incoming else R.drawable.ic_call_outgoing
            )
            row.findViewById<TextView>(R.id.tvRowNumber).text = e.number
            val sms = e.type == CallLogStore.TYPE_SMS
            // The direction arrow and the card are the same as a call's — a
            // message is the same kind of traffic through the same gateway.
            // What changes is the second line, which carries the message
            // itself, and the slot where a call shows its duration.
            row.findViewById<TextView>(R.id.tvRowSub).text = when {
                sms -> e.text.replace('\n', ' ').trim().ifEmpty { "(no text)" }
                e.durationSec > 0 -> if (incoming) "GSM → SIP" else "SIP → GSM"
                else -> "Not connected"
            }
            val dur = row.findViewById<TextView>(R.id.tvRowDuration)
            when {
                sms -> {
                    dur.text = "SMS"
                    dur.setTextColor(Color.parseColor("#60A5FA"))
                }
                e.durationSec > 0 -> {
                    dur.text = String.format("%02d:%02d", e.durationSec / 60, e.durationSec % 60)
                    dur.setTextColor(Color.parseColor("#34D399"))
                }
                else -> {
                    dur.text = "—"
                    dur.setTextColor(Color.parseColor("#F87171"))
                }
            }
            val d = java.util.Date(e.timestamp)
            val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(d) == today
            row.findViewById<TextView>(R.id.tvRowTime).text =
                if (sameDay) hhmm.format(d) else "Earlier"
            if (sms) {
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { showSmsDetails(e) }
            }
            homeTrafficList.addView(row)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inCallOpen) {
            return // must use END CALL
        } else if (currentTab == "logs") {
            switchTab("config")
        } else if (currentTab != "home") {
            switchTab("home")
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
        // NOT_EXPORTED: the service sends these with setPackage(), so nothing
        // outside the app has any business delivering them — exported, any
        // installed app could feed the UI fabricated status and log lines.
        registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Replay any log messages buffered while activity was paused
        // Re-render from the service's buffer rather than consuming it: the
        // lines are already stamped with when each event happened, and
        // replacing the view means a recreated activity shows the full recent
        // history instead of whatever it happened to witness.
        val buffered = GatewayService.logSnapshot()
        if (buffered.isNotEmpty()) {
            tvLog.text = buffered.joinToString("\n", postfix = "\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        if (onlineSince > 0) {
            uptimeHandler.removeCallbacks(uptimeRunnable)
            uptimeRunnable.run()
        }

        // The old full-screen in-call view is superseded by the live call
        // card on the home view; it is no longer opened automatically.

        if (inCallOpen) {
            if (com.callagent.gateway.gsm.GsmCallManager.activeCall == null &&
                System.currentTimeMillis() - inCallOpenTime > 2000) {
                closeInCallScreen()
            } else {
                callTimerHandler.removeCallbacks(callTimerRunnable)
                if (callStartTime > 0) callTimerRunnable.run()
                callTimerHandler.removeCallbacks(gsmPollRunnable)
                callTimerHandler.postDelayed(gsmPollRunnable, 500)
            }
        }

        // Refresh the traffic list if it is the visible one.  This used to be
        // guarded on the old Calls tab, so after that tab went the home list
        // stopped being refreshed here at all and showed a stale view until
        // something else rebuilt it.
        if (currentTab == "home") {
            refreshHome()
        }

        // Re-apply the chip highlight: the visual state is set in code, so it
        // has to be restored whenever the view comes back.
        if (::btnFilterAll.isInitialized) setCallFilter(callFilter)

        netHandler.removeCallbacks(netRunnable)
        netRunnable.run()

        // Ask the service where it is.  Status is only pushed on change, so
        // opening the app onto an already-running gateway would otherwise show
        // "Offline" until something happened.
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_STATUS
        })
    }

    override fun onPause() {
        super.onPause()
        uptimeHandler.removeCallbacks(uptimeRunnable)
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        netHandler.removeCallbacks(netRunnable)
        unregisterReceiver(statusReceiver)
    }

    // ── Config Dialog ────────────────────────────────────

    // ── Info Dialog ─────────────────────────────────────

    @SuppressLint("MissingPermission")
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

    /** Pre-load both IN and OUT lists off the main thread so tab switching is instant */
    /** Show the already-cached list for the current filter — runs on UI thread, no I/O */
    // ── Dialler ──────────────────────────────────────────

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

    /** Listen in on the live call through the speaker.  The microphone is not
     *  touched — it stays muted, so the room is never audible to either side. */
    private fun toggleMonitor() {
        monitoring = !monitoring
        startService(Intent(this, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MONITOR
            putExtra(GatewayService.EXTRA_MONITOR_ON, monitoring)
        })
        updateMonitorButtons()
        appendLog(if (monitoring) "Snoop on — both sides on the speaker" else "Snoop off")
    }

    private fun updateMonitorButtons() {
        if (::btnInCallMonitor.isInitialized) {
            btnInCallMonitor.text = if (monitoring) "STOP LISTENING" else "LISTEN IN"
        }
        if (::btnHomeSnoop.isInitialized) {
            setCallButtonState(
                btnHomeSnoop,
                if (monitoring) "Stop" else "Snoop",
                if (monitoring) R.drawable.ic_fa_circle_stop else R.drawable.ic_fa_headphones
            )
        }
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
        // The monitor lives with the RTP session, which ends with the call, so
        // only the button label needs resetting for the next one.
        monitoring = false
        updateMonitorButtons()
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.removeCallbacks(gsmPollRunnable)
        callStartTime = 0L
        lastGsmPollState = -1
        inCallView.visibility = View.GONE
        tabbedRoot.visibility = View.VISIBLE
        gsmCallActive = false
        // Refresh the traffic list on returning from a call, so the call that
        // just ended is there.
        if (currentTab == "home") {
            refreshHome()
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

        appendLog("Starting gateway: $user@$server:$port")
    }

    private fun stopGateway() {
        GatewayService.stop(this)
        running = false
    }

    private fun updateStatus(state: String, info: String) {
        // SNOOP only does anything while audio is flowing, so it follows the
        // bridge state rather than being permanently tappable.
        callLive = state == "BRIDGED"
        updateMonitorButtons()
        updateHomeCall(state, info)

    }

    private fun appendLog(msg: String) {
        appendLogRaw("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $msg")
    }

    /** Append a line that already carries its own timestamp. */
    private fun appendLogRaw(line: String) {
        runOnUiThread {
            tvLog.append("$line\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun clearLog() {
        tvLog.text = ""
        GatewayService.clearLogBuffer()
        Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
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
            Manifest.permission.READ_CALL_LOG
            // Deliberately not location.  A gateway has no business asking for
            // it, and the two things that use it are cosmetic: the cell-id
            // readout in the info dialog, and the WiFi SSID (getSSID() has
            // returned "<unknown ssid>" without location since Android 8.1).
            // Both degrade to a placeholder instead.
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
