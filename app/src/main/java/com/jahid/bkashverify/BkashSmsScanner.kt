package com.jahid.bkashverify

import android.content.Context
import android.net.Uri

data class PreviousSmsScanResult(
    val scanned: Int,
    val prefixMatched: Int,
    val parsedPayments: Int,
    val newlyQueued: Int
)

object BkashSmsScanner {

    private val inboxUri: Uri = Uri.parse("content://sms/inbox")
    private const val PREFS = "bkash_sms_recovery"
    private const val KEY_LAST_RECOVERY_SCAN = "last_recovery_scan_ms"
    private const val FIRST_RECOVERY_LOOKBACK_MS = 30L * 24L * 60L * 60L * 1000L
    private const val RECOVERY_OVERLAP_MS = 5L * 60L * 1000L

    // Manual button: scan the full inbox history.
    fun scanPreviousPayments(context: Context): PreviousSmsScanResult =
        scanInbox(context, sinceMs = null, saveCheckpoint = true)

    // Permanent safety-net: recover any SMS that the live BroadcastReceiver missed.
    // SyncWorker calls this before every sync (including the 15-minute periodic sync).
    fun scanRecoveryPayments(context: Context): PreviousSmsScanResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_RECOVERY_SCAN, now - FIRST_RECOVERY_LOOKBACK_MS)
        val since = (last - RECOVERY_OVERLAP_MS).coerceAtLeast(0L)
        return scanInbox(context, sinceMs = since, saveCheckpoint = true)
    }

    private fun scanInbox(
        context: Context,
        sinceMs: Long?,
        saveCheckpoint: Boolean
    ): PreviousSmsScanResult {
        var scanned = 0
        var prefixMatched = 0
        var parsed = 0
        var queued = 0

        val projection = arrayOf("address", "body", "date")

        // IMPORTANT: use the exact inbox provider confirmed to work on this phone.
        val cursor = context.contentResolver.query(
            inboxUri,
            projection,
            null,
            null,
            "date DESC"
        ) ?: throw IllegalStateException("Android SMS inbox provider returned no cursor")

        cursor.use {
            val addressIndex = it.getColumnIndexOrThrow("address")
            val bodyIndex = it.getColumnIndexOrThrow("body")
            val dateIndex = it.getColumnIndexOrThrow("date")

            while (it.moveToNext()) {
                val occurredAt = it.getLong(dateIndex)

                // Rows are newest first; recovery scan can stop once older than window.
                if (sinceMs != null && occurredAt < sinceMs) break

                scanned += 1

                val sender = it.getString(addressIndex).orEmpty()
                val body = it.getString(bodyIndex).orEmpty()

                if (!BkashSmsParser.hasReceivedPaymentPrefix(body)) continue
                prefixMatched += 1

                val payment = BkashSmsParser.parse(
                    sender = sender,
                    body = body,
                    occurredAt = occurredAt
                ) ?: continue

                parsed += 1

                if (BridgeStorage.enqueue(context.applicationContext, payment)) {
                    queued += 1
                }
            }
        }

        if (saveCheckpoint) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_RECOVERY_SCAN, System.currentTimeMillis())
                .apply()
        }

        val mode = if (sinceMs == null) "Previous SMS scan" else "Auto recovery scan"
        val message = if (queued > 0) {
            "$mode: $scanned scanned · $prefixMatched prefix match · $parsed parsed · $queued newly queued."
        } else {
            "$mode: $scanned scanned · $prefixMatched prefix match · $parsed parsed · nothing new to sync."
        }
        BridgeStorage.setLastSyncMessage(context.applicationContext, message)

        return PreviousSmsScanResult(scanned, prefixMatched, parsed, queued)
    }
}
