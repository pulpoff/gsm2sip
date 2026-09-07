package com.callagent.gateway.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.callagent.gateway.RootShell

/**
 * Auto-starts the gateway service on device boot.
 * Only starts if SIP credentials are configured.
 *
 * Also pre-grants RECORD_AUDIO permission at the earliest possible moment
 * (before GatewayService starts) so the permission is settled by the time
 * the first call arrives.  On cold boot, PermissionController aggressively
 * re-revokes permissions for background apps — doing it here gives maximum
 * lead time for the grant to propagate through AudioFlinger.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i(TAG, "Boot/update received, checking config...")

            // Pre-grant RECORD_AUDIO at earliest boot moment.
            // GatewayService.startGateway() also grants it, but doing it
            // here gives extra lead time on cold boot before any call arrives.
            earlyPermissionSetup(context)

            val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("autoconnect", true)) {
                Log.i(TAG, "Autoconnect disabled, skipping")
                return
            }
            val server = prefs.getString("server", "") ?: ""
            val user = prefs.getString("user", "") ?: ""

            if (server.isNotEmpty() && user.isNotEmpty()) {
                Log.i(TAG, "Config found, starting gateway service")
                val port = prefs.getInt("port", 5060)
                val pass = prefs.getString("pass", "") ?: ""
                GatewayService.start(context, server, port, user, pass)
            } else {
                Log.i(TAG, "No config, skipping auto-start")
            }
        }
    }

    /**
     * Grant RECORD_AUDIO as early as possible on boot.  Uses a background
     * thread since RootShell.exec() blocks (Magisk su takes 4+ seconds on
     * first cold-boot invocation).  BroadcastReceiver.onReceive() must
     * return quickly to avoid ANR.
     *
     * On cold boot, the appops service is NOT available for ~45-60 seconds
     * after the kernel starts (error: "Can't find service appops").
     * This thread polls until the service appears, then sets permissions
     * at the earliest possible moment.
     */
    private fun earlyPermissionSetup(context: Context) {
        Thread({
            try {
                RootShell.init()
                val pkg = context.packageName

                // Wait for appops service to become available.
                // On cold boot, BootReceiver fires very early when system
                // services aren't ready yet.  Poll every 3s for up to 90s.
                val maxWaitMs = 90_000L
                val pollMs = 3_000L
                val waitStart = System.currentTimeMillis()
                val uidFlag = if (Build.VERSION.SDK_INT >= 29) "--uid " else ""
                while (System.currentTimeMillis() - waitStart < maxWaitMs) {
                    val probe = RootShell.execForOutput(
                        "appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                    )
                    if (!probe.contains("Can't find service", ignoreCase = true)) {
                        Log.i(TAG, "appops service ready after ${System.currentTimeMillis() - waitStart}ms")
                        break
                    }
                    Log.d(TAG, "appops service not ready (${(System.currentTimeMillis() - waitStart) / 1000}s)")
                    Thread.sleep(pollMs)
                }

                val t0 = System.currentTimeMillis()
                val autoRevoke = if (Build.VERSION.SDK_INT >= 30)
                    "appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1; " else ""
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
                Log.i(TAG, "Early RECORD_AUDIO: [$result] ok=$allowed (${elapsed}ms)")

                if (!allowed) {
                    val fb = RootShell.execForOutput(
                        "cmd appops set ${uidFlag}$pkg RECORD_AUDIO allow 2>&1; " +
                        "cmd appops set $pkg RECORD_AUDIO allow 2>&1; " +
                        "cmd appops get ${uidFlag}$pkg RECORD_AUDIO 2>&1"
                    )
                    Log.w(TAG, "Early RECORD_AUDIO fallback: [$fb]")
                } else {
                    Log.d(TAG, "Early RECORD_AUDIO verified: allow")
                }

                // NOTE: boot-time AudioRecord priming was removed.
                // AudioRecord init is handled in RtpSession at call time.
            } catch (e: Exception) {
                Log.w(TAG, "Early permission setup failed: ${e.message}")
            }
        }, "boot-perms").start()
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
