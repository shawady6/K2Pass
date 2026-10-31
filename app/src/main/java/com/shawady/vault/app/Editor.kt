package com.shawady.vault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.shawady.vault.core.Entry
import java.util.UUID

/**
 * One form for adding and editing an account (used by the app and by the autofill "add account" page).
 *  - [orig]: the entry being edited, or null for a new one.
 *  - [prefill]: initial values for a new entry (title / url / username / category).
 *  - [autoGenerate]: start with a freshly generated password (sign-up forms).
 */
@Composable
fun EntryEditor(
    heading: String,
    orig: Entry?,
    prefill: Entry? = null,
    autoGenerate: Boolean = false,
    saveLabel: String = "حفظ",
    onSave: (Entry) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val base = orig ?: prefill
    var title by remember { mutableStateOf(base?.title ?: "") }
    var username by remember { mutableStateOf(base?.username ?: "") }
    var password by remember {
        mutableStateOf(
            base?.password?.takeIf { it.isNotEmpty() } ?: (if (autoGenerate) PasswordGenerator.generate(20, true) else ""),
        )
    }
    var url by remember { mutableStateOf(base?.url ?: "") }
    var notes by remember { mutableStateOf(base?.notes ?: "") }
    var cat by remember { mutableStateOf(base?.category ?: "other") }
    var show by remember { mutableStateOf(autoGenerate) }
    var len by remember { mutableStateOf(20f) }
    var symbols by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(heading, style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = title, onValueChange = { title = it }, label = { Text("اسم الحساب") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Text("الفئة", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CategoryChips(selected = cat, onSelect = { id -> if (id != null) cat = id }, withAll = false)

        OutlinedTextField(
            value = username, onValueChange = { username = it }, label = { Text("اسم المستخدم / الإيميل") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("الباسورد") },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { show = !show }) { Text(if (show) "إخفاء" else "إظهار") }
            TextButton(onClick = { copySensitive(ctx, "username", username) }) { Text("نسخ المستخدم") }
            TextButton(onClick = { copySensitive(ctx, "password", password) }) { Text("نسخ الباسورد") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { password = PasswordGenerator.generate(len.toInt(), symbols); show = true }) { Text("توليد") }
            Checkbox(checked = symbols, onCheckedChange = { symbols = it })
            Text("رموز")
            Text("   الطول ${len.toInt()}")
        }
        Slider(value = len, onValueChange = { len = it }, valueRange = 8f..48f)

        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("الرابط: https://site.com أو androidapp://com.package") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes, onValueChange = { notes = it }, label = { Text("ملاحظات" ) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (orig != null && orig.history.isNotEmpty()) {
            Text("نسخ قديمة محفوظة: ${orig.history.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val t = title.trim()
                if (t.isEmpty()) {
                    err = "اكتب اسم للحساب"
                } else {
                    val now = System.currentTimeMillis()
                    val e = if (orig == null) {
                        Entry(
                            id = UUID.randomUUID().toString(), title = t, username = username.trim(),
                            password = password, url = url.trim(), notes = notes, totpSecret = null,
                            updatedAt = now, category = cat,
                        )
                    } else {
                        val changed = orig.title != t || orig.username != username.trim() ||
                            orig.password != password || orig.url != url.trim() ||
                            orig.notes != notes || orig.category != cat
                        if (!changed) {
                            orig
                        } else {
                            val hist = (listOf(orig.copy(history = emptyList())) + orig.history).take(20)
                            orig.copy(
                                title = t, username = username.trim(), password = password,
                                url = url.trim(), notes = notes, category = cat,
                                updatedAt = now, history = hist,
                            )
                        }
                    }
                    onSave(e)
                }
            }) { Text(saveLabel) }
            OutlinedButton(onClick = onCancel) { Text("إلغاء") }
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
