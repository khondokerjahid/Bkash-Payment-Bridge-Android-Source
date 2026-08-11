package com.jahid.bkashverify

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CapturedPayment(
    val transactionId: String,
    val amount: Double,
    val reference: String,
    val sender: String,
    val occurredAt: Long,
    val rawText: String,
    val sourcePackage: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("transactionId", transactionId)
        put("amount", amount)
        put("reference", reference)
        put("sender", sender)
        put("occurredAt", occurredAt)
        put("rawText", rawText)
        put("sourcePackage", sourcePackage)
    }

    companion object {
        fun fromJson(json: JSONObject): CapturedPayment = CapturedPayment(
            transactionId = json.optString("transactionId"),
            amount = json.optDouble("amount", 0.0),
            reference = json.optString("reference"),
            sender = json.optString("sender"),
            occurredAt = json.optLong("occurredAt", System.currentTimeMillis()),
            rawText = json.optString("rawText"),
            sourcePackage = json.optString("sourcePackage", BkashNotificationListener.MERCHANT_PACKAGE)
        )
    }
}

object BridgeStorage {
    private const val PREFS = "bkash_bridge_prefs"
    private const val KEY_API = "api_base"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_TOKEN_CIPHER = "device_token_cipher"
    private const val KEY_TOKEN_IV = "device_token_iv"
    private const val KEYSTORE_ALIAS = "bkash_bridge_device_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_PENDING = "pending_transactions"
    private const val KEY_SENT = "sent_transaction_ids"
    private const val KEY_LAST = "last_detected"
    private const val KEY_LAST_SYNC = "last_sync_message"
    private const val MAX_QUEUE = 200
    private const val MAX_SENT = 300
    private val queueLock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiBase(context: Context): String = prefs(context).getString(KEY_API, "") ?: ""

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptToken(context: Context, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(KEY_TOKEN_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .remove(KEY_TOKEN)
            .apply()
    }

    fun token(context: Context): String {
        val p = prefs(context)
        val encrypted = p.getString(KEY_TOKEN_CIPHER, "") ?: ""
        val iv = p.getString(KEY_TOKEN_IV, "") ?: ""
        if (encrypted.isNotBlank() && iv.isNotBlank()) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateSecretKey(),
                    GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
                )
                return String(
                    cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                    Charsets.UTF_8
                )
            } catch (_: Exception) {
                return ""
            }
        }
        return p.getString(KEY_TOKEN, "") ?: ""
    }

    fun deviceName(context: Context): String = prefs(context).getString(KEY_DEVICE_NAME, Build.MODEL) ?: Build.MODEL

    fun deviceId(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_DEVICE_ID, "") ?: ""
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun normalizeApiBase(value: String): String {
        var clean = value.trim().trimEnd('/')
        if (clean.isBlank()) return ""
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
            clean = "https://$clean"
        }
        if (!Regex("/api$", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            clean += "/api"
        }
        return clean
    }

    fun saveConnection(context: Context, apiBase: String, token: String, deviceName: String) {
        prefs(context).edit()
            .putString(KEY_API, normalizeApiBase(apiBase))
            .putString(KEY_DEVICE_NAME, deviceName.trim().ifBlank { Build.MODEL })
            .apply()
        encryptToken(context, token)
    }

    fun disconnect(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_TOKEN_CIPHER)
            .remove(KEY_TOKEN_IV)
            .apply()
    }

    fun isConnected(context: Context): Boolean = apiBase(context).isNotBlank() && token(context).isNotBlank()

    fun enqueue(context: Context, payment: CapturedPayment): Boolean = synchronized(queueLock) {
        if (payment.transactionId.isBlank() || wasSent(context, payment.transactionId)) return false
        val queue = pending(context).toMutableList()
        if (queue.any { it.transactionId.equals(payment.transactionId, true) }) return false
        queue.add(0, payment)
        savePending(context, queue.take(MAX_QUEUE))
        saveLastDetected(context, payment)
        true
    }

    fun pending(context: Context): List<CapturedPayment> = synchronized(queueLock) {
        val raw = prefs(context).getString(KEY_PENDING, "[]") ?: "[]"
        return@synchronized try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = CapturedPayment.fromJson(array.optJSONObject(i) ?: continue)
                    if (item.transactionId.isNotBlank()) add(item)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePending(context: Context, queue: List<CapturedPayment>) {
        val array = JSONArray()
        queue.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_PENDING, array.toString()).apply()
    }

    fun markSent(context: Context, transactionId: String) = synchronized(queueLock) {
        val queue = pending(context).filterNot { it.transactionId.equals(transactionId, true) }
        savePending(context, queue)

        val sent = sentIds(context).toMutableList()
        sent.removeAll { it.equals(transactionId, true) }
        sent.add(0, transactionId.uppercase())
        prefs(context).edit().putString(KEY_SENT, JSONArray(sent.take(MAX_SENT)).toString()).apply()
    }

    private fun sentIds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SENT, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optString(i)
                    if (item.isNotBlank()) add(item)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun wasSent(context: Context, transactionId: String): Boolean =
        sentIds(context).any { it.equals(transactionId, true) }

    private fun saveLastDetected(context: Context, payment: CapturedPayment) {
        prefs(context).edit().putString(KEY_LAST, payment.toJson().toString()).apply()
    }

    fun lastDetected(context: Context): CapturedPayment? {
        val raw = prefs(context).getString(KEY_LAST, "") ?: ""
        if (raw.isBlank()) return null
        return try { CapturedPayment.fromJson(JSONObject(raw)) } catch (_: Exception) { null }
    }

    fun setLastSyncMessage(context: Context, message: String) {
        prefs(context).edit().putString(KEY_LAST_SYNC, message).apply()
    }

    fun lastSyncMessage(context: Context): String =
        prefs(context).getString(KEY_LAST_SYNC, "No sync has run yet.") ?: "No sync has run yet."
}
