package com.shawady.vault.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shawady.vault.core.Entry

/** Read-only item screen: view + copy. Editing happens only after pressing "تعديل". */
@Composable
fun EntryView(e: Entry, folderName: String?, onEdit: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val info = EntryTypes.of(e.type)
    val voice = remember(e.id) { mutableStateMapOf<String, String>().apply { putAll(e.extra) } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(K2Icons.Back, contentDescription = "رجوع") }
            if (e.type == "login") Avatar(e.title) else IconTile(info.icon, info.color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    info.label + (folderName?.let { "  •  📂 $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("✏️ تعديل") }

        if (info.creds) {
            if (info.hasUser && e.username.isNotBlank()) ViewRow("اسم المستخدم / الإيميل", e.username, false, "username")
            if (e.password.isNotEmpty()) ViewRow(info.passLabel, e.password, true, "password")
            if (e.type == "login" && e.url.isNotBlank()) ViewRow("الرابط", e.url, false, "url")
            if (e.type == "login" && !e.totpSecret.isNullOrBlank()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("كود TOTP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TotpLive(e.totpSecret!!)
                    }
                }
            }
        }
        for (f in info.fields) {
            val v = e.extra[f.key].orEmpty()
            if (v.isNotBlank()) ViewRow(f.label, v, f.secret, f.key)
        }
        if (e.notes.isNotBlank()) ViewRow("ملاحظات", e.notes, false, "notes")
        if (!e.extra[VOICE_KEY].isNullOrEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { VoiceSection(voice, readOnly = true) } }
        }
    }
}

@Composable
private fun ViewRow(label: String, value: String, secret: Boolean, copyLabel: String) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (secret && !show) "••••••••••" else value, style = MaterialTheme.typography.bodyLarge)
            }
            if (secret) TextButton(onClick = { show = !show }) { Text(if (show) "إخفاء" else "عرض") }
            IconButton(onClick = { copySensitive(ctx, copyLabel, value) }) {
                Icon(K2Icons.Copy, contentDescription = "نسخ", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Multi-select picker used to add items to / remove items from a folder in one go. */
@Composable
fun BulkPickDialog(
    title: String, confirmLabel: String, items: List<Entry>, hint: (Entry) -> String,
    onConfirm: (List<String>) -> Unit, onClose: () -> Unit,
) {
    val picked = remember { mutableStateListOf<String>() }
    var q by remember { mutableStateOf("") }
    val shown = items.filter { q.isBlank() || it.title.contains(q, true) || it.username.contains(q, true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text("بحث") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { shown.forEach { if (it.id !in picked) picked.add(it.id) } }) { Text("تحديد الكل (${shown.size})") }
                    TextButton(onClick = { picked.clear() }) { Text("إلغاء التحديد") }
                }
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (shown.isEmpty()) Text("مفيش عناصر", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (it in shown) {
                        Row(
                            Modifier.fillMaxWidth().clickable { if (it.id in picked) picked.remove(it.id) else picked.add(it.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = it.id in picked, onCheckedChange = { c -> if (c) picked.add(it.id) else picked.remove(it.id) })
                            Column(Modifier.weight(1f)) {
                                Text(it.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val h = hint(it)
                                if (h.isNotBlank()) Text(h, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = picked.isNotEmpty(), onClick = { onConfirm(picked.toList()); onClose() }) { Text("$confirmLabel (${picked.size})") } },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } },
    )
}
