package com.callagent.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import com.callagent.gateway.BuildConfig
import com.callagent.gateway.GatewayApp
import com.callagent.gateway.MainActivity
import com.callagent.gateway.R
import com.callagent.gateway.RootShell
import com.callagent.gateway.bridge.CallOrchestrator
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.net.StunClient
import com.callagent.gateway.sip.SipClient
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service: keeps the SIP client registered 24/7.
 *
 * Holds a WiFi lock and wake lock to prevent the device from
 * sleeping and dropping the SIP registration.
 */
class GatewayService : Service() {

    private var sipClient: SipClient? = null
    private var orchestrator: CallOrchestrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Saved config for reconnect */
    private var cfgServer = ""
    private var cfgPort = 5060
    private var cfgUser = ""
    private var cfgPass = ""
    private var currentLocalIp = ""

    // ── Call tracking ───────────────────────────────────
    private var onlineSince = 0L
    private var incomingCalls = 0
    private var incomingDurationSec = 0L
    private var outgoingCalls = 0
    private var outgoingDurationSec = 0L
    private var currentCallStart = 0L
    private var currentCallIncoming = true
    private var currentCallNumber = ""

    /** Prevents concurrent startGateway / reconnect threads */
    private val initializing = AtomicBoolean(false)

    /**
     * Bumped on every bring-up.  A SIP init is slow — Magisk `su` can take
     * seconds, STUN can take seconds more — and the stale-init recovery
     * releases [initializing] after 90s whether or not that thread has
     * finished.  Without a generation the late thread published its own
     * SipClient over the newer one and the older client was never stopped:
     * it kept its socket on :5060 (SO_REUSEADDR lets several bind), kept its
     * monitor loop, and every REGISTER it sent timed out because the kernel
     * delivered the response to one socket only.  Each timeout escalated the
     * process-wide REGISTER backoff, so a healthy client ended up held off
     * for five minutes at a time.  The device had four live SipClients.
     */
    private val initGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** When [initializing] was last set, so a stuck flag can be detected.
     *
     *  If the init thread dies or hangs, this flag stays true forever, and then
     *  nothing can ever bring the gateway back: reconnect()'s compareAndSet
     *  always fails and startGateway()'s guard always skips.  The gateway sits
     *  silent — no REGISTER at all — until someone restarts the app by hand. */
    @Volatile private var initializingSince = 0L

    /** Whether the speaker monitor is on, for the notification's action label. */
    @Volatile private var monitorOn = false

    /** Whether the agent is muted towards the caller. */
    @Volatile private var agentMuted = false

    // ── Network change detection ────────────────────────
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Last transport we logged, so the constant capability churn does not
     *  fill the log with identical lines. */
    private var lastTransport = ""

    /**
     * Describe the network actually carrying our traffic — "WiFi", "LTE",
     * "5G" — and log it when it changes.  A WiFi drop that hands over to
     * cellular, or an LTE re-attach, is exactly the kind of event that
     * explains a re-REGISTER after the fact, and none of it was visible:
     * transport changes only ever reached logcat.
     */
    private fun logTransportIfChanged() {
        val desc = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> mobileGeneration()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "other"
            }
        } catch (e: Exception) {
            "unknown (${e.message})"
        }
        if (desc == lastTransport) return
        val previous = lastTransport
        lastTransport = desc
        // First observation is the baseline, not a transition.
        if (previous.isEmpty()) broadcastLog("NET: on $desc")
        else broadcastLog("NET: $previous → $desc")
    }

    /** LTE / 5G / 3G for the data connection, mirroring the home view's label. */
    private fun mobileGeneration(): String = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> "Mobile"
        }
    } catch (_: SecurityException) {
        "Mobile"
    } catch (_: Exception) {
        "Mobile"
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                logTransportIfChanged()
                checkNetworkChanged()
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
                // During an active GSM call, cellular data goes SUSPENDED which
                // fires onLost.  This is normal Android behavior — do NOT tear
                // down the bridge.  WiFi still carries SIP/RTP traffic.
                val busy = orchestrator?.bridgeState?.let {
                    it != CallOrchestrator.BridgeState.IDLE
                } ?: false
                if (busy) {
                    Log.i(TAG, "Skipping reconnect — call in progress (${orchestrator?.bridgeState})")
                    broadcastLog("NET: lost (ignored — call active)")
                    return
                }
                logTransportIfChanged()
                // Don't reconnect on the strength of onLost alone.  This
                // fires whenever any network goes away — cellular settling
                // after boot, mobile data dropping while WiFi carries the
                // registration perfectly well — and each one rebuilt the
                // socket and sent a fresh REGISTER for nothing.
                // checkNetworkChanged() reconnects only if the local IP
                // actually moved or the registration is genuinely gone.
                checkNetworkChanged()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                logTransportIfChanged()
                checkNetworkChanged()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun checkNetworkChanged() {
        // Skip if no prior IP (first start handles its own init)
        if (currentLocalIp.isEmpty()) return
        if (cfgServer.isEmpty()) return
        // During an active call, skip network checks — cellular SUSPENDED
        // is normal and WiFi handles SIP/RTP traffic.
        val busy = orchestrator?.bridgeState?.let {
            it != CallOrchestrator.BridgeState.IDLE
        } ?: false
        if (busy) return
        val newIp = getLocalIp()
        if (newIp == "0.0.0.0") return
        val ipChanged = newIp != currentLocalIp
        val notRegistered = sipClient?.registered != true
        if (ipChanged || notRegistered) {
            if (ipChanged) broadcastLog("NET: IP changed $currentLocalIp → $newIp, reconnecting")
            else broadcastLog("NET: registration lost, reconnecting")
            reconnect()
        }
    }

    private fun reconnect() {
        if (stopped || cfgServer.isEmpty()) return
        clearStaleInitializing()
        if (!initializing.compareAndSet(false, true)) {
            Log.i(TAG, "Reconnect skipped — already initializing")
            return
        }
        initializingSince = System.currentTimeMillis()
        onlineSince = 0L
        broadcastLog("SIP: reconnecting")
        broadcastStatus("STARTING", "Reconnecting...")
        updateNotification(NotifState.WARN, "Connecting")

        // Tear down existing client
        orchestrator?.stop()
        sipClient?.stop()
        orchestrator = null
        sipClient = null

        val gen = initGeneration.incrementAndGet()
        thread(name = "gateway-reconnect") {
            try {
                initSipClient(gen)
            } finally {
                initializing.set(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
        RootShell.init()
        Log.i(TAG, "GatewayService created")
    }

    /**
     * Report whether the gateway still holds the default-dialer role.
     *
     * Losing it is silent and total: Telecom stops binding GsmCallService, so
     * no incoming GSM call is ever seen, while SIP stays registered and the
     * app looks perfectly healthy.  Checked at every bring-up so the log
     * carries the answer without anyone having to go looking for it.
     */
    private fun checkDefaultDialer(): Boolean {
        return try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            val holder = tm.defaultDialerPackage
            val held = holder == packageName
            if (held) {
                broadcastLog("Default dialer: yes")
            } else {
                broadcastLog(
                    "WARNING: not the default dialer (${holder ?: "none"}) — " +
                        "incoming GSM calls will not reach the gateway"
                )
            }
            held
        } catch (e: Exception) {
            broadcastLog("Default dialer check failed: ${e.message}")
            false
        }
    }

    /** Release the init flag if it has been held implausibly long. */
    private fun clearStaleInitializing() {
        if (initializing.get() &&
            System.currentTimeMillis() - initializingSince > INIT_STALE_MS
        ) {
            val heldFor = (System.currentTimeMillis() - initializingSince) / 1000
            Log.w(TAG, "init flag held ${heldFor}s — treating as stale and clearing")
            broadcastLog("Recovering from stuck initialisation (${heldFor}s)")
            initializing.set(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        clearStaleInitializing()
        when (intent?.action) {
            ACTION_START -> startGateway(intent)
            ACTION_STOP -> stopGateway()
            ACTION_RELOAD_STATS -> reloadStats()
            ACTION_STATUS -> broadcastCurrentStatus()
            ACTION_RECONNECT -> {
                // Tapping the offline pill should act immediately.  If there is
                // a client, ask it to register now; if there is not, the
                // gateway is down and needs bringing up.
                val sip = sipClient
                broadcastStatus("STARTING", "Retrying…")
                if (sip != null && !stopped) {
                    sip.retryNow()
                } else {
                    // No client — a previous reconnect nulled it and never
                    // finished.  Force a fresh one rather than letting the
                    // init guard swallow the request.
                    initializing.set(false)
                    stopped = false
                    reconnect()
                }
            }
            ACTION_APPLY_CONFIG -> applyConfigChange()
            ACTION_DIAL -> dialFromDialler(intent)
            ACTION_MUTE_AGENT -> {
                agentMuted = if (intent.hasExtra(EXTRA_MUTE_ON)) {
                    intent.getBooleanExtra(EXTRA_MUTE_ON, false)
                } else {
                    !agentMuted
                }
                orchestrator?.setAgentMuted(agentMuted)
                broadcastStatus(
                    orchestrator?.bridgeState?.name ?: "IDLE",
                    if (agentMuted) "Agent muted" else "Agent unmuted"
                )
            }
            ACTION_MONITOR -> {
                // No extra means "toggle", which is what the notification
                // action sends; the in-call screen passes an explicit value.
                monitorOn = if (intent.hasExtra(EXTRA_MONITOR_ON)) {
                    intent.getBooleanExtra(EXTRA_MONITOR_ON, false)
                } else {
                    !monitorOn
                }
                orchestrator?.setMonitorEnabled(monitorOn)
                updateNotification(NotifState.OK)
            }
            else -> startGateway(intent)
        }
        return START_STICKY
    }

    /**
     * Re-read the saved configuration and rebuild the SIP client with it.
     *
     * ACTION_RECONNECT deliberately only asks the *existing* client to
     * register again, which is right for the status pill but wrong for a
     * settings save: server, port, credentials and the STUN choice are all
     * read once at bring-up, so editing them and pressing SAVE changed
     * nothing until the next restart.
     */
    private fun applyConfigChange() {
        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        cfgServer = prefs.getString("server", "") ?: ""
        cfgPort = prefs.getInt("port", 5060)
        cfgUser = prefs.getString("user", "") ?: ""
        cfgPass = prefs.getString("pass", "") ?: ""
        if (cfgServer.isEmpty() || cfgUser.isEmpty()) {
            broadcastLog("ERROR: Missing server or username")
            broadcastStatus("ERROR", "Missing SIP configuration")
            return
        }
        broadcastLog("Config changed — rebuilding SIP client")
        // A save is an explicit instruction, so it outranks a bring-up that
        // is already in flight; the generation counter makes discarding that
        // one safe.
        stopped = false
        initializing.set(false)
        reconnect()
    }

    private fun dialFromDialler(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: return
        orchestrator?.initiateDiallerCall(number)
            ?: broadcastLog("ERROR: Gateway not running — cannot bridge to SIP")
    }

    /**
     * Re-broadcast the current state.
     *
     * The UI is only ever told about state *changes*, so an Activity that
     * starts while the service is already running never hears anything and
     * shows its default "offline" until the next call.  This gives it a way
     * to ask.
     */
    private fun broadcastCurrentStatus() {
        val state = orchestrator?.bridgeState ?: CallOrchestrator.BridgeState.IDLE
        val registered = sipClient?.registered == true
        val info = when {
            stopped -> "Stopped"
            state == CallOrchestrator.BridgeState.IDLE && registered -> "SIP registered"
            state == CallOrchestrator.BridgeState.IDLE -> "Connecting"
            else -> state.name
        }
        broadcastStatus(if (stopped) "STOPPED" else state.name, info)
    }

    private fun reloadStats() {
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        broadcastStatus(orchestrator?.bridgeState?.name ?: "IDLE", "Stats reloaded")
    }

    private fun startGateway(intent: Intent?) {
        // Guard: if the gateway is already running (SIP client exists and
        // we're not in stopped state), don't tear it down and restart.
        // This prevents redundant ACTION_START intents (e.g. from the
        // Activity opening, START_STICKY restart, or BootReceiver) from
        // killing an active SIP registration or call bridge.
        //
        // `initializing` covers the window before sipClient is assigned:
        // initSipClient() runs on the gateway-init thread, so two intents
        // arriving back to back (START_STICKY redelivery with a null intent
        // + the Activity's autoStartGateway) would both see a null sipClient
        // and each bring up their own client — two sockets on :5060 and two
        // REGISTERs.
        if (!stopped && (sipClient != null || initializing.get())) {
            Log.i(TAG, "startGateway: already running, skipping restart")
            // Broadcast current state so the Activity picks up the live status
            val state = orchestrator?.bridgeState ?: CallOrchestrator.BridgeState.IDLE
            val registered = sipClient?.registered == true
            val info = when (state) {
                CallOrchestrator.BridgeState.IDLE ->
                    if (registered) "SIP registered" else "Connected"
                else -> state.name
            }
            broadcastStatus(state.name, info)
            return
        }

        // Clean up any existing client before starting a new one
        orchestrator?.stop()
        sipClient?.stop()
        orchestrator = null
        sipClient = null
        stopped = false

        // Load call stats from persistent store so counters survive restarts
        onlineSince = 0L
        val totals = CallLogStore.getTotals(this)
        incomingCalls = totals.inCalls
        incomingDurationSec = totals.inDurationSec
        outgoingCalls = totals.outCalls
        outgoingDurationSec = totals.outDurationSec
        currentCallStart = 0L

        val prefs = getSharedPreferences("gateway", MODE_PRIVATE)
        val server = intent?.getStringExtra(EXTRA_SERVER) ?: prefs.getString("server", "callagent.pro") ?: ""
        val port = intent?.getIntExtra(EXTRA_PORT, 5060) ?: prefs.getInt("port", 5060)
        val username = intent?.getStringExtra(EXTRA_USER) ?: prefs.getString("user", "") ?: ""
        val password = intent?.getStringExtra(EXTRA_PASS) ?: prefs.getString("pass", "") ?: ""

        if (server.isEmpty() || username.isEmpty()) {
            Log.e(TAG, "Missing SIP configuration")
            broadcastLog("ERROR: Missing server or username")
            broadcastStatus("ERROR", "Missing SIP configuration")
            stopSelf()
            return
        }

        // Save for restart
        prefs.edit()
            .putString("server", server)
            .putInt("port", port)
            .putString("user", username)
            .putString("pass", password)
            .apply()

        // Codec preference is a property of the SDP we build, so it has to be
        // in place before the first INVITE goes out.
        com.callagent.gateway.sip.SipBuilder.codecMode =
            prefs.getString("codec", "g722") ?: "g722"

        cfgServer = server
        cfgPort = port
        cfgUser = username
        cfgPass = password

        notifStatusText = "Connecting"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(NotifState.WARN, "Connecting"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(NotifState.WARN, "Connecting")
            )
        }
        acquireLocks()
        initializing.set(true)
        initializingSince = System.currentTimeMillis()

        // Run network I/O off the main thread (Android blocks sockets on main thread)
        val gen = initGeneration.incrementAndGet()
        thread(name = "gateway-init") {
            try {
                // Force-allow RECORD_AUDIO BEFORE SIP registration.
                // Magisk su takes 4+ seconds on first invocation (root server
                // startup).  Must complete before any calls can arrive.
                forceAllowRecordAudio()
                initSipClient(gen)
            } finally {
                initializing.set(false)
            }
        }
    }

    /** Shared SIP init — called from both startGateway and reconnect threads. */
    private fun initSipClient(gen: Int) {
        /** True while this thread is still the newest bring-up. */
        fun current() = gen == initGeneration.get()

        if (!current()) {
            Log.w(TAG, "initSipClient: superseded before start (gen $gen)")
            return
        }

        // A previous client must be gone before another socket is bound to
        // :5060.  reconnect() already does this, but startGateway() and the
        // stale-init recovery reach here without it.
        sipClient?.let {
            Log.w(TAG, "initSipClient: stopping previous SIP client")
            broadcastLog("SIP: stopping previous client before rebind")
            orchestrator?.stop()
            it.stop()
            orchestrator = null
            sipClient = null
        }

        checkDefaultDialer()

        val localIp = getLocalIp()
        currentLocalIp = localIp
        broadcastLog("Local IP: $localIp")

        // STUN: discover public IP for NAT traversal.  Optional, because a
        // SIP server on the same network needs no public address at all —
        // advertising one there would point the server at the far side of a
        // NAT it never has to cross.
        val useStun = getSharedPreferences("gateway", MODE_PRIVATE)
            .getBoolean("use_stun", true)
        val stunResult = if (!useStun) null else try {
            StunClient.discover()
        } catch (e: Exception) {
            Log.e(TAG, "STUN exception: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        val publicIp = stunResult?.publicIp ?: localIp
        when {
            !useStun ->
                broadcastLog("STUN off — direct SIP, advertising $localIp")
            stunResult != null ->
                broadcastLog("STUN public IP: ${stunResult.publicIp}:${stunResult.publicPort}")
            else ->
                broadcastLog("STUN failed, using local IP for SDP")
        }

        if (stopped) return
        if (!current()) {
            Log.w(TAG, "initSipClient: superseded during STUN (gen $gen)")
            return
        }

        val sip = SipClient(
            username = cfgUser,
            password = cfgPass,
            serverDomain = cfgServer,
            serverPort = cfgPort,
            localIp = localIp,
            localPort = 5060,
            publicIp = publicIp
        )
        sipClient = sip

        val orch = CallOrchestrator(this, sip)
        orch.listener = object : CallOrchestrator.OrchestratorListener {
            override fun onStateChanged(state: CallOrchestrator.BridgeState, info: String) {
                Log.i(TAG, "Bridge: $state - $info")
                // The call lifecycle only ever reached logcat and the status
                // pill; the log viewer showed SIP and audio lines with no
                // record of the call they belonged to.
                broadcastLog("CALL: ${state.name}${if (info.isBlank()) "" else " — $info"}")
                val registered = sip.registered

                // Track online time
                if (registered && onlineSince == 0L) {
                    onlineSince = System.currentTimeMillis()
                } else if (!registered && state == CallOrchestrator.BridgeState.IDLE) {
                    onlineSince = 0L
                }

                // Track call direction and number
                when (state) {
                    CallOrchestrator.BridgeState.GSM_RINGING -> {
                        currentCallIncoming = true
                        currentCallNumber = info.removePrefix("GSM call from ")
                    }
                    CallOrchestrator.BridgeState.GSM_DIALING -> {
                        currentCallIncoming = false
                        currentCallNumber = info.removePrefix("Dialing ")
                    }
                    else -> {}
                }

                // Track call start / end
                if (state == CallOrchestrator.BridgeState.BRIDGED && currentCallStart == 0L) {
                    currentCallStart = System.currentTimeMillis()
                    if (currentCallIncoming) incomingCalls++ else outgoingCalls++
                }
                if (state == CallOrchestrator.BridgeState.IDLE && currentCallStart != 0L) {
                    val dur = (System.currentTimeMillis() - currentCallStart) / 1000
                    if (currentCallIncoming) incomingDurationSec += dur
                    else outgoingDurationSec += dur
                    CallLogStore.addEntry(this@GatewayService, CallLogEntry(
                        direction = if (currentCallIncoming) "IN" else "OUT",
                        number = currentCallNumber,
                        timestamp = currentCallStart,
                        durationSec = dur
                    ))
                    currentCallStart = 0L
                    currentCallNumber = ""
                }

                // Map bridge state to notification status text
                val (notifState, statusText) = when (state) {
                    CallOrchestrator.BridgeState.IDLE ->
                        if (registered) NotifState.OK to "Connected"
                        else NotifState.WARN to "Connecting"
                    CallOrchestrator.BridgeState.GSM_DIALING ->
                        NotifState.OK to "Dialing"
                    CallOrchestrator.BridgeState.BRIDGED ->
                        NotifState.OK to "In-Call"
                    CallOrchestrator.BridgeState.GSM_RINGING,
                    CallOrchestrator.BridgeState.GSM_ANSWERED,
                    CallOrchestrator.BridgeState.SIP_CALLING,
                    CallOrchestrator.BridgeState.SIP_RINGING ->
                        NotifState.OK to "In-Call"
                    CallOrchestrator.BridgeState.TEARING_DOWN ->
                        NotifState.OK to "In-Call"
                }
                updateNotification(notifState, statusText)
                broadcastStatus(state.name, info)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Orchestrator error: $error")
                broadcastLog("ERROR: $error")
                broadcastStatus("ERROR", error)
            }

            override fun onRtpStats(stats: String) {
                broadcastLog("RTP: $stats")
            }
        }
        orchestrator = orch
        orch.start()

        sip.logListener = { msg -> broadcastLog("SIP: $msg") }
        GsmCallManager.logCallback = { msg -> broadcastLog("AUDIO: $msg") }
        sip.onConnectionLost = { reconnect() }

        // Last check before anything binds a socket: if a newer bring-up has
        // started meanwhile, this client must not exist at all.
        if (!current() || sipClient !== sip) {
            Log.w(TAG, "initSipClient: superseded before start (gen $gen) — discarding")
            orch.stop()
            return
        }

        try {
            sip.start()
            broadcastLog("[v${BuildConfig.VERSION_NAME}] SIP client started, registering with $cfgServer:$cfgPort")
            broadcastStatus("STARTING", "Registering with $cfgServer")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SIP client: ${e.message}", e)
            broadcastLog("ERROR: SIP start failed — ${e.message}")
            broadcastStatus("ERROR", "SIP start failed: ${e.message}")
        }
    }

    @Volatile private var stopped = false

    private fun stopGateway() {
        if (stopped) return
        stopped = true
        onlineSince = 0L
        Log.i(TAG, "Stopping gateway")
        orchestrator?.stop()
        sipClient?.stop()
        orchestrator = null
        sipClient = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastStatus("STOPPED", "Gateway stopped")
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        stopGateway()
        super.onDestroy()
    }

    // ── Notification ────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private enum class NotifState { OK, WARN, ERROR }

    /** Current notification status text, kept in sync with bridge/SIP state. */
    private var notifStatusText = "Connecting"

    private fun buildNotification(state: NotifState = NotifState.ERROR, statusText: String = notifStatusText): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = when (state) {
            NotifState.OK -> R.drawable.ic_notif_check
            NotifState.WARN -> R.drawable.ic_notif_warning
            NotifState.ERROR -> R.drawable.ic_notif_cross
        }
        // Listen-in toggle.  It lives on the notification rather than on the
        // in-call screen because during a real gateway call there is no visible
        // Activity at all — the service runs headless, and Android 15+ refuses
        // to let it launch one from the background (BAL_BLOCK).  The
        // notification is the only UI reachable at that moment.
        val monitorIntent = Intent(this, GatewayService::class.java).apply {
            action = ACTION_MONITOR
        }
        val monitorPi = PendingIntent.getService(
            this, 1, monitorIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val monitorAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_phone_call),
            if (monitorOn) "Stop listening" else "Listen in",
            monitorPi
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(statusText)
            .setSmallIcon(icon)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(monitorAction)
            .build()
            .apply { flags = flags or Notification.FLAG_NO_CLEAR }
    }

    private fun updateNotification(state: NotifState = NotifState.ERROR, statusText: String? = null) {
        if (statusText != null) notifStatusText = statusText
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    // ── Wake / WiFi locks ───────────────────────────────

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gateway:sip").apply {
            acquire()
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gateway:wifi").apply {
            acquire()
        }
        Log.i(TAG, "Wake + WiFi locks acquired")
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    // ── Broadcast to MainActivity ────────────────────────

    private fun broadcastStatus(state: String, info: String) {
        val intent = Intent(STATUS_ACTION).apply {
            setPackage(packageName)
            putExtra("state", state)
            putExtra("info", info)
            // Whether we are actually registered, as a fact rather than as a
            // string the UI has to recognise.  The pill used to infer this by
            // comparing info to the literal "SIP registered", so every state
            // change carrying any other text — "GSM call ended", for one —
            // read as offline while the registration was perfectly alive.
            putExtra("registered", sipClient?.registered == true)
            putExtra("online_since", onlineSince)
            // When the bridge came up, so the UI can show elapsed time rather
            // than time-since-it-noticed.  It only learns of a call when a
            // state change is broadcast, which is not when the call started.
            putExtra("call_start", currentCallStart)
            putExtra("in_calls", incomingCalls)
            putExtra("in_duration", incomingDurationSec)
            putExtra("out_calls", outgoingCalls)
            putExtra("out_duration", outgoingDurationSec)
        }
        sendBroadcast(intent)
    }

    private fun broadcastLog(msg: String) {
        // Buffer for replay when activity resumes (receiver is only active in
        // foreground).  Stamped here, not on replay: the UI used to timestamp
        // as it appended, so every line buffered while the app was closed —
        // the whole unattended history, which is the part worth reading —
        // collapsed onto the moment the app was opened.
        val stamped = "${bufferTimeFormat.format(java.util.Date())}  $msg"
        synchronized(logBuffer) {
            logBuffer.add(stamped)
            if (logBuffer.size > LOG_BUFFER_SIZE) logBuffer.removeAt(0)
        }
        val intent = Intent(LOG_ACTION).apply {
            setPackage(packageName)
            putExtra("msg", msg)
        }
        sendBroadcast(intent)
    }

    // ── Network ─────────────────────────────────────────

    private fun getLocalIp(): String {
        // Ask for the address of the network that actually carries our
        // traffic.  Enumerating interfaces and taking the first non-loopback
        // IPv4 could hand back the cellular rmnet address while SIP was
        // running over WiFi — so cellular attaching or detaching read as "the
        // local IP changed" and forced a reconnect and a fresh REGISTER that
        // WiFi never needed.  The active network's link address is the one the
        // socket will bind through.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val link = active?.let { cm.getLinkProperties(it) }
            link?.linkAddresses?.firstOrNull { la ->
                la.address is Inet4Address && !la.address.isLoopbackAddress
            }?.address?.hostAddress?.let { return it }
        } catch (e: Exception) {
            Log.w(TAG, "Active-network IP unavailable: ${e.message}")
        }

        // Fallback: interface scan, WiFi first for the same reason.
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                .toList()
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .let { java.util.Collections.enumeration(it) }
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
        }
        return "0.0.0.0"
    }

    /**
     * Force-allow RECORD_AUDIO via appops using root (Magisk).
     *
     * Android's AppOpsService can revoke RECORD_AUDIO (app op 27) for
     * background apps even when runtime permission is granted.  On the
     * second call the screen is off and the system may deny AudioRecord.
     *
     * Called SYNCHRONOUSLY on the gateway-init thread BEFORE initSipClient().
     * This ensures the appops command completes before SIP registration,
     * so no incoming calls can arrive while it's still pending.
     *
     * On cold boot, the appops service is NOT available for ~45-60 seconds
     * after the kernel starts.  This method BLOCKS until the service appears,
     * intentionally delaying SIP registration.  No calls can arrive until
     * both appops is verified AND SIP is registered.
     *
     * CRITICAL: Must use --uid flag to set the UID-level mode.
     * `appops set <pkg>` sets the package mode, but AudioFlinger checks
     * the UID mode (set by PermissionController).  UID mode overrides
     * package mode, so without --uid the allow is ineffective on cold boot.
     */
    private fun forceAllowRecordAudio() {
        try {
            val pkg = packageName

            // Wait for appops service to become available (cold boot).
            // Blocking here is intentional — initSipClient() must not run
            // until appops is set, because SIP registration makes us
            // reachable for incoming calls and AudioRecord will be denied
            // without the permission.  Polls every 3s for up to 90s.
            val maxWaitMs = 90_000L
            val pollMs = 3_000L
            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < maxWaitMs) {
                val uidProbe = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
                val probe = RootShell.execForOutput(
                    "appops get ${uidProbe}$pkg RECORD_AUDIO 2>&1"
                )
                if (!probe.contains("Can't find service", ignoreCase = true)) {
                    val waited = System.currentTimeMillis() - waitStart
                    if (waited > 100) {
                        Log.i(TAG, "appops service ready after ${waited}ms")
                        broadcastLog("System services ready (${waited / 1000}s)")
                    }
                    break
                }
                val elapsed = (System.currentTimeMillis() - waitStart) / 1000
                Log.i(TAG, "appops service not ready (${elapsed}s), waiting...")
                broadcastLog("Waiting for system services... (${elapsed}s)")
                Thread.sleep(pollMs)
            }

            val t0 = System.currentTimeMillis()
            val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
            val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
            val result = RootShell.execForOutput(
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "pm grant $pkg android.permission.RECORD_AUDIO 2>&1; " +
                autoRevoke +
                "appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                "appops set $pkg RECORD_AUDIO allow 2>&1; " +
                "killall com.google.android.permissioncontroller 2>/dev/null; " +
                "killall com.android.permissioncontroller 2>/dev/null; " +
                "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
            )
            val elapsed = System.currentTimeMillis() - t0
            val allowed = result.contains("allow", ignoreCase = true)
            Log.i(TAG, "appops RECORD_AUDIO: [$result] ok=$allowed (${elapsed}ms)")
            broadcastLog("appops RECORD_AUDIO: ok=$allowed (${elapsed}ms)")

            if (!allowed) {
                val fb = RootShell.execForOutput(
                    "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                    "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                )
                Log.w(TAG, "appops fallback cmd: [$fb]")
                broadcastLog("appops fallback: [$fb]")
            } else {
                Log.d(TAG, "appops RECORD_AUDIO verified: allow")
            }
        } catch (e: Exception) {
            Log.w(TAG, "appops force-allow failed (non-root?): ${e.message}")
            broadcastLog("appops RECORD_AUDIO failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "GatewayService"
        private const val LOG_BUFFER_SIZE = 200
        /** Ring buffer of recent log messages — survives activity pause/resume. */
        private val bufferTimeFormat =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

        val logBuffer = mutableListOf<String>()

        /** Drain buffered logs.  Returns all messages and clears the buffer. */
        fun drainLogBuffer(): List<String> = synchronized(logBuffer) {
            val copy = logBuffer.toList()
            logBuffer.clear()
            copy
        }

        /** Longest plausible time to bring a SIP client up; past this the
         *  init flag is assumed stuck rather than genuinely in progress. */
        private const val INIT_STALE_MS = 90_000L

        const val CHANNEL_ID = "gateway_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.callagent.gateway.START"
        const val ACTION_STOP = "com.callagent.gateway.STOP"
        const val ACTION_RELOAD_STATS = "com.callagent.gateway.RELOAD_STATS"
        const val ACTION_STATUS = "com.callagent.gateway.STATUS_REQUEST"
        const val ACTION_RECONNECT = "com.callagent.gateway.RECONNECT"
        const val ACTION_DIAL = "com.callagent.gateway.DIAL"
        const val ACTION_MONITOR = "com.callagent.gateway.MONITOR"
        const val EXTRA_MONITOR_ON = "monitor_on"
        const val ACTION_MUTE_AGENT = "com.callagent.gateway.MUTE_AGENT"
        const val EXTRA_MUTE_ON = "mute_on"
        const val EXTRA_SERVER = "server"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_NUMBER = "number"
        const val STATUS_ACTION = "com.callagent.gateway.STATUS"
        const val LOG_ACTION = "com.callagent.gateway.LOG"
        const val ACTION_APPLY_CONFIG = "com.callagent.gateway.APPLY_CONFIG"

        fun start(context: Context, server: String, port: Int, user: String, pass: String) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER, server)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USER, user)
                putExtra(EXTRA_PASS, pass)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
