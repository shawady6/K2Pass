package com.shawady.vault.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.shawady.vault.core.LanSyncServer
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Private IPv4 addresses of this phone (Wi-Fi first). Empty when not on a local network. */
private fun localIps(): List<String> =
    try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress!! }
    } catch (_: Exception) { emptyList() }

/**
 * Sync with the browser extension. The extension shows a QR (`k2pair://<secret>`); the phone scans it, remembers the secret and
 * serves its (still encrypted) vault on the local network to whoever proves knowledge of a remembered secret. The extension
 * finds the phone on the LAN by itself. The server runs only while this card is on screen / started.
 */
@Composable
fun LanSyncSection(rawVault: () -> String?, prefs: AppPrefs) {
    val ctx = LocalContext.current
    var server by remember { mutableStateOf<LanSyncServer?>(null) }
    var addr by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun keepOn(on: Boolean) {
        val w = (ctx as? Activity)?.window ?: return
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun stop() { server?.stop(); server = null; addr = ""; keepOn(false) }
    fun start(): Boolean {
        if (server != null) return true
        val ips = localIps()
        if (ips.isEmpty()) { status = "مفيش شبكة محلية. وصّل الموبايل بنفس الواي فاي اللي عليه الكمبيوتر (أو شغّل هوت سبوت)."; return false }
        val s = LanSyncServer({ prefs.lanSecrets.map { Base64.decode(it, Base64.URL_SAFE) } }, rawVault) { ev ->
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            main.post { status = if (ev == "served") "الكمبيوتر سحب الخزنة ($t)" else "محاولة مرفوضة ($t)" }
        }
        val port = s.start()
        server = s; addr = "${ips.first()}:$port"; keepOn(true)
        return true
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val c = r.contents ?: return@rememberLauncherForActivityResult
        val m = Regex("^k2pair://([A-Za-z0-9_-]{43})$").find(c.trim())
        if (m == null) { status = "الكود ده مش كود ربط من إضافة K2Pass."; return@rememberLauncherForActivityResult }
        prefs.addLanSecret(m.groupValues[1])
        if (start()) status = "اتربط ✔. سيب الشاشة دي مفتوحة، والإضافة هتلاقي الموبايل لوحدها."
    }
    DisposableEffect(Unit) { onDispose { server?.stop(); keepOn(false) } }

    Section("مزامنة مع إضافة المتصفح") {
        Text("١) في الإضافة على الكمبيوتر: ⋮ ← ربط الموبايل، هيظهر كود QR.  ٢) اضغط الزرار تحت وامسحه بكاميرا الموبايل. الاتنين لازم على نفس الواي فاي. الماستر باسورد ما بيتبعتش، الخزنة بتتسحب مشفّرة.")
        Button(onClick = {
            scan.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("وجّه الكاميرا على الكود اللي في الإضافة").setBeepEnabled(false).setOrientationLocked(false),
            )
        }, modifier = Modifier.fillMaxWidth()) { Text("📷 مسح كود الإضافة") }
        if (prefs.lanSecrets.isNotEmpty() && server == null) {
            OutlinedButton(onClick = { if (start()) status = "المزامنة شغّالة. افتح الإضافة على الكمبيوتر." }, modifier = Modifier.fillMaxWidth()) {
                Text("تشغيل المزامنة (الكمبيوتر مربوط قبل كده)")
            }
        }
        if (server != null) {
            Text("شغّال على $addr", style = MaterialTheme.typography.bodySmall)
            Text("لو الإضافة ما لقتش الموبايل، اكتب العنوان ده فيها.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { stop(); status = "المزامنة اتوقفت." }, modifier = Modifier.fillMaxWidth()) { Text("إيقاف") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (prefs.lanSecrets.isNotEmpty()) {
            OutlinedButton(onClick = { stop(); prefs.clearLanSecrets(); status = "اتلغى الربط مع كل الأجهزة." }) { Text("إلغاء ربط كل الأجهزة") }
        }
    }
}
