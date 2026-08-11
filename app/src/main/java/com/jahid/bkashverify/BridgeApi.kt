package com.jahid.bkashverify

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class BridgeApiException(message: String, val statusCode: Int = 0) : Exception(message)

object BridgeApi {
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 20000

    private fun requireSecureOrLocal(apiBase: String) {
        val parsed = URL(apiBase)
        if (parsed.protocol.equals("https", true)) return
        val host = parsed.host.lowercase()
        val privateHost = host == "localhost" || host == "127.0.0.1" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\.""").containsMatchIn(host)
        if (!privateHost) {
            throw BridgeApiException("Use an HTTPS Website API URL. HTTP is allowed only for local Wi-Fi testing.")
        }
    }

    private fun request(
        method: String,
        url: String,
        body: JSONObject? = null,
        token: String = ""
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "BDeshi-bKash-Bridge/${BuildConfig.VERSION_NAME}")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            doInput = true
            if (body != null) doOutput = true
        }

        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(body.toString())
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val json = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (_: Exception) { JSONObject() }

            if (status !in 200..299) {
                throw BridgeApiException(json.optString("message").ifBlank { "Server returned HTTP $status" }, status)
            }

            return json
        } finally {
            connection.disconnect()
        }
    }

    fun pair(context: Context, apiBaseInput: String, pairingCode: String, deviceName: String): JSONObject {
        val apiBase = BridgeStorage.normalizeApiBase(apiBaseInput)
        if (apiBase.isBlank()) throw BridgeApiException("Website API URL is required")
        requireSecureOrLocal(apiBase)
        val body = JSONObject().apply {
            put("pairingCode", pairingCode.trim())
            put("deviceId", BridgeStorage.deviceId(context))
            put("name", deviceName.trim().ifBlank { Build.MODEL })
            put("model", Build.MANUFACTURER + " " + Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("appVersion", BuildConfig.VERSION_NAME)
        }
        return request("POST", "$apiBase/bkash-verification/device/pair", body)
    }

    fun heartbeat(context: Context): JSONObject {
        val api = BridgeStorage.apiBase(context)
        val token = BridgeStorage.token(context)
        if (api.isBlank() || token.isBlank()) throw BridgeApiException("Phone is not connected")
        return request("GET", "$api/bkash-verification/device/heartbeat", token = token)
    }

    fun disconnect(context: Context): JSONObject {
        val api = BridgeStorage.apiBase(context)
        val token = BridgeStorage.token(context)
        if (api.isBlank() || token.isBlank()) throw BridgeApiException("Phone is not connected")
        return request("POST", "$api/bkash-verification/device/disconnect", JSONObject(), token)
    }

    fun sendPayment(context: Context, payment: CapturedPayment): JSONObject {
        val api = BridgeStorage.apiBase(context)
        val token = BridgeStorage.token(context)
        if (api.isBlank() || token.isBlank()) throw BridgeApiException("Phone is not connected")
        val body = JSONObject().apply {
            put("transactionId", payment.transactionId)
            put("amount", payment.amount)
            put("reference", payment.reference)
            put("sender", payment.sender)
            put("occurredAt", java.time.Instant.ofEpochMilli(payment.occurredAt).toString())
            put("rawText", payment.rawText)
            put("sourcePackage", payment.sourcePackage)
        }
        return request("POST", "$api/bkash-verification/device/transactions", body, token)
    }
}
