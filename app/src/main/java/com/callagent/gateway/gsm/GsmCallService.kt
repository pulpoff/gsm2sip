package com.callagent.gateway.gsm

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

/**
 * InCallService implementation: intercepts all GSM calls on the device.
 *
 * When registered as the default dialer (or with BIND_INCALL_SERVICE permission
 * on rooted device), Android routes all call events through this service.
 *
 * Based on the telon-org/react-native-tele InCallService approach.
 */
class GsmCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val number = call.details?.handle?.schemeSpecificPart ?: "unknown"
        val state = call.state
        Log.i(TAG, "Call added: number=$number state=$state")

        call.registerCallback(callCallback)
        GsmCallManager.onCallAdded(call, this)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.i(TAG, "Call removed")
        call.unregisterCallback(callCallback)
        GsmCallManager.onCallRemoved(call)
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val stateStr = when (state) {
                Call.STATE_DIALING -> "DIALING"
                Call.STATE_RINGING -> "RINGING"
                Call.STATE_ACTIVE -> "ACTIVE"
                Call.STATE_HOLDING -> "HOLDING"
                Call.STATE_DISCONNECTED -> "DISCONNECTED"
                Call.STATE_CONNECTING -> "CONNECTING"
                Call.STATE_DISCONNECTING -> "DISCONNECTING"
                Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_ACCOUNT"
                else -> "UNKNOWN($state)"
            }
            Log.i(TAG, "Call state changed: $stateStr")
            GsmCallManager.onCallStateChanged(call, state)
        }
    }

    companion object {
        private const val TAG = "GsmCallService"
    }
}
