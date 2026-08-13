package com.jahid.bkashverify

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * This receiver is active only while bKash Payment Bridge is the phone's
 * temporary default SMS app. Android then delivers SMS_DELIVER here.
 *
 * The message is written back into the system SMS inbox so switching the
 * default SMS app for a history scan does not make a newly-arriving SMS vanish.
 */
class DefaultSmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()
            ?.displayOriginatingAddress
            .orEmpty()
            .trim()

        val body = messages.joinToString(separator = "") {
            it.displayMessageBody.orEmpty()
        }

        val occurredAt = messages.firstOrNull()?.timestampMillis
            ?: System.currentTimeMillis()

        // A default SMS app is responsible for persisting incoming SMS.
        // Store a single combined row for multipart messages.
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, sender)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, occurredAt)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
        } catch (_: Exception) {
            // Never block bKash detection if the OEM provider rejects an insert.
        }

        val payment = BkashSmsParser.parse(
            sender = sender,
            body = body,
            occurredAt = occurredAt
        ) ?: return

        if (BridgeStorage.enqueue(context.applicationContext, payment)) {
            BridgeStorage.setLastSyncMessage(
                context.applicationContext,
                "bKash SMS received while Full History Access was active · queued for sync"
            )
            SyncWorker.enqueueNow(context.applicationContext)
        }
    }
}
