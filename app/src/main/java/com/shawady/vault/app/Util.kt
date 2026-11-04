package com.shawady.vault.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.shawady.vault.core.Entry
import java.security.SecureRandom

object PasswordGenerator {
    private val rng = SecureRandom()
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#\$%^&*-_=+?"

    fun generate(length: Int, symbols: Boolean): String {
        val sets = mutableListOf(LOWER, UPPER, DIGITS)
        if (symbols) sets.add(SYMBOLS)
        val all = sets.joinToString("")
        val n = maxOf(length, sets.size)
        val chars = CharArray(n)
        for (i in sets.indices) chars[i] = sets[i][rng.nextInt(sets[i].length)]
        for (i in sets.size until n) chars[i] = all[rng.nextInt(all.length)]
        for (i in n - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return String(chars)
    }
}

/** Generator engine for the Generator tab (password / passphrase / username). Uses SecureRandom only. */
object Gen {
    private val rng = SecureRandom()
    private var words: List<String>? = null

    fun password(len: Int, upper: Boolean, lower: Boolean, digits: Boolean, symbols: Boolean, noAmbiguous: Boolean): String {
        fun cl(s: String) = if (noAmbiguous) s.filter { it !in "lIO01o|" } else s
        val sets = mutableListOf<String>()
        if (upper) sets.add(cl("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
        if (lower) sets.add(cl("abcdefghijklmnopqrstuvwxyz"))
        if (digits) sets.add(cl("0123456789"))
        if (symbols) sets.add("!@#\$%^&*")
        if (sets.isEmpty()) sets.add(cl("abcdefghijklmnopqrstuvwxyz"))
        val all = sets.joinToString("")
        val n = maxOf(len, sets.size)
        val c = CharArray(n)
        for (i in sets.indices) c[i] = sets[i][rng.nextInt(sets[i].length)]
        for (i in sets.size until n) c[i] = all[rng.nextInt(all.length)]
        for (i in n - 1 downTo 1) { val j = rng.nextInt(i + 1); val t = c[i]; c[i] = c[j]; c[j] = t }
        return String(c)
    }

    private fun wordList(ctx: Context): List<String> =
        words ?: ctx.resources.openRawResource(R.raw.wordlist).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }.also { words = it }

    fun passphrase(ctx: Context, count: Int, sep: String, capitalize: Boolean, number: Boolean): String {
        val w = wordList(ctx)
        val parts = MutableList(count) { w[rng.nextInt(w.size)].let { x -> if (capitalize) x.replaceFirstChar { c -> c.uppercase() } else x } }
        if (number) { val i = rng.nextInt(parts.size); parts[i] = parts[i] + rng.nextInt(10) }
        return parts.joinToString(sep)
    }

    private fun rand(n: Int, chars: String = "abcdefghijklmnopqrstuvwxyz0123456789") = String(CharArray(n) { chars[rng.nextInt(chars.length)] })

    /** kind: 0 plus-addressed email, 1 catch-all, 2 random word */
    fun username(ctx: Context, kind: Int, email: String, domain: String): String = when (kind) {
        0 -> {
            val at = email.indexOf('@')
            if (at <= 0) "" else email.substring(0, at) + "+" + rand(8) + email.substring(at)
        }
        1 -> if (domain.isBlank()) "" else rand(10) + "@" + domain.trim().removePrefix("@")
        else -> wordList(ctx).let { w -> w[rng.nextInt(w.size)].replaceFirstChar { it.uppercase() } + w[rng.nextInt(w.size)].replaceFirstChar { it.uppercase() } + rng.nextInt(100) }
    }
}

/** Copies to the clipboard flagged as sensitive and clears it after the configured time (default 20 s). */
fun copySensitive(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    cm.setPrimaryClip(clip)
    val sec = AppPrefs(ctx).clipClearSec
    if (sec > 0) {
        Handler(Looper.getMainLooper()).postDelayed({
            try { cm.clearPrimaryClip() } catch (e: Exception) { }
        }, sec * 1000L)
    }
}

private val secondLevel = setOf("co", "com", "org", "net", "gov", "edu", "ac")

/** "accounts.google.com" -> "google.com", "mail.bbc.co.uk" -> "bbc.co.uk" (approximation, no public-suffix list). */
fun baseDomain(host: String): String {
    val parts = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
    if (parts.size <= 2) return parts.joinToString(".")
    val n = if (parts[parts.size - 2] in secondLevel && parts.last().length == 2) 3 else 2
    return parts.takeLast(n).joinToString(".")
}

/** "google.com" -> "Google". */
fun siteName(base: String): String =
    base.substringBefore('.').replaceFirstChar { it.uppercase() }

/**
 * Autofill matching. Web: the page host must equal the entry host or be a subdomain of it.
 * Apps: entry url must be "androidapp://<package>". Plain http pages are never matched.
 */
object Matcher {
    fun matches(e: Entry, domain: String?, pkg: String?): Boolean {
        if (e.deleted || e.trashedAt > 0L || e.type != "login") return false
        val u = e.url.trim().lowercase()
        if (u.isEmpty()) return false
        if (u.startsWith("androidapp://")) {
            return pkg != null && u.removePrefix("androidapp://") == pkg.lowercase()
        }
        if (domain == null) return false
        val host = hostOf(u) ?: return false
        val d = domain.lowercase().removePrefix("www.")
        return d == host || d.endsWith(".$host")
    }

    private fun hostOf(u: String): String? = try {
        java.net.URI(if ("://" in u) u else "https://$u").host?.removePrefix("www.")
    } catch (e: Exception) {
        null
    }
}
