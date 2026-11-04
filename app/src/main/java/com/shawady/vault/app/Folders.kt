package com.shawady.vault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shawady.vault.core.Entry

/**
 * Main folders (personal / work / project ...). A folder is a vault entry with type "folder" (title = name),
 * so it is encrypted, synced and survives while empty. Items point to it with category = "f:<folderId>".
 */
object Folders {
    const val TYPE = "folder"
    const val PREFIX = "f:"
    fun list(all: List<Entry>?): List<Entry> =
        (all ?: emptyList()).filter { it.type == TYPE && !it.deleted }.sortedBy { it.title.lowercase() }
    fun catOf(folder: Entry) = PREFIX + folder.id
    fun nameOf(cat: String, folders: List<Entry>): String? =
        folders.firstOrNull { catOf(it) == cat }?.title
}

/** Folder picker used in the editor: one button, dialog with the list. */
@Composable
fun FolderPicker(selected: String, onSelect: (String) -> Unit) {
    val folders = Folders.list(Session.data?.entries)
    var open by remember { mutableStateOf(false) }
    val label = Folders.nameOf(selected, folders) ?: "بدون فولدر"
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("📂 الفولدر: $label") }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("اختار الفولدر") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { onSelect("other"); open = false }) { Text("بدون فولدر") }
                    for (f in folders) TextButton(onClick = { onSelect(Folders.catOf(f)); open = false }) { Text("📂 " + f.title) }
                    if (folders.isEmpty()) Text("مفيش فولدرات. اعمل واحد من شاشة الخزنة (زرار الفولدرات).", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("إغلاق") } },
        )
    }
}

/**
 * Vault filter + folder manager. [filter] is "all", "fav", "trash" or a folder category ("f:<id>").
 */
@Composable
fun FolderDialog(
    folders: List<Entry>, counts: Map<String, Int>, favCount: Int, trashCount: Int, totalCount: Int,
    filter: String, onFilter: (String) -> Unit,
    onCreate: (String) -> Unit, onRename: (Entry, String) -> Unit, onDelete: (Entry) -> Unit,
    onClose: () -> Unit,
) {
    var nameDlg by remember { mutableStateOf<Pair<Entry?, String>?>(null) }
    var delDlg by remember { mutableStateOf<Entry?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("الفولدرات") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                @Composable fun row(key: String, text: String) {
                    TextButton(onClick = { onFilter(key); onClose() }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (filter == key) "✓ " else "") + text, modifier = Modifier.fillMaxWidth())
                    }
                }
                row("all", "الكل ($totalCount)")
                row("fav", "⭐ المفضلة ($favCount)")
                row("trash", "🗑 المهملات ($trashCount)")
                if (folders.isNotEmpty()) Text("فولدراتي", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                for (f in folders) {
                    val key = Folders.catOf(f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onFilter(key); onClose() }, modifier = Modifier.weight(1f)) {
                            Text((if (filter == key) "✓ " else "") + "📂 ${f.title} (${counts[key] ?: 0})", modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(onClick = { nameDlg = f to f.title }) { Text("✏️") }
                        TextButton(onClick = { delDlg = f }) { Text("🗑") }
                    }
                }
                OutlinedButton(onClick = { nameDlg = null to "" }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("+ فولدر جديد") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } },
    )
    nameDlg?.let { (f, n) ->
        var name by remember(f) { mutableStateOf(n) }
        AlertDialog(
            onDismissRequest = { nameDlg = null },
            title = { Text(if (f == null) "فولدر جديد" else "إعادة تسمية") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("اسم الفولدر (مثال: شخصي، شغل، مشروع X)") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    if (f == null) onCreate(name.trim()) else onRename(f, name.trim())
                    nameDlg = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { nameDlg = null }) { Text("إلغاء") } },
        )
    }
    delDlg?.let { f ->
        AlertDialog(
            onDismissRequest = { delDlg = null },
            title = { Text("حذف الفولدر؟") },
            text = { Text("الفولدر \"${f.title}\" هيتحذف، والعناصر اللي جواه هتفضل موجودة بدون فولدر.") },
            confirmButton = { TextButton(onClick = { onDelete(f); delDlg = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { delDlg = null }) { Text("إلغاء") } },
        )
    }
}
