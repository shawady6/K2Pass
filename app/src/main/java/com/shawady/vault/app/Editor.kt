package com.shawady.vault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shawady.vault.core.Entry
import com.shawady.vault.core.Totp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * One form for adding and editing an item (used by the app and by the autofill pages).
 *  - [orig]: the item being edited, or null for a new one.
 *  - [prefill]: initial values for a new item (title / url / username / password / category).
 *  - [autoGenerate]: start with a freshly generated password (sign-up forms).
 *  - [allowTypes]: show the item-type chooser (new items only; autofill pages are always logins).
 */
@Composable
fun EntryEditor(
    heading: String,
    orig: Entry?,
    prefill: Entry? = null,
    autoGenerate: Boolean = false,
    allowTypes: Boolean = true,
    initialType: String = "login",
    saveLabel: String = "حفظ",
    onSave: (Entry) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val base = orig ?: prefill
    var type by remember { mutableStateOf(base?.type ?: initialType) }
    val info = EntryTypes.of(type)
    var title by remember { mutableStateOf(base?.title ?: "") }
    var username by remember { mutableStateOf(base?.username ?: "") }
    var password by remember {
        mutableStateOf(
            base?.password?.takeIf { it.isNotEmpty() } ?: (if (autoGenerate) PasswordGenerator.generate(20, true) else ""),
        )
    }
    var url by remember { mutableStateOf(base?.url ?: "") }
    var notes by remember { mutableStateOf(base?.notes ?: "") }
    var totp by remember { mutableStateOf(base?.totpSecret ?: "") }
    var cat by remember { mutableStateOf(base?.category ?: "other") }
    var fav by remember { mutableStateOf(base?.favorite ?: false) }
    val extra = remember { mutableStateMapOf<String, String>().also { m -> base?.extra?.let { m.putAll(it) } } }
    val shown = remember { mutableStateMapOf<String, Boolean>() }
    var showPass by remember { mutableStateOf(autoGenerate) }
    var showTotp by remember { mutableStateOf(false) }
    var len by remember { mutableStateOf(20f) }
    var symbols by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(heading, style = MaterialTheme.typography.titleLarge)

        if (orig == null && allowTypes) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in EntryTypes.all) {
                    FilterChip(selected = type == t.id, onClick = { type = t.id }, label = { Text(t.emoji + " " + t.label) })
                }
            }
        }

        OutlinedTextField(
            colors = k2FieldColors(),
            value = title, onValueChange = { title = it },
            label = { Text(if (info.creds && !info.isIt) "اسم الحساب" else "الاسم") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = fav, onCheckedChange = { fav = it })
            Text("⭐ مفضل")
        }

        Text("الفئة", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CategoryChips(selected = cat, onSelect = { id -> if (id != null) cat = id }, withAll = false)

        if (info.isIt) for (f in info.fields) ExtraField(f, extra, shown)

        if (info.creds) {
            if (info.hasUser) {
                OutlinedTextField(
                    colors = k2FieldColors(),
                    value = username, onValueChange = { username = it }, label = { Text("اسم المستخدم / الإيميل") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            SecretField(info.passLabel, password, { password = it }, showPass, { showPass = !showPass })
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (info.hasUser) TextButton(onClick = { copySensitive(ctx, "username", username) }) { Text("نسخ المستخدم") }
                TextButton(onClick = { copySensitive(ctx, "password", password) }) { Text("نسخ الباسورد") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { password = PasswordGenerator.generate(len.toInt(), symbols); showPass = true }) { Text("توليد") }
                Checkbox(checked = symbols, onCheckedChange = { symbols = it })
                Text("رموز")
                Text("   الطول ${len.toInt()}")
            }
            Slider(value = len, onValueChange = { len = it }, valueRange = 8f..48f)
            if (type == "login") {
                OutlinedTextField(
                    colors = k2FieldColors(),
                    value = url, onValueChange = { url = it },
                    label = { Text("الرابط: https://site.com أو androidapp://com.package") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                SecretField("مفتاح TOTP (base32 أو otpauth://)", totp, { totp = it }, showTotp, { showTotp = !showTotp })
                if (totp.isNotBlank()) TotpLive(totp)
            }
        }

        if (!info.isIt) for (f in info.fields) ExtraField(f, extra, shown)

        OutlinedTextField(
            colors = k2FieldColors(),
            value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات") },
            minLines = if (type == "note") 8 else 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (orig != null && orig.history.isNotEmpty()) {
            OutlinedButton(onClick = { historyOpen = true }) { Text("🕘 السجل (${orig.history.size} نسخة قديمة)") }
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val t = title.trim()
                if (t.isEmpty()) {
                    err = "اكتب اسم"
                } else {
                    val now = System.currentTimeMillis()
                    val isLogin = type == "login"
                    val un = if (info.creds && info.hasUser) username.trim() else ""
                    val pw = if (info.creds) password else ""
                    val u = if (isLogin) url.trim() else ""
                    val tp = if (isLogin) totp.trim().ifEmpty { null } else null
                    val keys = info.fields.map { it.key }
                    val ex: Map<String, String> = extra.filter { it.key in keys && it.value.isNotBlank() }.toMap()
                    val e = if (orig == null) {
                        Entry(
                            id = UUID.randomUUID().toString(), title = t, username = un, password = pw, url = u,
                            notes = notes, totpSecret = tp, updatedAt = now, category = cat, type = type,
                            favorite = fav, extra = ex,
                        )
                    } else {
                        val content = orig.title != t || orig.username != un || orig.password != pw ||
                            orig.url != u || orig.notes != notes || orig.totpSecret != tp || orig.extra != ex
                        val meta = orig.category != cat || orig.favorite != fav
                        if (!content && !meta) {
                            orig
                        } else {
                            val hist = if (content) (listOf(orig.copy(history = emptyList())) + orig.history).take(20) else orig.history
                            orig.copy(
                                title = t, username = un, password = pw, url = u, notes = notes, totpSecret = tp,
                                extra = ex, category = cat, favorite = fav, updatedAt = now, history = hist,
                            )
                        }
                    }
                    onSave(e)
                }
            }) { Text(saveLabel) }
            OutlinedButton(onClick = onCancel) { Text("إلغاء") }
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("🗑 للمهملات", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (historyOpen && orig != null) {
        HistoryDialog(
            hist = orig.history,
            onRestore = { h -> username = h.username; password = h.password; url = h.url; historyOpen = false },
            onClose = { historyOpen = false },
        )
    }
}

@Composable
private fun ExtraField(f: Field, extra: MutableMap<String, String>, shown: MutableMap<String, Boolean>) {
    val value = extra[f.key] ?: ""
    if (f.secret) {
        val show = shown[f.key] == true
        SecretField(
            f.label, value, { extra[f.key] = it }, show, { shown[f.key] = !show },
            keyboardType = if (f.numeric) KeyboardType.Number else KeyboardType.Text,
        )
    } else {
        OutlinedTextField(
            colors = k2FieldColors(),
            value = value, onValueChange = { extra[f.key] = it }, label = { Text(f.label) },
            singleLine = !f.multiline, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Live one-time code with a countdown; tap to copy. */
@Composable
fun TotpLive(secret: String) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(secret) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val r = Totp.generate(secret, now)
    if (r == null) {
        Text("المفتاح غير صالح", color = MaterialTheme.colorScheme.error)
        return
    }
    val pretty = if (r.code.length == 6) r.code.substring(0, 3) + " " + r.code.substring(3) else r.code
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(pretty, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text("${r.secondsLeft} ث", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { r.secondsLeft.toFloat() / r.period }, modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = { copySensitive(ctx, "totp", r.code) }) { Text("نسخ") }
    }
}

@Composable
private fun HistoryDialog(hist: List<Entry>, onRestore: (Entry) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("السجل") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (h in hist) HistoryRow(h, onRestore)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
    )
}

@Composable
private fun HistoryRow(h: Entry, onRestore: (Entry) -> Unit) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    Column {
        Text(fmt.format(Date(h.updatedAt)), style = MaterialTheme.typography.labelMedium)
        Text(h.username.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
        Text(if (show) h.password else "••••••••", style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = { show = !show }) { Text(if (show) "إخفاء" else "عرض") }
            TextButton(onClick = { copySensitive(ctx, "password", h.password) }) { Text("نسخ") }
            TextButton(onClick = { onRestore(h) }) { Text("استرجاع") }
        }
    }
}
