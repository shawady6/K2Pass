package com.shawady.vault.app

import com.shawady.vault.core.Entry
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Import from Bitwarden (JSON, unencrypted export) and CSV exports of Chrome / Bitwarden / KeePassXC / LastPass,
 * plus a plain CSV export. Plain files contain secrets in clear text: the user is told to delete them.
 */
object Importer {

    class Result(val entries: List<Entry>)

    fun parse(text: String, now: Long): Result {
        val t = text.removePrefix("﻿").trim()
        if (t.isEmpty()) throw IllegalArgumentException("الملف فاضي")
        return if (t.startsWith("{")) parseBitwarden(t, now) else parseCsvFile(t, now)
    }

    private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k, "")

    private fun parseBitwarden(t: String, now: Long): Result {
        val root = JSONObject(t)
        if (root.optBoolean("encrypted", false)) throw IllegalArgumentException("الملف مشفّر. صدّره من Bitwarden بصيغة JSON غير مشفرة")
        val items: JSONArray = root.optJSONArray("items") ?: throw IllegalArgumentException("صيغة JSON غير معروفة")
        val out = ArrayList<Entry>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val name = it.s("name")
            val notes = it.s("notes")
            val fav = it.optBoolean("favorite", false)
            when (it.optInt("type", 1)) {
                1 -> {
                    val l = it.optJSONObject("login") ?: JSONObject()
                    val uri = l.optJSONArray("uris")?.optJSONObject(0)?.s("uri").orEmpty()
                    out.add(
                        make(
                            now, "login", name.ifBlank { titleFor(uri, l.s("username")) }, l.s("username"), l.s("password"),
                            uri, notes, l.s("totp").ifBlank { null }, fav, emptyMap(),
                        ),
                    )
                }
                2 -> out.add(make(now, "note", name, "", "", "", notes, null, fav, emptyMap()))
                3 -> {
                    val c = it.optJSONObject("card") ?: JSONObject()
                    val m = c.s("expMonth"); val y = c.s("expYear")
                    val exp = if (m.isNotEmpty() && y.isNotEmpty()) m.padStart(2, '0') + "/" + y.takeLast(2) else ""
                    out.add(
                        make(
                            now, "card", name, "", "", "", notes, null, fav,
                            clean(mapOf("holder" to c.s("cardholderName"), "number" to c.s("number"), "exp" to exp, "cvv" to c.s("code"))),
                        ),
                    )
                }
                4 -> {
                    val d = it.optJSONObject("identity") ?: JSONObject()
                    val full = listOf(d.s("title"), d.s("firstName"), d.s("middleName"), d.s("lastName")).filter { x -> x.isNotBlank() }.joinToString(" ")
                    val addr = listOf(d.s("address1"), d.s("address2"), d.s("city"), d.s("state"), d.s("country")).filter { x -> x.isNotBlank() }.joinToString("، ")
                    val idn = listOf(d.s("ssn"), d.s("passportNumber"), d.s("licenseNumber")).firstOrNull { x -> x.isNotBlank() } ?: ""
                    out.add(
                        make(
                            now, "identity", name, "", "", "", notes, null, fav,
                            clean(mapOf("name" to full, "email" to d.s("email"), "phone" to d.s("phone"), "address" to addr, "idnum" to idn)),
                        ),
                    )
                }
            }
        }
        return Result(out)
    }

    private fun parseCsvFile(t: String, now: Long): Result {
        val rows = parseCsv(t)
        if (rows.size < 2) throw IllegalArgumentException("ملف CSV فاضي")
        val header = rows[0].map { it.trim().lowercase() }
        fun col(vararg names: String): Int = names.map { header.indexOf(it) }.firstOrNull { it >= 0 } ?: -1
        val cName = col("name", "title", "account name")
        val cUrl = col("url", "login_uri", "web site", "website", "uri")
        val cUser = col("username", "login_username", "user name", "login name")
        val cPass = col("password", "login_password")
        val cNotes = col("notes", "note", "extra")
        val cTotp = col("totp", "login_totp", "otpauth")
        val cFav = col("favorite", "fav")
        val cType = col("type")
        if (cPass < 0 && cUser < 0) throw IllegalArgumentException("مش قادر أتعرف على أعمدة الملف")
        fun cell(r: List<String>, i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
        val out = ArrayList<Entry>()
        for (r in rows.drop(1)) {
            val url = cell(r, cUrl)
            val user = cell(r, cUser)
            val pass = if (cPass >= 0 && cPass < r.size) r[cPass] else ""
            val notes = if (cNotes >= 0 && cNotes < r.size) r[cNotes] else ""
            val name = cell(r, cName)
            if (name.isEmpty() && url.isEmpty() && user.isEmpty() && pass.isEmpty()) continue
            val fav = cell(r, cFav).lowercase().let { it == "1" || it == "true" }
            val type = if (cell(r, cType).lowercase() == "note") "note" else "login"
            out.add(
                make(
                    now, type, name.ifBlank { titleFor(url, user) }, if (type == "note") "" else user,
                    if (type == "note") "" else pass, if (type == "note") "" else url, notes,
                    cell(r, cTotp).ifBlank { null }, fav, emptyMap(),
                ),
            )
        }
        return Result(out)
    }

    private fun make(
        now: Long, type: String, title: String, user: String, pass: String, url: String, notes: String,
        totp: String?, fav: Boolean, extra: Map<String, String>,
    ) = Entry(
        id = UUID.randomUUID().toString(), title = title.ifBlank { "بدون اسم" }, username = user, password = pass,
        url = url, notes = notes, totpSecret = totp, updatedAt = now, type = type, favorite = fav, extra = extra,
    )

    private fun clean(m: Map<String, String>): Map<String, String> = m.filterValues { it.isNotBlank() }

    private fun titleFor(url: String, user: String): String {
        val host = try {
            java.net.URI(if ("://" in url) url else "https://$url").host
        } catch (e: Exception) {
            null
        }
        return if (!host.isNullOrBlank()) siteName(baseDomain(host)) else user
    }

    // ---- CSV (RFC 4180) ----

    private fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        f.append('"'); i++
                    } else {
                        quoted = false
                    }
                } else {
                    f.append(c)
                }
            } else if (c == '"') {
                quoted = true
            } else if (c == ',') {
                row.add(f.toString()); f.setLength(0)
            } else if (c == '\n') {
                row.add(f.toString()); f.setLength(0)
                rows.add(row); row = ArrayList()
            } else if (c != '\r') {
                f.append(c)
            }
            i++
        }
        if (f.isNotEmpty() || row.isNotEmpty()) {
            row.add(f.toString()); rows.add(row)
        }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    fun toCsv(entries: List<Entry>): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val sb = StringBuilder("name,url,username,password,note,totp,type,favorite\n")
        for (e in entries) {
            val notes = if (e.extra.isEmpty()) e.notes else (e.extra.entries.joinToString("\n") { it.key + ": " + it.value } + "\n" + e.notes).trim()
            sb.append(listOf(e.title, e.url, e.username, e.password, notes, e.totpSecret ?: "", e.type, e.favorite.toString()).joinToString(",") { q(it) })
            sb.append('\n')
        }
        return sb.toString()
    }
}
