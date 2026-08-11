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

            sbn.packageName in MESSAGE_PACKAGES -> parseBkashReceivedPaymentMessage(
                packageName = sbn.packageName,
                title = title,
                text = text,
                bigText = bigText,
                lines = lines,
                occurredAt = sbn.postTime
            )

            else -> null
        } ?: return

        if (BridgeStorage.enqueue(applicationContext, payment)) {
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

    private fun parseBkashReceivedPaymentMessage(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        lines: String,
        occurredAt: Long
    ): CapturedPayment? {
        if (!isBkashSender(title)) return null

        val candidates = listOf(bigText, text, lines)
            .map(::normalizeMessage)
            .filter { it.isNotBlank() }

        val message = candidates.firstOrNull {
            it.startsWith(RECEIVED_PAYMENT_PREFIX, ignoreCase = true)
        } ?: return null

        val transactionId = parseTrxId(message) ?: return null
        val amount = parseAmount(message) ?: return null

        return CapturedPayment(
            transactionId = transactionId.uppercase(),
            amount = amount,
            reference = parseReference(message),
            sender = parseSender(message),
            occurredAt = occurredAt,
            rawText = message.take(4000),
            sourcePackage = packageName
        )
    }

    private fun isBkashSender(value: String): Boolean {
        val normalized = value
            .replace(Regex("[^A-Za-z]"), "")
            .lowercase()
        return normalized == "bkash"
    }

    private fun normalizeMessage(value: String): String = value
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
            val amountValue = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
            if (amountValue != null && amountValue > 0) return amountValue
        }
        return null
    }

    private fun parseTrxId(text: String): String? {
        val patterns = listOf(
            Regex("""(?:trx\s*id|trxid|transaction\s*id)\s*[:#-]?\s*([A-Za-z0-9]{6,60})""", RegexOption.IGNORE_CASE),
            Regex("""(?:ট্রানজেকশন\s*আইডি|ট্রানজেকশন\s*ID)\s*[:#-]?\s*([A-Za-z0-9]{6,60})""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { regex -> regex.find(text)?.groupValues?.getOrNull(1) }
    }

    private fun parseReference(text: String): String {
        val regex = Regex("""(?:reference|ref\.?|রেফারেন্স)\s*[:#-]?\s*([^,;|\n]{1,80})""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun parseSender(text: String): String {
        val regex = Regex("""(?:from|account|number|অ্যাকাউন্ট|নম্বর)\s*[:#-]?\s*((?:\+?88)?01[3-9][0-9Xx*]{8})""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    companion object {
        const val MERCHANT_PACKAGE = "com.bKash.merchantapp"
        const val RECEIVED_PAYMENT_PREFIX = "You have received payment"

        private val MESSAGE_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.miui.mms",
            "com.samsung.android.messaging"
        )
    }
}
