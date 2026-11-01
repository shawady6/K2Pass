package com.shawady.vault.app

import android.content.Context

/** User-configurable settings (not secret). */
class AppPrefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("k2_settings", Context.MODE_PRIVATE)

    var autoLockMs: Long
        get() = p.getLong("auto_lock_ms", 60_000L)
        set(v) { p.edit().putLong("auto_lock_ms", v).apply() }

    /** 0 = never clear. */
    var clipClearSec: Int
        get() = p.getInt("clip_clear_sec", 20)
        set(v) { p.edit().putInt("clip_clear_sec", v).apply() }

    var blockScreens: Boolean
        get() = p.getBoolean("block_screens", true)
        set(v) { p.edit().putBoolean("block_screens", v).apply() }

    var copyTotp: Boolean
        get() = p.getBoolean("copy_totp", true)
        set(v) { p.edit().putBoolean("copy_totp", v).apply() }

    /** One domain (or androidapp://package) per line: autofill and save prompts are disabled there. */
    var blockedDomains: String
        get() = p.getString("blocked", "") ?: ""
        set(v) { p.edit().putString("blocked", v).apply() }

    fun isBlocked(domain: String?, pkg: String?): Boolean {
        val list = blockedDomains.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return false
        val base = domain?.takeIf { it.isNotBlank() }?.let { baseDomain(it) }
        return list.any { b ->
            val isApp = b.startsWith("androidapp://")
            if (isApp) pkg != null && pkg.lowercase() == b.removePrefix("androidapp://")
            else base != null && base == baseDomain(b.removePrefix("https://").removePrefix("http://").substringBefore('/'))
        }
    }
}
