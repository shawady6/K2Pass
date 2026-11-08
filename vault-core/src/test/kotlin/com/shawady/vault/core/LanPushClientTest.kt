package com.shawady.vault.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

/** A tiny stand-in for the Windows receiver that follows the same protocol (the real one is tested in windows/k2-lan). */
class LanPushClientTest {
    private val secret = ByteArray(32) { it.toByte() }
    private var got: String? = null
    private val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/n") { ex -> val b = "0123456789abcdef0123456789abcdef".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        createContext("/push") { ex ->
            val a = ex.requestURI.query.removePrefix("a=")
            val ok = a == "0123456789abcdef0123456789abcdef." + LanSyncServer.macHex(secret, "p|0123456789abcdef0123456789abcdef")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            if (ok) got = body
            ex.sendResponseHeaders(if (ok) 200 else 403, -1); ex.close()
        }
        start()
    }
    private val port = srv.address.port

    @After fun stop() = srv.stop(0)

    @Test fun pushesTheVaultWithAValidMac() {
        assertEquals(LanPushClient.Result.OK, LanPushClient.push("127.0.0.1", port, secret, """{"v":1,"wk":"x","data":"y"}"""))
        assertEquals("""{"v":1,"wk":"x","data":"y"}""", got)
    }

    @Test fun wrongSecretIsDenied() {
        assertEquals(LanPushClient.Result.DENIED, LanPushClient.push("127.0.0.1", port, ByteArray(32) { 7 }, "{}"))
        assertEquals(null, got)
    }

    @Test fun nothingListeningIsUnreachable() {
        srv.stop(0)
        assertEquals(LanPushClient.Result.UNREACHABLE, LanPushClient.push("127.0.0.1", port, secret, "{}", 800))
    }
}
