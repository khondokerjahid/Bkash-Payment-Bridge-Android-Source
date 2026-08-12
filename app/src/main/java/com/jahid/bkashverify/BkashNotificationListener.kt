package com.jahid.bkashverify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class BkashNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val lines = extras.getCharSequenceArray("android.textLines")
            ?.joinToString(" ") { it.toString() }
            .orEmpty()

        val payment = when {
            sbn.packageName == MERCHANT_PACKAGE -> parseMerchantNotification(
                packageName = sbn.packageName,
                title = title,
                text = text,
                bigText = bigText,
                lines = lines,
                occurredAt = sbn.postTime
            )

            // Permanent SMS-notification path:
            // Do NOT depend on a fixed Messages package name or notification title.
            // Xiaomi/HyperOS, Google Messages, Samsung Messages and other OEM apps
            // can expose different package/title values. The payment BODY itself is
            // the hard filter: it must contain the exact bKash received-payment prefix
            // and a valid TrxID + amount.
            else -> parseReceivedPaymentFromAnyNotification(
                packageName = sbn.packageName,
                title = title,
                text = text,
                bigText = bigText,
                lines = lines,
                occurredAt = sbn.postTime
            )
        } ?: return

        if (BridgeStorage.enqueue(applicationContext, payment)) {
            BridgeStorage.setLastSyncMessage(
                applicationContext,
                "bKash payment detected from phone notification · queued for sync"
            )
            SyncWorker.enqueueNow(applicationContext)
        }
    }

    private fun parseMerchantNotification(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        lines: String,
        occurredAt: Long
    ): CapturedPayment? {
        val combined = normalizeMessage(
            listOf(title, text, bigText, lines)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )

        if (combined.isBlank()) return null

        val transactionId = parseTrxId(combined) ?: return null
        val amount = parseAmount(combined) ?: return null

        return CapturedPayment(
            transactionId = transactionId.uppercase(),
            amount = amount,
            reference = parseReference(combined),
            sender = parseSender(combined),
            occurredAt = occurredAt,
            rawText = combined.take(4000),
            sourcePackage = packageName
        )
    }

    private fun parseReceivedPaymentFromAnyNotification(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        lines: String,
        occurredAt: Long
    ): CapturedPayment? {
        // Prefer rich/full notification fields first, then fall back to combinations.
        val candidates = listOf(
            bigText,
            text,
            lines,
            listOf(title, bigText).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, text).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, lines).filter { it.isNotBlank() }.joinToString(" "),
            listOf(title, text, bigText, lines).filter { it.isNotBlank() }.joinToString(" ")
        )
            .map(::normalizeMessage)
            .filter { it.isNotBlank() }
            .distinct()

        for (candidate in candidates) {
            val paymentText = extractReceivedPaymentText(candidate) ?: continue
            val transactionId = parseTrxId(paymentText) ?: continue
            val amount = parseAmount(paymentText) ?: continue

            return CapturedPayment(
                transactionId = transactionId.uppercase(),
                amount = amount,
                reference = parseReference(paymentText),
                sender = parseSender(paymentText),
                occurredAt = occurredAt,
                rawText = paymentText.take(4000),
                sourcePackage = packageName
            )
        }

        return null
    }

    private fun extractReceivedPaymentText(value: String): String? {
        val normalized = normalizeMessage(value)
        val index = normalized.indexOf(RECEIVED_PAYMENT_PREFIX, ignoreCase = true)
        if (index < 0) return null

        val paymentText = normalized.substring(index).trim()

        // Exact body prefix is the security/filtering rule.
        if (!paymentText.startsWith(RECEIVED_PAYMENT_PREFIX, ignoreCase = true)) {
            return null
        }

        return paymentText
    }

    private fun normalizeMessage(value: String): String = value
        .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E]"), "")
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseAmount(text: String): Double? {
        val patterns = listOf(
            Regex(
                """(?:৳|Tk\.?|BDT)\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """([0-9,]+(?:\.[0-9]{1,2})?)\s*(?:৳|Tk\.?|BDT)""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:amount|পরিমাণ)\s*[:#-]?\s*(?:৳|Tk\.?|BDT)?\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
                RegexOption.IGNORE_CASE
            )
        )

        for (regex in patterns) {
            val match = regex.find(text) ?: continue
            val value = match.groupValues
                .getOrNull(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()

            if (value != null && value > 0) return value
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

    private fun parseSender(text: String): String {
        val regex = Regex(
            """(?:from|account|number|অ্যাকাউন্ট|নম্বর)\s*[:#-]?\s*((?:\+?88)?01[3-9][0-9Xx*]{8})""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    companion object {
        const val MERCHANT_PACKAGE = "com.bKash.merchantapp"
        const val RECEIVED_PAYMENT_PREFIX = "You have received payment"
    }
}
