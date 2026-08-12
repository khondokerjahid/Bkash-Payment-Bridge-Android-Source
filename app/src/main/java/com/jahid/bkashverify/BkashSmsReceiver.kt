package com.jahid.bkashverify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class BkashSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

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

        // Sender name is NOT used as a hard filter because some phones/carriers
        // expose bKash sender IDs differently. Body prefix is the hard filter.
        val payment = BkashSmsParser.parse(
            sender = sender,
            body = body,
            occurredAt = occurredAt
        ) ?: return

        if (BridgeStorage.enqueue(context.applicationContext, payment)) {
            BridgeStorage.setLastSyncMessage(
                context.applicationContext,
                "bKash received-payment SMS detected · queued for sync"
            )
            SyncWorker.enqueueNow(context.applicationContext)
        }
    }
}
