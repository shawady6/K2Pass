package com.shawady.vault.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Pushes the (already encrypted) vault file to the Windows app's receiver (windows/k2-lan):
 *   GET /n -> nonce ; POST /push?a=<nonce>.<hex HMAC-SHA256(secret,"p|"+nonce)> with the vault file as body.
 */
object LanPushClient {
    enum class Result { OK, DENIED, UNREACHABLE, REJECTED }

    fun push(host: String, port: Int, secret: ByteArray, vaultJson: String, timeoutMs: Int = 5000): Result {
        return try {
            val nonce = open("http://$host:$port/n", "GET", timeoutMs).let { c ->
                if (c.responseCode != 200) return Result.UNREACHABLE
                c.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            }
            val mac = LanSyncServer.macHex(secret, "p|$nonce")
            val c = open("http://$host:$port/push?a=$nonce.$mac", "POST", timeoutMs)
            c.doOutput = true
            c.outputStream.use { it.write(vaultJson.toByteArray(Charsets.UTF_8)) }
            when (c.responseCode) {
                200 -> Result.OK
                403 -> Result.DENIED
                else -> Result.REJECTED
            }
        } catch (e: Exception) {
            Result.UNREACHABLE
        }
    }

    /** The PC's current (merged) vault file: GET /n, then GET /v?a=<nonce>.<hex HMAC-SHA256(secret,"v|"+nonce)>. Null on any failure. */
    fun pull(host: String, port: Int, secret: ByteArray, timeoutMs: Int = 5000): String? = try {
        val nonce = open("http://$host:$port/n", "GET", timeoutMs).let { c ->
            if (c.responseCode != 200) return null
            c.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        }
        val c = open("http://$host:$port/v?a=$nonce." + LanSyncServer.macHex(secret, "v|$nonce"), "GET", timeoutMs)
        if (c.responseCode == 200) c.inputStream.readBytes().toString(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    private fun open(url: String, method: String, timeoutMs: Int) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = timeoutMs; readTimeout = timeoutMs
        }
}
