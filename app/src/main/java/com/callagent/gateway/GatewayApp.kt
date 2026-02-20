package com.callagent.gateway

import android.app.Application
import com.callagent.gateway.BuildConfig
import android.os.StrictMode
import android.util.Log

class GatewayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Allow network on main thread — this is a headless gateway service,
        // not a UI app.  GSM callbacks arrive on the main thread and need to
        // trigger short UDP sends (SIP INVITE / BYE).
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().permitAll().build()
        )
        Log.i("GatewayApp", "SIP-GSM Gateway v${BuildConfig.VERSION_NAME} started")
    }
}
