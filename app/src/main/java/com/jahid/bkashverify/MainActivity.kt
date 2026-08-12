package com.jahid.bkashverify

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var connectionStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var queueStatus: TextView
    private lateinit var lastPayment: TextView
    private lateinit var serverUrl: EditText
    private lateinit var pairingCode: EditText
    private lateinit var deviceName: EditText
    private lateinit var pairButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var scanPreviousSmsButton: Button

    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionStatus = findViewById(R.id.connectionStatus)
        notificationStatus = findViewById(R.id.notificationStatus)
        syncStatus = findViewById(R.id.syncStatus)
        queueStatus = findViewById(R.id.queueStatus)
        lastPayment = findViewById(R.id.lastPayment)
        serverUrl = findViewById(R.id.serverUrl)
        pairingCode = findViewById(R.id.pairingCode)
        deviceName = findViewById(R.id.deviceName)
        pairButton = findViewById(R.id.pairButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        scanPreviousSmsButton = findViewById(R.id.scanPreviousSmsButton)

        serverUrl.setText(BridgeStorage.apiBase(this))
        deviceName.setText(BridgeStorage.deviceName(this))

        findViewById<Button>(R.id.openNotificationSettings).setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        pairButton.setOnClickListener { connectPhone() }

        findViewById<Button>(R.id.syncButton).setOnClickListener {
            if (!BridgeStorage.isConnected(this)) {
                syncStatus.text = "Connect this phone to the website first."
            } else {
                SyncWorker.enqueueNow(this)
                syncStatus.text = "Sync requested..."
            }
        }

        scanPreviousSmsButton.setOnClickListener {
            scanPreviousBkashPayments()
        }

        findViewById<Button>(R.id.testButton).setOnClickListener { testConnection() }
        disconnectButton.setOnClickListener { disconnectPhone() }

        requestSmsPermissionsIfNeeded()

        if (BridgeStorage.isConnected(this)) {
            SyncWorker.ensurePeriodic(this)
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun hasReceiveSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasReadSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

    private fun requestSmsPermissionsIfNeeded() {
        val missing = buildList {
            if (!hasReceiveSmsPermission()) add(Manifest.permission.RECEIVE_SMS)
            if (!hasReadSmsPermission()) add(Manifest.permission.READ_SMS)
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                SMS_PERMISSION_REQUEST
            )
        }
    }

    private fun scanPreviousBkashPayments() {
        if (!hasReadSmsPermission()) {
            syncStatus.text = "Allow SMS access first, then tap Scan Previous bKash Payments again."
            requestSmsPermissionsIfNeeded()
            return
        }

        scanPreviousSmsButton.isEnabled = false
        syncStatus.text = "Scanning previous SMS for bKash received payments..."

        Thread {
            try {
                val result = BkashSmsScanner.scanPreviousPayments(this)

                runOnUiThread {
                    syncStatus.text =
                        "Previous SMS scan complete ✓ · Matching: ${result.matchingBkashPayments} · New: ${result.newlyQueued}"
                    scanPreviousSmsButton.isEnabled = true
                    refreshUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    syncStatus.text =
                        "Previous SMS scan failed: ${e.message ?: "Unknown error"}"
                    scanPreviousSmsButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun connectPhone() {
        val api = serverUrl.text.toString().trim()
        val code = pairingCode.text.toString().trim()
        val name = deviceName.text.toString().trim().ifBlank { android.os.Build.MODEL }

        if (api.isBlank() || code.isBlank()) {
            connectionStatus.text = "Enter the Website API URL and Pairing Code."
            return
        }

        pairButton.isEnabled = false
        connectionStatus.text = "Connecting..."

        Thread {
            try {
                val response = BridgeApi.pair(this, api, code, name)
                val token = response.optString("token")

                if (token.isBlank()) {
                    throw BridgeApiException("Server did not return a device token")
                }

                BridgeStorage.saveConnection(this, api, token, name)
                SyncWorker.ensurePeriodic(this)
                SyncWorker.enqueueNow(this)

                runOnUiThread {
                    serverUrl.setText(BridgeStorage.apiBase(this))
                    pairingCode.setText("")
                    connectionStatus.text = "Connected securely ✓"
                    pairButton.isEnabled = true
                    refreshUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connectionStatus.text =
                        "Connection failed: ${e.message ?: "Unknown error"}"
                    pairButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun testConnection() {
        if (!BridgeStorage.isConnected(this)) {
            syncStatus.text = "Phone is not connected yet."
            return
        }

        syncStatus.text = "Checking website connection..."

        Thread {
            try {
                BridgeApi.heartbeat(this)
                BridgeStorage.setLastSyncMessage(
                    this,
                    "Website connection OK · ${Date()}"
                )

                runOnUiThread {
                    syncStatus.text = "Website connection OK ✓"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    syncStatus.text = "Connection check failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun disconnectPhone() {
        if (!BridgeStorage.isConnected(this)) return

        disconnectButton.isEnabled = false
        connectionStatus.text = "Disconnecting..."

        Thread {
            try {
                BridgeApi.disconnect(this)
            } catch (_: Exception) {
                // Local token is removed even if the website is temporarily unreachable.
            } finally {
                BridgeStorage.disconnect(this)

                runOnUiThread {
                    pairingCode.setText("")
                    disconnectButton.isEnabled = true
                    connectionStatus.text =
                        "Disconnected. Generate a new pairing code to reconnect."
                    refreshUi()
                }
            }
        }.start()
    }

    private fun refreshUi() {
        val connected = BridgeStorage.isConnected(this)

        connectionStatus.text = if (connected) {
            "Website connection: CONNECTED ✓\n${BridgeStorage.apiBase(this)}"
        } else {
            "Website connection: NOT CONNECTED"
        }

        val listenerComponent =
            ComponentName(this, BkashNotificationListener::class.java)

        val notificationEnabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )?.split(":")?.any {
            it.equals(listenerComponent.flattenToString(), true)
        } == true

        notificationStatus.text = buildString {
            append(
                if (notificationEnabled)
                    "Notification access: ON ✓"
                else
                    "Notification access: OFF"
            )
            append(" · ")
            append(
                if (hasReceiveSmsPermission())
                    "Direct SMS: ON ✓"
                else
                    "Direct SMS: OFF"
            )
            append(" · ")
            append(
                if (hasReadSmsPermission())
                    "Previous SMS scan: ON ✓"
                else
                    "Previous SMS scan: OFF"
            )

            if (!hasReceiveSmsPermission() || !hasReadSmsPermission()) {
                append("\nAllow SMS permission for new-payment detection and previous-payment scanning.")
            }
        }

        scanPreviousSmsButton.isEnabled = hasReadSmsPermission()

        val pending = BridgeStorage.pending(this)
        queueStatus.text = "Pending sync: ${pending.size}"
        syncStatus.text = BridgeStorage.lastSyncMessage(this)

        val payment = BridgeStorage.lastDetected(this)

        lastPayment.text = if (payment == null) {
            "No matching bKash payment notification or received-payment SMS detected yet."
        } else {
            val date = SimpleDateFormat(
                "dd MMM, hh:mm a",
                Locale.getDefault()
            ).format(Date(payment.occurredAt))

            buildString {
                append("TrxID: ${payment.transactionId}\n")
                append(
                    "Amount: ৳${
                        String.format(
                            Locale.US,
                            "%.2f",
                            payment.amount
                        )
                    }"
                )

                if (payment.reference.isNotBlank()) {
                    append("\nReference: ${payment.reference}")
                }

                append("\nDetected: $date")
            }
        }

        pairButton.text =
            if (connected) "Pair Again With New Code" else "Connect Phone"

        disconnectButton.isEnabled = connected
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == SMS_PERMISSION_REQUEST) {
            refreshUi()
        }
    }

    companion object {
        private const val SMS_PERMISSION_REQUEST = 4001
    }
}
