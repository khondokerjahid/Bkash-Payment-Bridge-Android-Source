package com.jahid.bkashverify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class BkashNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MERCHANT_PACKAGE) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val lines = extras.getCharSequenceArray("android.textLines")
            ?.joinToString(" ") { it.toString() }
            .orEmpty()

        val combined = listOf(title, text, bigText, lines)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (combined.isBlank()) return

        val transactionId = parseTrxId(combined) ?: return
        val amount = parseAmount(combined) ?: return
        val reference = parseReference(combined)
        val sender = parseSender(combined)

        val payment = CapturedPayment(
            transactionId = transactionId.uppercase(),
            amount = amount,
            reference = reference,
            sender = sender,
            occurredAt = sbn.postTime,
            rawText = combined.take(4000),
            sourcePackage = sbn.packageName
        )

        if (BridgeStorage.enqueue(applicationContext, payment)) {
            SyncWorker.enqueueNow(applicationContext)
        }
    }

    private fun parseAmount(text: String): Double? {
        val patterns = listOf(
            Regex("""(?:৳|Tk\\.?|BDT)\\s*([0-9,]+(?:\\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE),
            Regex("""([0-9,]+(?:\\.[0-9]{1,2})?)\\s*(?:৳|Tk\\.?|BDT)""", RegexOption.IGNORE_CASE),
            Regex("""(?:amount|পরিমাণ)\\s*[:#-]?\\s*(?:৳|Tk\\.?|BDT)?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            val match = regex.find(text) ?: continue
            val value = match.groupValues.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
            if (value != null && value > 0) return value
        }
        return null
    }

    private fun parseTrxId(text: String): String? {
        val patterns = listOf(
            Regex("""(?:trx\\s*id|trxid|transaction\\s*id)\\s*[:#-]?\\s*([A-Za-z0-9]{6,60})""", RegexOption.IGNORE_CASE),
            Regex("""(?:ট্রানজেকশন\\s*আইডি|ট্রানজেকশন\\s*ID)\\s*[:#-]?\\s*([A-Za-z0-9]{6,60})""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { regex -> regex.find(text)?.groupValues?.getOrNull(1) }
    }

    private fun parseReference(text: String): String {
        val regex = Regex("""(?:reference|ref\\.?|রেফারেন্স)\\s*[:#-]?\\s*([^,;|\\n]{1,80})""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun parseSender(text: String): String {
        val regex = Regex("""(?:from|account|number|অ্যাকাউন্ট|নম্বর)\\s*[:#-]?\\s*((?:\\+?88)?01[3-9][0-9Xx*]{8})""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    companion object {
        const val MERCHANT_PACKAGE = "com.bKash.merchantapp"
    }
}
