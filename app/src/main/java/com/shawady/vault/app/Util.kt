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

/** Copies to the clipboard flagged as sensitive and clears it after 20 seconds. */
fun copySensitive(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    cm.setPrimaryClip(clip)
    Handler(Looper.getMainLooper()).postDelayed({
        try { cm.clearPrimaryClip() } catch (e: Exception) { }
    }, 20_000)
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
