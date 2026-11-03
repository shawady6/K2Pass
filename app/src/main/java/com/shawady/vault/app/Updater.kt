package com.shawady.vault.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Manual in-app update from the project's public GitHub release (tag "latest").
 * Network is used ONLY when the user taps the buttons below: no background checks, no telemetry, nothing sent
 * except a plain HTTPS GET (generic User-Agent, no identifiers). The APK is verified against the SHA-256 published in
 * the release notes, and Android itself refuses to install it unless it is signed with the same key as the installed app.
 */
object Updater {
    private const val API = "https://api.github.com/repos/shawady6/K2Pass/releases/tags/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/shawady6/K2Pass/releases/download/"
    private const val MAX_BYTES = 120L * 1024 * 1024

    class Info(val versionCode: Long, val versionName: String, val sha256: String, val url: String, val size: Long)

    fun installedVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun installedVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "Only HTTPS is allowed" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "K2Pass-updater")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return c
    }

    /** Blocking: call from Dispatchers.IO. Throws on network / format problems. */
    fun fetchLatest(): Info {
        val c = open(API)
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub رد بكود ${c.responseCode}")
            val o = JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            val body = o.optString("body", "")
            fun field(k: String) = body.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim()
            val vc = field("versionCode")?.toLongOrNull() ?: throw IllegalStateException("بيانات الإصدار ناقصة في الـ release")
            val sha = field("sha256")?.lowercase()?.takeIf { it.length == 64 && it.all { ch -> ch in "0123456789abcdef" } }
                ?: throw IllegalStateException("الـ release مفيهوش SHA-256")
            val assets = o.getJSONArray("assets")
            var url = ""
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == "K2Pass-debug.apk") {
                    url = a.getString("browser_download_url")
                    size = a.optLong("size", 0L)
                }
            }
            if (!url.startsWith(DOWNLOAD_PREFIX)) throw IllegalStateException("رابط التحميل غير متوقع")
            if (size <= 0L || size > MAX_BYTES) throw IllegalStateException("حجم الملف غير منطقي")
            return Info(vc, field("versionName") ?: vc.toString(), sha, url, size)
        } finally {
            c.disconnect()
        }
    }

    /** Blocking download with SHA-256 verification. Returns the verified file. */
    fun download(ctx: Context, info: Info, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "K2Pass-update.apk")
        val md = MessageDigest.getInstance("SHA-256")
        val c = open(info.url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("فشل التحميل (${c.responseCode})")
            var total = 0L
            c.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalStateException("الملف أكبر من المتوقع")
                        md.update(buf, 0, n)
                        os.write(buf, 0, n)
                        onProgress((total.toFloat() / info.size).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != info.sha256) {
            out.delete()
            throw IllegalStateException("التحقق من الملف فشل (SHA-256 مختلف). اتلغى التحديث.")
        }
        return out
    }

    /** True when the user still has to allow "install unknown apps" for K2Pass (settings screen is opened). */
    fun needsInstallPermission(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return false
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    fun launchInstaller(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Settings card: current version, "check for update", download + install. */
@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<Updater.Info?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    val current = remember { Updater.installedVersionCode(ctx) }

    Section("التحديثات") {
        Text("الإصدار الحالي: ${Updater.installedVersionName(ctx)} ($current)")
        Text(
            "الفحص بيتم لما تدوس الزر بس. مفيش اتصال تلقائي. بيتصل بـ GitHub فقط.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.let { Text(it) }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; status = "بفحص آخر إصدار..."; info = null; ready = null; progress = null
                scope.launch {
                    try {
                        val i = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                        if (i.versionCode > current) {
                            info = i
                            status = "في إصدار جديد: ${i.versionName} (${i.versionCode})"
                        } else {
                            status = "أنت على آخر إصدار."
                        }
                    } catch (e: Exception) {
                        status = "تعذر الفحص: " + (e.message ?: "خطأ في الاتصال")
                    }
                    busy = false
                }
            }) { Text("تحقق من تحديث") }

            val i = info
            if (i != null && ready == null) {
                Button(enabled = !busy, onClick = {
                    busy = true; status = "بنزّل ${i.versionName}..."; progress = 0f
                    scope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) { Updater.download(ctx, i) { p -> progress = p } }
                            ready = f
                            status = "التحميل اتأكد منه. دوس \"تثبيت\"."
                        } catch (e: Exception) {
                            status = "تعذر التحميل: " + (e.message ?: "خطأ")
                        }
                        progress = null
                        busy = false
                    }
                }) { Text("تنزيل التحديث") }
            }
            val f = ready
            if (f != null) {
                Button(onClick = {
                    if (Updater.needsInstallPermission(ctx)) {
                        status = "اسمح لـ K2Pass بتثبيت التطبيقات من الشاشة اللي اتفتحت، وارجع دوس \"تثبيت\" تاني."
                    } else {
                        Updater.launchInstaller(ctx, f)
                    }
                }) { Text("تثبيت") }
            }
        }
        Text(
            "الخزنة والإعدادات بتفضل زي ما هي. لو التثبيت اترفض بسبب التوقيع، صدّر نسخة مشفرة الأول وثبّت من جديد.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
