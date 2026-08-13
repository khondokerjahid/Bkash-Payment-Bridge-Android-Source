package com.jahid.bkashverify

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Telephony
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
    private lateinit var fullHistoryStatus: TextView
    private lateinit var serverUrl: EditText
    private lateinit var pairingCode: EditText
    private lateinit var deviceName: EditText
    private lateinit var pairButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var fullHistoryButton: Button
    private lateinit var restoreSmsButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var pendingFullHistoryScan = false

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
        fullHistoryStatus = findViewById(R.id.fullHistoryStatus)
        serverUrl = findViewById(R.id.serverUrl)
        pairingCode = findViewById(R.id.pairingCode)
        deviceName = findViewById(R.id.deviceName)
        pairButton = findViewById(R.id.pairButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        fullHistoryButton = findViewById(R.id.fullHistoryButton)
        restoreSmsButton = findViewById(R.id.restoreSmsButton)

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

        fullHistoryButton.setOnClickListener { beginFullPhoneHistoryScan() }
        restoreSmsButton.setOnClickListener { openDefaultSmsSettings() }
        findViewById<Button>(R.id.testButton).setOnClickListener { testConnection() }
        disconnectButton.setOnClickListener { disconnectPhone() }

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

        if (pendingFullHistoryScan && hasSmsRole()) {
            ensureSmsPermissionsThenScan()
        }
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

    /**
     * Xiaomi/HyperOS can report a stale value from
     * Telephony.Sms.getDefaultSmsPackage() even while RoleManager already shows
     * this package as the SMS role holder.
     *
     * RoleManager is therefore the primary source of truth on Android 10+.
     * Telephony is kept only as a legacy/fallback check.
     */
    private fun hasSmsRole(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = getSystemService(RoleManager::class.java)
                if (
                    roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                    roleManager.isRoleHeld(RoleManager.ROLE_SMS)
                ) {
                    return true
                }
            } catch (_: Exception) {
            }
        }

        return try {
            Telephony.Sms.getDefaultSmsPackage(this) == packageName
        } catch (_: Exception) {
            false
        }
    }

    private fun currentDefaultSmsPackage(): String =
        try {
            Telephony.Sms.getDefaultSmsPackage(this).orEmpty()
        } catch (_: Exception) {
            ""
        }

    private fun beginFullPhoneHistoryScan() {
        if (!BridgeStorage.isConnected(this)) {
            fullHistoryStatus.text = "Connect this phone to the website first."
            return
        }

        pendingFullHistoryScan = true
        savePreviousSmsPackageIfNeeded()

        // Important for Xiaomi:
        // If ADB/RoleManager has already granted SMS role, DO NOT show another
        // default-app dialog. Go straight to permission verification + real inbox scan.
        if (hasSmsRole()) {
            fullHistoryStatus.text = "Full phone SMS access detected ✓ · starting real inbox scan..."
            ensureSmsPermissionsThenScan()
            return
        }

        fullHistoryStatus.text =
            "Android needs temporary SMS-role access to read the real phone inbox. Approve the next system prompt."

        requestSmsRole()
    }

    private fun savePreviousSmsPackageIfNeeded() {
        val current = currentDefaultSmsPackage()
        if (current.isBlank() || current == packageName) return

        getSharedPreferences(ROLE_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_PREVIOUS_SMS_PACKAGE, current)
            .apply()
    }

    private fun previousSmsPackage(): String =
        getSharedPreferences(ROLE_PREFS, MODE_PRIVATE)
            .getString(KEY_PREVIOUS_SMS_PACKAGE, "")
            .orEmpty()

    private fun requestSmsRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (!roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    fullHistoryStatus.text = "This phone does not expose the Android SMS role."
                    pendingFullHistoryScan = false
                    return
                }

                if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    ensureSmsPermissionsThenScan()
                    return
                }

                startActivityForResult(
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS),
                    SMS_ROLE_REQUEST
                )
            } else {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                }
                startActivityForResult(intent, SMS_ROLE_REQUEST)
            }
        } catch (e: Exception) {
            fullHistoryStatus.text =
                "Could not open Full SMS History Access: ${e.message ?: "Unknown error"}"
            pendingFullHistoryScan = false
        }
    }

    private fun ensureSmsPermissionsThenScan() {
        if (!hasSmsRole()) {
            fullHistoryStatus.text =
                "SMS role is not active yet. Grant Full Access and try again."
            pendingFullHistoryScan = false
            return
        }

        val missing = buildList {
            if (!hasReadSmsPermission()) add(Manifest.permission.READ_SMS)
            if (!hasReceiveSmsPermission()) add(Manifest.permission.RECEIVE_SMS)
        }

        if (missing.isNotEmpty()) {
            fullHistoryStatus.text = "Allow SMS permission so the real phone inbox can be scanned."
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                SMS_PERMISSION_REQUEST
            )
            return
        }

        scanRealPhoneInbox()
    }

    private fun scanRealPhoneInbox() {
        pendingFullHistoryScan = false
        fullHistoryButton.isEnabled = false
        fullHistoryStatus.text =
            "Scanning the real phone SMS inbox now. Read/Seen messages are included..."

        Thread {
            try {
                val result = BkashSmsScanner.scanPreviousPayments(this)

                if (result.newlyQueued > 0) {
                    SyncWorker.enqueueNow(this)
                }

                runOnUiThread {
                    fullHistoryStatus.text = buildString {
                        append("Phone inbox scan complete ✓")
                        append("\nSMS scanned: ${result.scanned}")
                        append(" · bKash payment format: ${result.prefixMatched}")
                        append(" · parsed: ${result.parsedPayments}")
                        append(" · newly queued: ${result.newlyQueued}")
                        append("\nRead/Seen status does NOT exclude messages.")
                        if (result.newlyQueued > 0) {
                            append("\nQueued payments are being synced to Admin now.")
                        } else {
                            append("\nNo new unsynced bKash payment was found.")
                        }
                    }
                    fullHistoryButton.isEnabled = true
                    refreshUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    fullHistoryStatus.text =
                        "Real phone inbox scan failed: ${e.message ?: "Unknown error"}"
                    fullHistoryButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun openDefaultSmsSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            val previous = previousSmsPackage()
            fullHistoryStatus.text = if (previous.isBlank()) {
                "Choose your normal Messages app as the default SMS app."
            } else {
                "Restore your normal SMS app. Previous package: $previous"
            }
        } catch (_: Exception) {
            fullHistoryStatus.text =
                "Open Settings > Apps > Default apps > SMS app and restore your normal Messages app."
        }
    }

    private fun connectPhone() {
        val api = serverUrl.text.toString().trim()
        val code = pairingCode.text.toString().trim()
        val name = deviceName.text.toString().trim().ifBlank { Build.MODEL }

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
                BridgeStorage.setLastSyncMessage(this, "Website connection OK · ${Date()}")
                runOnUiThread { syncStatus.text = "Website connection OK ✓" }
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

        val listenerComponent = ComponentName(this, BkashNotificationListener::class.java)

        val notificationEnabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )?.split(":")?.any {
            it.equals(listenerComponent.flattenToString(), true)
        } == true

        notificationStatus.text = buildString {
            append(if (notificationEnabled) "Notification access: ON ✓" else "Notification access: OFF")
            append(" · ")
            append(if (hasReceiveSmsPermission()) "Direct SMS: ON ✓" else "Direct SMS: OFF")
            append("\n")
            append(
                if (hasSmsRole())
                    "Full phone SMS history access: ACTIVE ✓"
                else
                    "Full phone SMS history access: OFF"
            )
        }

        val pending = BridgeStorage.pending(this)
        queueStatus.text = "Pending sync: ${pending.size}"
        syncStatus.text = BridgeStorage.lastSyncMessage(this)

        val payment = BridgeStorage.lastDetected(this)

        lastPayment.text = if (payment == null) {
            "No matching bKash payment detected yet."
        } else {
            val date = SimpleDateFormat(
                "dd MMM, hh:mm a",
                Locale.getDefault()
            ).format(Date(payment.occurredAt))

            buildString {
                append("TrxID: ${payment.transactionId}\n")
                append("Amount: ৳${String.format(Locale.US, "%.2f", payment.amount)}")
                if (payment.reference.isNotBlank()) {
                    append("\nReference: ${payment.reference}")
                }
                append("\nDetected: $date")
            }
        }

        pairButton.text = if (connected) "Pair Again With New Code" else "Connect Phone"
        disconnectButton.isEnabled = connected
        fullHistoryButton.isEnabled = connected
        restoreSmsButton.isEnabled = hasSmsRole()
    }

    @Deprecated("Deprecated in Android framework; kept for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SMS_ROLE_REQUEST) {
            if (hasSmsRole()) {
                fullHistoryStatus.text = "Full phone SMS history access granted ✓"
                ensureSmsPermissionsThenScan()
            } else {
                fullHistoryStatus.text =
                    "Full history scan cancelled. Android did not grant temporary SMS access."
                pendingFullHistoryScan = false
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == SMS_PERMISSION_REQUEST) {
            if (hasSmsRole() && hasReadSmsPermission()) {
                scanRealPhoneInbox()
            } else {
                fullHistoryStatus.text =
                    "SMS permission was not granted, so the real phone inbox could not be scanned."
                pendingFullHistoryScan = false
            }
            refreshUi()
        }
    }

    companion object {
        private const val SMS_ROLE_REQUEST = 4101
        private const val SMS_PERMISSION_REQUEST = 4102
        private const val ROLE_PREFS = "bkash_sms_role_prefs"
        private const val KEY_PREVIOUS_SMS_PACKAGE = "previous_sms_package"
    }
}
