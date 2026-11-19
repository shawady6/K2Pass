package com.shawady.vault.core

/** Wi-Fi QR payload: `WIFI:T:WPA;S:<ssid>;P:<password>;H:true;;` (backslash escapes \\ \; \, \: \"). */
object WifiQr {
    data class Net(val ssid: String, val password: String, val security: String, val hidden: Boolean)

    fun parse(text: String): Net? {
        val t = text.trim()
        if (!t.startsWith("WIFI:", ignoreCase = true)) return null
        val f = HashMap<String, String>()
        val cur = StringBuilder()
        var key: String? = null
        var i = 5
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\\' && i + 1 < t.length -> { cur.append(t[i + 1]); i++ }
                key == null && c == ':' -> { key = cur.toString().uppercase(); cur.clear() }
                key != null && c == ';' -> { f[key] = cur.toString(); key = null; cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        if (key != null && cur.isNotEmpty()) f[key] = cur.toString()
        val ssid = f["S"]?.takeIf { it.isNotEmpty() } ?: return null
        val sec = when (f["T"]?.uppercase()) {
            "SAE", "WPA3" -> "WPA3"
            "NOPASS", "", null -> if ((f["P"] ?: "").isEmpty()) "Open" else "WPA2"
            "WEP" -> "WEP"
            else -> "WPA2"
        }
        return Net(ssid, f["P"] ?: "", sec, f["H"]?.equals("true", true) == true)
    }
}
