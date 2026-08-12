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

        if (!isBkashSender(sender)) return

        val body = normalize(
            messages.joinToString(separator = "") {
                it.displayMessageBody.orEmpty()
            }
        )

        if (!body.startsWith(RECEIVED_PAYMENT_PREFIX, ignoreCase = true)) return

        val transactionId = parseTrxId(body) ?: return
        val amount = parseAmount(body) ?: return

        val payment = CapturedPayment(
            transactionId = transactionId.uppercase(),
            amount = amount,
            reference = parseReference(body),
            sender = sender,
            occurredAt = System.currentTimeMillis(),
            rawText = body.take(4000),
            // Normalized source understood by the current website compatibility patch.
            sourcePackage = SMS_SOURCE_PACKAGE
        )

        if (BridgeStorage.enqueue(context.applicationContext, payment)) {
            BridgeStorage.setLastSyncMessage(
                context.applicationContext,
                "bKash received-payment SMS detected · queued for sync"
            )
            SyncWorker.enqueueNow(context.applicationContext)
        }
    }

    private fun isBkashSender(value: String): Boolean {
        val normalized = value
            .replace(Regex("[^A-Za-z]"), "")
            .lowercase()
        return normalized == "bkash"
    }

    private fun normalize(value: String): String = value
        .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseAmount(text: String): Double? {
        val patterns = listOf(
            Regex("""(?:৳|Tk\.?|BDT)\s*([0-9,]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9,]+(?:\.[0-9]{1,2})?)\s*(?:৳|Tk\.?|BDT)""", RegexOption.IGNORE_CASE),
            Regex("""(?:amount|পরিমাণ)\s*[:#-]?\s*(?:৳|Tk\.?|BDT)?\s*([0-9,]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
        )

        for (regex in patterns) {
            val match = regex.find(text) ?: continue
            val amountValue = match.groupValues
                .getOrNull(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()

            if (amountValue != null && amountValue > 0) return amountValue
        }

        return null
    }

    private fun parseTrxId(text: String): String? {
        val patterns = listOf(
            Regex(
                """(?:trx\s*id|trxid|transaction\s*id)\s*[:#-]?\s*([A-Za-z0-9]{6,60})""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:ট্রানজেকশন\s*আইডি|ট্রানজেকশন\s*ID)\s*[:#-]?\s*([A-Za-z0-9]{6,60})""",
                RegexOption.IGNORE_CASE
            )
        )

        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.getOrNull(1)
        }
    }

    private fun parseReference(text: String): String {
        val regex = Regex(
            """(?:reference|ref\.?|রেফারেন্স)\s*[:#-]?\s*([^,;|\n]{1,80})""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    companion object {
        private const val RECEIVED_PAYMENT_PREFIX = "You have received payment"
        private const val SMS_SOURCE_PACKAGE = "com.android.mms"
    }
}
