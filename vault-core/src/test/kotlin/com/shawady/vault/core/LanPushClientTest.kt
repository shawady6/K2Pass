package com.shawady.vault.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket

/** A tiny raw-socket stand-in for the Windows receiver that follows the same protocol (the real one is tested in windows/k2-lan). */
class LanPushClientTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val nonce = "0123456789abcdef0123456789abcdef"
    @Volatile private var got: String? = null
    private val ss = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
    private val port = ss.localPort

    init {
        Thread {
            try {
                while (true) {
                    val c = ss.accept()
                    c.soTimeout = 3000
                    val inp = c.getInputStream().buffered()
                    fun line(): String { val sb = StringBuilder(); while (true) { val b = inp.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }; return sb.toString() }
                    val req = line(); var len = 0
                    while (true) { val h = line(); if (h.isEmpty()) break; if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toInt() }
                    val code: Int; val body: String
                    if (req.startsWith("GET /n")) { code = 200; body = nonce }
                    else {
                        val data = ByteArray(len).also { var o = 0; while (o < len) { val n = inp.read(it, o, len - o); if (n < 0) break; o += n } }
                        val ok = req.contains("a=$nonce." + LanSyncServer.macHex(secret, "p|$nonce"))
                        if (ok) got = data.toString(Charsets.UTF_8)
                        code = if (ok) 200 else 403; body = if (ok) "ok" else "denied"
                    }
                    c.getOutputStream().apply { write("HTTP/1.1 $code X\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()); flush() }
                    c.close()
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true }.start()
    }

    @After fun stop() = ss.close()

    @Test fun pushesTheVaultWithAValidMac() {
        assertEquals(LanPushClient.Result.OK, LanPushClient.push("127.0.0.1", port, secret, """{"v":1,"wk":"x","data":"y"}"""))
        assertEquals("""{"v":1,"wk":"x","data":"y"}""", got)
    }

    @Test fun wrongSecretIsDenied() {
        assertEquals(LanPushClient.Result.DENIED, LanPushClient.push("127.0.0.1", port, ByteArray(32) { 7 }, "{}"))
        assertEquals(null, got)
    }

    @Test fun nothingListeningIsUnreachable() {
        ss.close()
        assertEquals(LanPushClient.Result.UNREACHABLE, LanPushClient.push("127.0.0.1", port, secret, "{}", 800))
    }
}
