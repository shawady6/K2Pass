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
import com.shawady.vault.core.LanPushClient
import com.shawady.vault.core.VaultCrypto
import com.shawady.vault.core.VaultData
import com.shawady.vault.core.VaultMerge
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

    var pcTarget by remember { mutableStateOf(prefs.pcTarget) }
    fun pushToPc(): Unit {
        val m = Regex("^([A-Za-z0-9_-]{43})@(.+)$").find(pcTarget) ?: run { status = "مفيش كمبيوتر مربوط."; return }
        val vault = rawVault() ?: run { status = "مفيش خزنة تتبعت."; return }
        val secret = Base64.decode(m.groupValues[1], Base64.URL_SAFE)
        val addrs = m.groupValues[2].split(',').mapNotNull { a -> a.substringBefore(':').takeIf { it.isNotEmpty() }?.let { it to (a.substringAfter(':').toIntOrNull() ?: 8766) } }
        status = "باعت الخزنة للكمبيوتر…"
        Thread {
            var r = LanPushClient.Result.UNREACHABLE
            for ((h, p) in addrs) {          // the PC lists every private address it has; the first that answers wins
                r = LanPushClient.push(h, p, secret, vault, 2500)
                if (r != LanPushClient.Result.UNREACHABLE) break
            }
            main.post {
                status = when (r) {
                    LanPushClient.Result.OK -> "الخزنة وصلت الكمبيوتر ✔"
                    LanPushClient.Result.DENIED -> "الكمبيوتر رفض الكود. اعمل ربط جديد من تطبيق الكمبيوتر."
                    LanPushClient.Result.UNREACHABLE -> {
                        // Probably the PC firewall: flip the direction. We serve, the PC (outgoing connections aren't blocked) pulls.
                        prefs.addLanSecret(m.groupValues[1])
                        if (start()) "الكمبيوتر مش بيستقبل (غالبًا الجدار الناري)، فشغّلت وضع السحب: تطبيق الكمبيوتر هيلاقي الموبايل لوحده. سيب الشاشة دي مفتوحة. العنوان: $addr" else status
                    }
                    LanPushClient.Result.REJECTED -> "الكمبيوتر ما قبلش الملف."
                }
            }
        }.start()
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val c = r.contents ?: return@rememberLauncherForActivityResult
        val m = Regex("^k2pair://([A-Za-z0-9_-]{43})(?:@(.+))?$").find(c.trim())
        if (m == null) { status = "الكود ده مش كود ربط من K2Pass."; return@rememberLauncherForActivityResult }
        val hosts = m.groupValues[2]
        if (hosts.isNotEmpty()) {
            // Windows app: it listens, we push. Every address must be a private IPv4 with a port.
            val ok = hosts.split(',').all { a ->
                val mm = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3}):(\\d{2,5})$").find(a) ?: return@all false
                val o = mm.groupValues.drop(1).take(4).map { it.toInt() }
                o.all { it <= 255 } && (o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31))
            }
            if (!ok) { status = "عنوان الكمبيوتر مش على شبكة محلية."; return@rememberLauncherForActivityResult }
            pcTarget = "${m.groupValues[1]}@$hosts"; prefs.pcTarget = pcTarget
            pushToPc()
        } else {
            prefs.addLanSecret(m.groupValues[1])
            if (start()) status = "اتربط ✔. سيب الشاشة دي مفتوحة، والإضافة هتلاقي الموبايل لوحدها."
        }
    }
    DisposableEffect(Unit) { onDispose { server?.stop(); keepOn(false) } }

    Section("مزامنة مع إضافة المتصفح") {
        Text("١) في الإضافة على الكمبيوتر: ⋮ ← ربط الموبايل، هيظهر كود QR.  ٢) اضغط الزرار تحت وامسحه بكاميرا الموبايل. الاتنين لازم على نفس الواي فاي. الماستر باسورد ما بيتبعتش، الخزنة بتتسحب مشفّرة.")
        Button(onClick = {
            scan.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("وجّه الكاميرا على الكود اللي في الإضافة").setBeepEnabled(false).setOrientationLocked(false),
            )
        }, modifier = Modifier.fillMaxWidth()) { Text("📷 مسح كود (إضافة المتصفح / تطبيق الكمبيوتر)") }
        if (pcTarget.isNotEmpty() && prefs.pcSyncNote.isNotEmpty()) Text(
            "المزامنة التلقائية: " + prefs.pcSyncNote + if (prefs.pcSyncMs > 0) " (" + SimpleDateFormat("d/M HH:mm", java.util.Locale.getDefault()).format(Date(prefs.pcSyncMs)) + ")" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        if (pcTarget.isNotEmpty()) OutlinedButton(onClick = { pushToPc() }, modifier = Modifier.fillMaxWidth()) { Text("📤 ابعت الخزنة لتطبيق الكمبيوتر") }
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
        if (prefs.lanSecrets.isNotEmpty() || pcTarget.isNotEmpty()) {
            OutlinedButton(onClick = { stop(); prefs.clearLanSecrets(); prefs.pcTarget = ""; pcTarget = ""; status = "اتلغى الربط مع كل الأجهزة." }) { Text("إلغاء ربط كل الأجهزة") }
        }
    }
}


/**
 * Keeps the paired Windows app in step: a few seconds after every save the vault is pushed to the PC and the PC's merged vault is
 * pulled back and merged in (same VaultMerge on both sides). Silent when the PC is off or unreachable; the last result is kept in prefs.
 */
object PcAutoSync {
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var job: Runnable? = null

    /** Parses "<secret>@ip:port,ip:port" into (secret bytes, addresses). */
    fun parse(target: String): Pair<ByteArray, List<Pair<String, Int>>>? {
        val m = Regex("^([A-Za-z0-9_-]{43})@(.+)$").find(target) ?: return null
        val secret = Base64.decode(m.groupValues[1], Base64.URL_SAFE)
        val addrs = m.groupValues[2].split(',').mapNotNull { a ->
            a.substringBefore(':').takeIf { it.isNotEmpty() }?.let { it to (a.substringAfter(':').toIntOrNull() ?: 8766) }
        }
        return secret to addrs
    }

    /** Call from the UI thread after a save. [onMerged] runs on the UI thread when the PC contributed something new. */
    fun schedule(prefs: AppPrefs, store: VaultStore, onMerged: () -> Unit) {
        if (prefs.pcTarget.isEmpty()) return
        job?.let { ui.removeCallbacks(it) }
        val r = Runnable { Thread { run(prefs, store, onMerged) }.start() }
        job = r
        ui.postDelayed(r, 4000)
    }

    fun run(prefs: AppPrefs, store: VaultStore, onMerged: () -> Unit): String {
        val (secret, addrs) = parse(prefs.pcTarget) ?: return "no target"
        val vault = store.rawJson()
        var res = LanPushClient.Result.UNREACHABLE
        var host: Pair<String, Int>? = null
        for (a in addrs) { res = LanPushClient.push(a.first, a.second, secret, vault, 2500); if (res != LanPushClient.Result.UNREACHABLE) { host = a; break } }
        val note = when (res) {
            LanPushClient.Result.OK -> {
                val back = LanPushClient.pull(host!!.first, host.second, secret, 4000)
                if (back != null && mergeBack(store, back)) ui.post(onMerged)
                "تمت المزامنة مع الكمبيوتر"
            }
            LanPushClient.Result.DENIED -> "الكمبيوتر رفض الكود (اعمل ربط جديد)"
            LanPushClient.Result.REJECTED -> "الكمبيوتر ما قبلش الملف"
            LanPushClient.Result.UNREACHABLE -> "الكمبيوتر مش متاح (مقفول أو الجدار الناري)"
        }
        prefs.pcSyncNote = note
        if (res == LanPushClient.Result.OK) prefs.pcSyncMs = System.currentTimeMillis()
        return note
    }

    /** Merge the PC's vault into the open vault; true when something changed. Needs the vault unlocked. */
    private fun mergeBack(store: VaultStore, text: String): Boolean {
        val local = Session.data ?: return false
        val k = Session.keyCopy() ?: return false
        val remote = try {
            VaultData.fromBytes(VaultCrypto.readPayload(VaultCrypto.VaultFile.fromJson(text), k))
        } catch (e: Exception) { return false } finally { k.fill(0) }
        val merged = VaultMerge.merge(local, remote)
        if (merged == VaultMerge.merge(local, local)) return false
        Session.quiet = true
        try { Session.persist(store, merged) } finally { Session.quiet = false }
        return true
    }
}
