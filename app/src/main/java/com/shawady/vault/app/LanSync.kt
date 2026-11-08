package com.shawady.vault.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
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

private fun qr(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) AColor.BLACK else AColor.WHITE)
    return bmp
}

/**
 * "Sync with the browser extension": while this card is on, the phone serves its (still encrypted) vault file on the local
 * network to anyone holding the pairing secret in the QR. Stops when you press stop, leave the tools tab, or lock the app.
 */
@Composable
fun LanSyncSection(rawVault: () -> String?, prefs: AppPrefs) {
    val ctx = LocalContext.current
    var server by remember { mutableStateOf<LanSyncServer?>(null) }
    var link by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun keepOn(on: Boolean) {
        val w = (ctx as? Activity)?.window ?: return
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun stop() { server?.stop(); server = null; link = ""; keepOn(false) }
    fun start() {
        val ips = localIps()
        if (ips.isEmpty()) { status = "مفيش شبكة محلية. وصّل الموبايل بنفس الواي فاي اللي عليه الكمبيوتر (أو شغّل هوت سبوت)."; return }
        val secret = android.util.Base64.decode(prefs.lanSecret, android.util.Base64.URL_SAFE)
        val s = LanSyncServer(secret, rawVault) { ev ->
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            main.post { status = if (ev == "served") "الكمبيوتر سحب الخزنة ($t)" else "محاولة مرفوضة ($t)" }
        }
        val port = s.start()
        server = s
        link = "k2sync://${ips.first()}:$port/${prefs.lanSecret}"
        status = "شغّال على ${ips.first()}:$port"
        keepOn(true)
    }
    DisposableEffect(Unit) { onDispose { server?.stop(); keepOn(false) } }

    Section("مزامنة مع إضافة المتصفح") {
        Text("بتشغّل سيرفر مؤقت على الواي فاي بيسمح للإضافة بسحب الخزنة المشفّرة (ماستر باسوردك ما بيتبعتش). اربط مرة واحدة بمسح الكود من الإضافة.")
        if (server == null) {
            Button(onClick = { start() }, modifier = Modifier.fillMaxWidth()) { Text("تشغيل المزامنة") }
        } else {
            val bmp = remember(link) { qr(link).asImageBitmap() }
            Image(bmp, contentDescription = "كود الربط", modifier = Modifier.size(220.dp))
            Text("الكود ده بيربط الكمبيوتر بخزنتك. ما تصوّرهوش ولا تبعتهوش لأي حد.", style = MaterialTheme.typography.bodySmall)
            Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { stop() }, modifier = Modifier.fillMaxWidth()) { Text("إيقاف") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { stop(); prefs.rotateLanSecret(); status = "اتلغى الربط مع كل الأجهزة. شغّل المزامنة واربط من جديد." }) {
            Text("إلغاء ربط كل الأجهزة")
        }
    }
}
