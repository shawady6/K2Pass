package com.shawady.vault.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One credential. [updatedAt] is epoch millis and drives sync merge.
 * Deleting never removes the entry: it sets [deleted] = true (tombstone) so that
 * the deletion propagates to the other device on the next manual sync.
 */
data class Entry(
    val id: String,                    // UUID, never changes
    val title: String,
    val username: String,
    val password: String,
    val url: String = "",              // used by autofill matching (domain / android package)
    val notes: String = "",
    val totpSecret: String? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val history: List<Entry> = emptyList(), // older versions (their own history is always empty)
    val category: String = "other",    // category id (see app Categories); unknown ids fall back to "other"
) {
    /** Content equality ignoring history (used by merge to detect real conflicts). */
    fun sameContent(o: Entry) =
        title == o.title && username == o.username && password == o.password &&
            url == o.url && notes == o.notes && totpSecret == o.totpSecret &&
            deleted == o.deleted && updatedAt == o.updatedAt && category == o.category

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("user", username); put("pass", password)
        put("url", url); put("notes", notes)
        if (totpSecret != null) put("totp", totpSecret)
        put("ts", updatedAt); put("del", deleted); put("cat", category)
        if (history.isNotEmpty()) put("hist", JSONArray(history.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JSONObject): Entry = Entry(
            id = o.getString("id"),
            title = o.getString("title"),
            username = o.getString("user"),
            password = o.getString("pass"),
            url = o.optString("url", ""),
            notes = o.optString("notes", ""),
            totpSecret = if (o.has("totp")) o.getString("totp") else null,
            updatedAt = o.getLong("ts"),
            deleted = o.optBoolean("del", false),
            category = o.optString("cat", "other"),
            history = o.optJSONArray("hist")?.let { a ->
                (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            } ?: emptyList(),
        )
    }
}

data class VaultData(val entries: List<Entry>) {
    fun toBytes(): ByteArray =
        JSONObject().put("entries", JSONArray(entries.map { it.toJson() })).toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(b: ByteArray): VaultData {
            val a = JSONObject(String(b, Charsets.UTF_8)).getJSONArray("entries")
            return VaultData((0 until a.length()).map { Entry.fromJson(a.getJSONObject(it)) })
        }
    }
}
