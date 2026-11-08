package com.shawady.vault.core

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tiny one-purpose HTTP server used for "pull my vault from the phone" sync with the browser extension, on the local network.
 * It only ever serves the vault file as stored on disk — still encrypted by the master password — so an eavesdropper on the Wi-Fi
 * learns nothing usable; the pairing secret (shown as a QR on the phone) only decides WHO may download it.
 *
 * Protocol (all GET):
 *   /n            -> 32-hex single-use nonce (valid 30 s)
 *   /v?a=<nonce>.<hex(HMAC-SHA256(secret, "v|"+nonce))>  -> the vault file (200) or 403
 * Everything else -> 404. CORS is open (the response is useless without the secret) so extension pages can read it.
 */
class LanSyncServer(
    private val secret: ByteArray,
    private val vaultProvider: () -> String?,
    private val onEvent: (String) -> Unit = {},
) {
    private val rng = SecureRandom()
    private val nonces = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var server: ServerSocket? = null

    /** Starts listening; returns the bound port (pass 0 for any free port). */
    @Synchronized
    fun start(port: Int = 8765): Int {
        stop()
        val s = try { ServerSocket(port, 20, null as InetAddress?) } catch (e: Exception) { ServerSocket(0, 20, null as InetAddress?) }
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                pool.execute { handle(c) }
            }
        }, "k2-lan-sync").apply { isDaemon = true }.start()
        return s.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        nonces.clear()
    }

    private fun handle(c: Socket) {
        c.use {
            try {
                c.soTimeout = 5000
                val ins = c.getInputStream()
                val head = StringBuilder()
                while (head.length < 8192) {
                    val b = ins.read(); if (b < 0) break
                    head.append(b.toChar())
                    if (head.endsWith("\r\n\r\n")) break
                }
                val first = head.lineSequence().firstOrNull() ?: return
                val parts = first.split(' ')
                if (parts.size < 2) return respond(c, 400, "bad request")
                val method = parts[0]; val target = parts[1]
                if (method == "OPTIONS") return respond(c, 204, "")
                if (method != "GET") return respond(c, 405, "method not allowed")
                val path = target.substringBefore('?'); val query = target.substringAfter('?', "")
                when (path) {
                    "/n" -> respond(c, 200, newNonce(), "text/plain")
                    "/v" -> {
                        val a = query.split('&').firstOrNull { it.startsWith("a=") }?.removePrefix("a=") ?: ""
                        if (!authorized(a)) { Thread.sleep(400); onEvent("denied"); return respond(c, 403, "forbidden") }
                        val v = vaultProvider() ?: return respond(c, 404, "no vault")
                        onEvent("served")
                        respond(c, 200, v, "application/json")
                    }
                    else -> respond(c, 404, "not found")
                }
            } catch (_: Exception) { /* client went away */ }
        }
    }

    private fun newNonce(): String {
        val now = System.currentTimeMillis()
        nonces.entries.removeIf { it.value < now }
        if (nonces.size > 50) nonces.clear()
        val n = hex(ByteArray(16).also { rng.nextBytes(it) })
        nonces[n] = now + 30_000
        return n
    }

    private fun authorized(a: String): Boolean {
        val nonce = a.substringBefore('.', ""); val mac = a.substringAfter('.', "")
        if (nonce.isEmpty() || mac.isEmpty()) return false
        val exp = nonces.remove(nonce) ?: return false          // single use
        if (exp < System.currentTimeMillis()) return false
        return MessageDigest.isEqual(mac.lowercase().toByteArray(), macHex(secret, "v|$nonce").toByteArray())
    }

    private fun respond(c: Socket, code: Int, body: String, type: String = "text/plain") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val h = "HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Private-Network: true\r\n" +
            "Access-Control-Allow-Methods: GET\r\nConnection: close\r\n\r\n"
        c.getOutputStream().apply { write(h.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }

    companion object {
        fun macHex(secret: ByteArray, msg: String): String {
            val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(secret, "HmacSHA256"))
            return hex(m.doFinal(msg.toByteArray(Charsets.UTF_8)))
        }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
