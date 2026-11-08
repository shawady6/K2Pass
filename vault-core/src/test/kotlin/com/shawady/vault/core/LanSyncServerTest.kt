package com.shawady.vault.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URL

class LanSyncServerTest {
    private val secret = ByteArray(32) { it.toByte() }
    private var served = 0
    private val srv = LanSyncServer(secret, { """{"v":1}""" }, { if (it == "served") served++ })
    private val port = srv.start(0)

    @After fun stop() = srv.stop()

    private fun get(path: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        return code to body
    }

    private fun nonce() = get("/n").second

    @Test fun hmacVectorMatchesJsClient() {
        // Same vector is asserted in extension/test/extension.test.mjs
        assertEquals(VECTOR, LanSyncServer.macHex(secret, "v|abc123"))
    }

    @Test fun servesVaultWithValidMacAndNonceIsSingleUse() {
        val n = nonce()
        val a = "$n." + LanSyncServer.macHex(secret, "v|$n")
        assertEquals(200 to """{"v":1}""", get("/v?a=$a"))
        assertEquals(403, get("/v?a=$a").first) // replay
        assertEquals(1, served)
    }

    @Test fun rejectsWrongSecretMissingAuthAndUnknownPaths() {
        val n = nonce()
        val bad = "$n." + LanSyncServer.macHex(ByteArray(32), "v|$n")
        assertEquals(403, get("/v?a=$bad").first)
        assertEquals(403, get("/v").first)
        assertEquals(403, get("/v?a=garbage").first)
        assertEquals(404, get("/other").first)
        assertEquals(0, served)
    }

    companion object {
        // HMAC-SHA256(key = bytes 0..31, "v|abc123") — computed independently with Python's hmac module.
        const val VECTOR = "c014485e217b5e52e47739d96c38968362350723cffe5695d34aa737f274d2af"
    }
}
