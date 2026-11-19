package com.shawady.vault.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/** "Connect to this network" for Wi-Fi items. Android 11+: system dialog to save the network; Android 10: network suggestion. */
object WifiConnect {
    private fun suggestion(ssid: String, pass: String, security: String): WifiNetworkSuggestion {
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid).setIsAppInteractionRequired(false)
        val sec = security.uppercase()
        if (pass.isNotEmpty() && !sec.contains("OPEN")) {
            if (sec.contains("3") || sec.contains("SAE")) b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass)
        }
        return b.build()
    }

    fun connect(ctx: Context, ssid: String, pass: String, security: String) {
        if (ssid.isBlank()) { toast(ctx, "اكتب اسم الشبكة (SSID) الأول"); return }
        try {
            val s = suggestion(ssid, pass, security)
            if (Build.VERSION.SDK_INT >= 30) {
                val i = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                    .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(s))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            }
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.removeNetworkSuggestions(listOf(s))
            when (wm.addNetworkSuggestions(listOf(s))) {
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
                    toast(ctx, "اتضافت الشبكة. أندرويد هيتصل بيها تلقائي لما تبقى في المدى (وافق على إشعار الأذونات لو ظهر).")
                else -> fallback(ctx, pass)
            }
        } catch (e: Exception) {
            fallback(ctx, pass)
        }
    }

    /** Copies the password and opens the Wi-Fi settings so the user can pick the network by hand. */
    private fun fallback(ctx: Context, pass: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("wifi", pass))
        toast(ctx, "اتنسخ الباسورد. اختار الشبكة من إعدادات الواي فاي والصقه.")
        ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
}
