package com.shawady.vault.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    fun of(id: String): Category = all.firstOrNull { it.id == id } ?: all.last()
}

/** Horizontally scrollable category chips. [withAll] adds an "all" chip that selects null. */
@Composable
fun CategoryChips(selected: String?, onSelect: (String?) -> Unit, withAll: Boolean) {
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
    }
}
