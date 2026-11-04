package com.shawady.vault.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RFC 6238 Appendix B test vectors (8 digits). */
class TotpTest {
    private fun code(algo: String, secret: String, t: Long): String =
        Totp.generate("otpauth://totp/x?secret=$secret&digits=8&algorithm=$algo", t * 1000)!!.code

    private val sha1 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    private val sha256 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA"
    private val sha512 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA"

    @Test fun sha1Vectors() {
        assertEquals("94287082", code("SHA1", sha1, 59))
        assertEquals("07081804", code("SHA1", sha1, 1111111109))
        assertEquals("14050471", code("SHA1", sha1, 1111111111))
        assertEquals("89005924", code("SHA1", sha1, 1234567890))
        assertEquals("69279037", code("SHA1", sha1, 2000000000))
        assertEquals("65353130", code("SHA1", sha1, 20000000000))
    }

    @Test fun sha256Vectors() {
        assertEquals("46119246", code("SHA256", sha256, 59))
        assertEquals("68084774", code("SHA256", sha256, 1111111109))
        assertEquals("91819424", code("SHA256", sha256, 1234567890))
    }

    @Test fun sha512Vector() {
        assertEquals("90693936", code("SHA512", sha512, 59))
    }

    @Test fun plainBase32DefaultsToSixDigits() {
        val r = Totp.generate(sha1, 59_000)!!
        assertEquals(6, r.code.length)
        assertEquals("287082", r.code) // last 6 digits of the 8-digit vector
        assertEquals(30, r.period)
        assertEquals(1, r.secondsLeft)
    }

    @Test fun invalidSecretIsRejected() {
        assertNull(Totp.generate("not base32 !!!"))
        assertNull(Totp.generate(""))
    }
}
