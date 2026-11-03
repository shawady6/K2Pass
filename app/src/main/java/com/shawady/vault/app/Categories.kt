package com.shawady.vault.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shawady.vault.core.Entry
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

data class Category(val id: String, val label: String, val emoji: String)

object Categories {
    val all = listOf(
        Category("social", "تواصل", "💬"),
        Category("email", "إيميل", "✉️"),
        Category("banking", "بنوك ومحافظ", "🏦"),
        Category("shopping", "تسوق", "🛒"),
        Category("work", "شغل", "💼"),
        Category("dev", "تطوير", "💻"),
        Category("entertainment", "ترفيه", "🎬"),
        Category("education", "تعليم", "🎓"),
        Category("other", "أخرى", "📁"),
    )

    /** Unknown ids (e.g. from a newer version of the app) fall back to "other". */
    fun of(id: String): Category =
        if (id.startsWith(FOLDER_PREFIX)) Category(id, id.removePrefix(FOLDER_PREFIX), "📂")
        else all.firstOrNull { it.id == id } ?: all.last()

    const val FOLDER_PREFIX = "f:"

    /** User folders = category ids "f:<name>" found on live entries (no vault-format change; empty folders vanish). */
    fun folders(entries: List<Entry>?): List<String> =
        (entries ?: emptyList()).filter { !it.deleted }.map { it.category }
            .filter { it.startsWith(FOLDER_PREFIX) && it.length > FOLDER_PREFIX.length }
            .distinct().sortedBy { it.lowercase() }
}

/** Horizontally scrollable category chips. [withAll] adds an "all" chip that selects null. */
@Composable
fun CategoryChips(selected: String?, onSelect: (String?) -> Unit, withAll: Boolean, allowNewFolder: Boolean = false) {
    val mine = Categories.folders(Session.data?.entries).toMutableList()
    if (selected != null && selected.startsWith(Categories.FOLDER_PREFIX) && selected !in mine) mine.add(selected)
    var dlg by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (withAll) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("الكل") })
        }
        for (c in Categories.all) {
            FilterChip(
                selected = selected == c.id,
                onClick = { onSelect(c.id) },
                label = { Text(c.label) },
            )
        }
        for (id in mine) {
            FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text("📂 " + Categories.of(id).label) })
        }
        if (allowNewFolder) {
            FilterChip(selected = false, onClick = { name = ""; dlg = true }, label = { Text("+ فولدر جديد") })
        }
    }
    if (dlg) {
        AlertDialog(
            onDismissRequest = { dlg = false },
            title = { Text("فولدر جديد") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("اسم الفولدر") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onSelect(Categories.FOLDER_PREFIX + name.trim()); dlg = false }) { Text("إنشاء") }
            },
            dismissButton = { TextButton(onClick = { dlg = false }) { Text("إلغاء") } },
        )
    }
}
