package com.shawady.vault.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultMergeTest {
    private fun e(id: String, pw: String, t: Long, title: String = "t-$id", deleted: Boolean = false) =
        Entry(id = id, title = title, username = "u", password = pw, updatedAt = t, deleted = deleted)

    private fun v(vararg x: Entry) = VaultData(x.toList())

    @Test fun commutative() {
        val a = v(e("1", "a", 10), e("2", "b", 20), e("3", "c", 5))
        val b = v(e("1", "a2", 15), e("2", "b", 20), e("4", "d", 1))
        assertEquals(VaultMerge.merge(a, b), VaultMerge.merge(b, a))
    }

    @Test fun idempotent() {
        val a = v(e("1", "a", 10), e("2", "b", 20))
        assertEquals(a.entries.sortedBy { it.id }, VaultMerge.merge(a, a).entries)
        val b = v(e("1", "a2", 15))
        val m = VaultMerge.merge(a, b)
        assertEquals(m, VaultMerge.merge(m, m))
        assertEquals(m, VaultMerge.merge(m, a))
    }

    @Test fun newestWinsAndLoserGoesToHistory() {
        val m = VaultMerge.merge(v(e("1", "old", 10)), v(e("1", "new", 20))).entries.single()
        assertEquals("new", m.password)
        assertEquals(listOf("old"), m.history.map { it.password })
    }

    @Test fun deletionIsFinalAndDropsHistory() {
        val a = v(e("1", "secret", 10))
        val b = v(e("1", "secret", 20, deleted = true))
        val m = VaultMerge.merge(a, b).entries.single()
        assertTrue(m.deleted)
        assertTrue(m.history.isEmpty())
        assertEquals(VaultMerge.merge(a, b), VaultMerge.merge(b, a))
    }

    @Test fun tieBreakIsDeterministic() {
        val a = v(e("1", "aaa", 10))
        val b = v(e("1", "bbb", 10))
        assertEquals(VaultMerge.merge(a, b), VaultMerge.merge(b, a))
    }

    @Test fun historyIsCapped() {
        val hist = (1..30).map { e("1", "p$it", it.toLong()) }
        val a = v(e("1", "cur", 100).copy(history = hist))
        assertEquals(20, VaultMerge.merge(a, a).entries.single().history.size)
    }

    /** The JS port (extension/lib/merge.js, used by the Windows app) must merge exactly like this class. */
    @Test fun matchesTheSharedJsVectors() {
        val f = java.io.File("../extension/test/merge_vectors.json").takeIf { it.exists() } ?: java.io.File("extension/test/merge_vectors.json")
        val root = org.json.JSONObject(f.readText())
        fun data(a: org.json.JSONArray) = VaultData((0 until a.length()).map { Entry.fromJson(a.getJSONObject(it)) })
        for (name in root.keys()) {
            val c = root.getJSONObject(name)
            val merged = VaultMerge.merge(data(c.getJSONArray("a")), data(c.getJSONArray("b")))
            val expected = c.getJSONArray("merged")
            assertEquals(name, expected.length(), merged.entries.size)
            for (i in 0 until expected.length()) {
                val got = merged.entries[i].toJson()
                val want = expected.getJSONObject(i)
                assertTrue("$name #$i\n got  $got\n want $want", got.similar(want))
            }
        }
    }
}
