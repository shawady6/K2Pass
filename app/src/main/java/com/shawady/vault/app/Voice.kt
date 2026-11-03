package com.shawady.vault.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.shawady.vault.core.Entry
import kotlinx.coroutines.delay
import java.io.File

/** Key inside Entry.extra that holds the Base64 AAC (m4a) recording. Lives inside the encrypted vault. */
const val VOICE_KEY = "voice"
private const val MAX_SECONDS = 120

/** History copies must not duplicate audio (would bloat the vault up to 20x). */
fun Entry.withoutVoice(): Entry = if (extra.containsKey(VOICE_KEY)) copy(extra = extra - VOICE_KEY) else this

/**
 * Record / play / delete one voice note. State is kept in [extra] (key "voice"), saved with the entry.
 * Temp files in cacheDir are plaintext, so they are removed immediately after use.
 */
@Composable
fun VoiceSection(extra: MutableMap<String, String>) {
    val ctx = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var playFile by remember { mutableStateOf<File?>(null) }
    var seconds by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    val has = !extra[VOICE_KEY].isNullOrEmpty()

    fun stopPlay() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        playFile?.delete(); playFile = null
    }

    fun finishRecording(save: Boolean) {
        val r = recorder ?: return
        recorder = null
        var ok = true
        try { r.stop() } catch (_: Exception) { ok = false }
        try { r.release() } catch (_: Exception) {}
        val f = recFile; recFile = null
        if (f != null) {
            if (save && ok && f.length() > 0) {
                extra[VOICE_KEY] = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            } else if (save) msg = "التسجيل قصير جدًا"
            f.delete()
        }
    }

    fun startRecording() {
        stopPlay()
        try {
            val f = File.createTempFile("rec", ".m4a", File(ctx.cacheDir, "").also { it.mkdirs() })
            @Suppress("DEPRECATION")
            val r = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setMaxDuration(MAX_SECONDS * 1000)
                setOutputFile(f.absolutePath)
                prepare(); start()
            }
            recFile = f; recorder = r; seconds = 0; msg = null
        } catch (e: Exception) {
            msg = "تعذّر بدء التسجيل"
        }
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else msg = "لازم تسمح بالميكروفون عشان التسجيل"
    }

    LaunchedEffect(recorder) {
        while (recorder != null) {
            delay(1000); seconds++
            if (seconds >= MAX_SECONDS) { finishRecording(true); break }
        }
    }
    DisposableEffect(Unit) { onDispose { finishRecording(false); stopPlay() } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text("تسجيل صوتي", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (recorder != null) {
                Button(onClick = { finishRecording(true) }) { Text("إيقاف وحفظ (${seconds}ث / ${MAX_SECONDS}ث)") }
                OutlinedButton(onClick = { finishRecording(false) }) { Text("إلغاء") }
            } else {
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        startRecording() else perm.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(if (has) "تسجيل جديد (يستبدل القديم)" else "ابدأ التسجيل") }
                if (has) {
                    OutlinedButton(onClick = {
                        if (player != null) { stopPlay() } else {
                            try {
                                val f = File.createTempFile("play", ".m4a", ctx.cacheDir)
                                f.writeBytes(Base64.decode(extra[VOICE_KEY], Base64.NO_WRAP))
                                playFile = f
                                player = MediaPlayer().apply {
                                    setDataSource(f.absolutePath); prepare()
                                    setOnCompletionListener { stopPlay() }
                                    start()
                                }
                                msg = null
                            } catch (e: Exception) { stopPlay(); msg = "تعذّر التشغيل" }
                        }
                    }) { Text(if (player != null) "إيقاف" else "تشغيل") }
                    OutlinedButton(onClick = { stopPlay(); extra.remove(VOICE_KEY) }) {
                        Text("حذف", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("الحد الأقصى دقيقتين. التسجيل بيتخزن مشفّر داخل الخزنة.", style = MaterialTheme.typography.bodySmall)
    }
}
