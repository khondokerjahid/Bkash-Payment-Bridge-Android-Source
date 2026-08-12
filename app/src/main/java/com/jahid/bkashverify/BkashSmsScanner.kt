package com.jahid.bkashverify

import android.content.Context
import android.provider.Telephony

data class PreviousSmsScanResult(
    val scanned: Int,
    val prefixMatched: Int,
    val parsedPayments: Int,
    val newlyQueued: Int
)

object BkashSmsScanner {

    fun scanPreviousPayments(context: Context): PreviousSmsScanResult {
        var scanned = 0
        var prefixMatched = 0
        var parsed = 0
        var queued = 0

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )

        // Use the common SMS content provider and explicitly keep inbox messages.
        // This is more compatible across OEM messaging apps than relying on a
        // sender-name filter.
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms.TYPE}=?",
            arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString()),
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (cursor.moveToNext()) {
                scanned += 1

                val sender = cursor.getString(addressIndex).orEmpty()
                val body = cursor.getString(bodyIndex).orEmpty()
                val occurredAt = cursor.getLong(dateIndex)

                if (!BkashSmsParser.hasReceivedPaymentPrefix(body)) {
                    continue
                }

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

        val message = if (queued > 0) {
            "Previous SMS scan: $scanned scanned · $prefixMatched prefix match · $parsed parsed · $queued newly queued."
        } else {
            "Previous SMS scan: $scanned scanned · $prefixMatched prefix match · $parsed parsed · nothing new to sync."
        }

        BridgeStorage.setLastSyncMessage(context.applicationContext, message)

        if (queued > 0) {
            SyncWorker.enqueueNow(context.applicationContext)
        }

        return PreviousSmsScanResult(
            scanned = scanned,
            prefixMatched = prefixMatched,
            parsedPayments = parsed,
            newlyQueued = queued
        )
    }
}
