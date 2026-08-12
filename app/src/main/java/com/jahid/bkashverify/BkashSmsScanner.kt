package com.jahid.bkashverify

import android.content.Context
import android.provider.Telephony

data class PreviousSmsScanResult(
    val scanned: Int,
    val matchingBkashPayments: Int,
    val newlyQueued: Int
)

object BkashSmsScanner {

    fun scanPreviousPayments(context: Context): PreviousSmsScanResult {
        var scanned = 0
        var matching = 0
        var queued = 0

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (cursor.moveToNext()) {
                scanned += 1

                val sender = cursor.getString(addressIndex).orEmpty()
                if (!BkashSmsParser.isBkashSender(sender)) {
                    continue
                }

                val body = cursor.getString(bodyIndex).orEmpty()
                val occurredAt = cursor.getLong(dateIndex)

                val payment = BkashSmsParser.parse(
                    sender = sender,
                    body = body,
                    occurredAt = occurredAt
                ) ?: continue

                matching += 1

                if (BridgeStorage.enqueue(context.applicationContext, payment)) {
                    queued += 1
                }
            }
        }

        if (queued > 0) {
            BridgeStorage.setLastSyncMessage(
                context.applicationContext,
                "Previous SMS scan found $matching matching bKash payment(s); $queued newly queued."
            )
            SyncWorker.enqueueNow(context.applicationContext)
        } else {
            BridgeStorage.setLastSyncMessage(
                context.applicationContext,
                "Previous SMS scan found $matching matching bKash payment(s); nothing new to sync."
            )
        }

        return PreviousSmsScanResult(
            scanned = scanned,
            matchingBkashPayments = matching,
            newlyQueued = queued
        )
    }
}
