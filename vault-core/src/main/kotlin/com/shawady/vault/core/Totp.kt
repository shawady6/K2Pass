package com.shawady.vault.core

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 TOTP. Accepts a bare base32 secret or an otpauth://totp/... URI (digits / period / algorithm honoured). */
object Totp {

    class Result(val code: String, val secondsLeft: Int, val period: Int)

    private class Params(val secret: ByteArray, val digits: Int, val period: Int, val algo: String)

    /** Returns null when [input] is not a valid secret. */
    fun generate(input: String, nowMs: Long = System.currentTimeMillis()): Result? {
        val p = parse(input) ?: return null
        val counter = nowMs / 1000L / p.period
        val msg = ByteArray(8) { i -> ((counter shr (8 * (7 - i))) and 0xffL).toByte() }
        val mac = Mac.getInstance(p.algo)
        mac.init(SecretKeySpec(p.secret, p.algo))
        val h = mac.doFinal(msg)
        val off = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[off].toInt() and 0x7f) shl 24) or
            ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or
            (h[off + 3].toInt() and 0xff)
        var mod = 1
        repeat(p.digits) { mod *= 10 }
        val code = (bin % mod).toString().padStart(p.digits, '0')
        val left = p.period - ((nowMs / 1000L) % p.period).toInt()
        return Result(code, left, p.period)
    }

    private fun parse(input: String): Params? {
        val s = input.trim()
        if (s.isEmpty()) return null
        var secretStr = s
        var digits = 6
        var period = 30
        var algo = "HmacSHA1"
        if (s.startsWith("otpauth://", ignoreCase = true)) {
            val q = s.substringAfter('?', "")
            val m = HashMap<String, String>()
            for (kv in q.split('&')) {
                val i = kv.indexOf('=')
                if (i > 0) m[kv.substring(0, i).lowercase()] = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }
            secretStr = m["secret"] ?: return null
            m["digits"]?.toIntOrNull()?.let { digits = it.coerceIn(6, 8) }
            m["period"]?.toIntOrNull()?.let { if (it > 0) period = it }
            val a = m["algorithm"]?.uppercase()
            if (a == "SHA256") algo = "HmacSHA256"
            if (a == "SHA512") algo = "HmacSHA512"
        }
        val key = base32(secretStr) ?: return null
        return Params(key, digits, period, algo)
    }

    private fun base32(src: String): ByteArray? {
        val clean = src.uppercase().filter { it != ' ' && it != '-' && it != '=' }
        if (clean.isEmpty()) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = alphabet.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}
