package com.callagent.gateway.gsm

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * ConnectionService: allows the Telecom framework to route calls through our app.
 * Used for managing self-managed connections if needed.
 */
class CallConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateOutgoingConnection: ${request?.address}")
        return GatewayConnection().apply {
            setInitializing()
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateIncomingConnection")
        return GatewayConnection().apply {
            setRinging()
        }
    }

    inner class GatewayConnection : Connection() {
        override fun onAnswer() {
            Log.i(TAG, "Connection answered")
            setActive()
        }

        override fun onDisconnect() {
            Log.i(TAG, "Connection disconnected")
            setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.LOCAL))
            destroy()
        }

        override fun onAbort() {
            Log.i(TAG, "Connection aborted")
            setDisconnected(android.telecom.DisconnectCause(android.telecom.DisconnectCause.CANCELED))
            destroy()
        }
    }

    companion object {
        private const val TAG = "CallConnService"
    }
}
