package com.shawady.vault.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelJsonTest {
    @Test fun fullEntryRoundTrip() {
        val old = Entry(id = "o", title = "old", username = "u", password = "p0", updatedAt = 1)
        val e = Entry(
            id = "1", title = "Router", username = "admin", password = "pw", url = "https://x.com",
            notes = "n\nمرحبا", totpSecret = "ABC", updatedAt = 42, history = listOf(old), category = "f:xyz",
            type = "router", favorite = true, trashedAt = 7, extra = mapOf("host" to "10.0.0.1", "urls" to "a\nb"),
        )
        val back = VaultData.fromBytes(VaultData(listOf(e)).toBytes()).entries.single()
        assertEquals(e, back)
    }

    @Test fun oldFormatWithoutNewFieldsStillLoads() {
        val json = """{"entries":[{"id":"1","title":"t","user":"u","pass":"p","ts":5}]}"""
        val e = VaultData.fromBytes(json.toByteArray()).entries.single()
        assertEquals("login", e.type)
        assertEquals(emptyMap<String, String>(), e.extra)
    }
}
