package com.jahid.bkashverify

object BkashSmsParser {

    const val RECEIVED_PAYMENT_PREFIX = "You have received payment"
    const val SMS_SOURCE_PACKAGE = "com.android.mms"

    fun normalize(value: String): String = value
        .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E]"), "")
        .replace('\u00A0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    fun hasReceivedPaymentPrefix(body: String): Boolean =
        normalize(body).startsWith(RECEIVED_PAYMENT_PREFIX, ignoreCase = true)

    fun parse(
        sender: String,
        body: String,
        occurredAt: Long
    ): CapturedPayment? {
        val normalizedBody = normalize(body)

        // Main matching rule requested for this bridge:
        // ONLY process messages whose BODY starts with "You have received payment".
        // Do not reject a valid payment just because the SMS sender/address is displayed
        // differently by a carrier or phone manufacturer.
        if (!normalizedBody.startsWith(RECEIVED_PAYMENT_PREFIX, ignoreCase = true)) {
            return null
        }

        val transactionId = parseTrxId(normalizedBody) ?: return null
        val amount = parseAmount(normalizedBody) ?: return null

        return CapturedPayment(
            transactionId = transactionId.uppercase(),
            amount = amount,
            reference = parseReference(normalizedBody),
            sender = sender.trim(),
            occurredAt = occurredAt,
            rawText = normalizedBody.take(4000),
            sourcePackage = SMS_SOURCE_PACKAGE
        )
    }

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
}
