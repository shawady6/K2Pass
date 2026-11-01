package com.shawady.vault.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shawady.vault.core.Entry

/** Vault health report: weak, reused and old passwords. Every active item that has a password counts (logins, IT devices, Wi-Fi). */
object Health {
    class Report(val weak: List<Entry>, val reused: List<List<Entry>>, val old: List<Entry>, val total: Int)

    private val common = listOf(
        "password", "123456", "qwerty", "admin", "letmein", "welcome", "iloveyou", "abc123",
        "111111", "000000", "1q2w3e", "monkey", "dragon", "login", "passw0rd", "p@ssw0rd",
    )
    private const val YEAR_MS = 365L * 24 * 3600 * 1000

    fun isWeak(p: String): Boolean {
        if (p.length < 10) return true
        var classes = 0
        if (p.any { it.isLowerCase() }) classes++
        if (p.any { it.isUpperCase() }) classes++
        if (p.any { it.isDigit() }) classes++
        if (p.any { !it.isLetterOrDigit() }) classes++
        if (classes < 3) return true
        if (p.toSet().size <= 3) return true
        val l = p.lowercase()
        return common.any { it in l }
    }

    fun analyze(all: List<Entry>, now: Long): Report {
        val logins = all.filter { !it.deleted && it.trashedAt == 0L && it.password.isNotEmpty() }
        val weak = logins.filter { isWeak(it.password) }
        val reused = logins.groupBy { it.password }.values.filter { it.size > 1 }
        val old = logins.filter { now - it.updatedAt > YEAR_MS }
        return Report(weak, reused, old, logins.size)
    }
}

@Composable
fun HealthScreen(entries: List<Entry>, onOpen: (Entry) -> Unit, onBack: () -> Unit) {
    val r = androidx.compose.runtime.remember(entries) { Health.analyze(entries, System.currentTimeMillis()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("صحة الخزنة", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("رجوع") }
        }
        Text(
            "فُحص ${r.total} حساب. الباسوردات مش بتخرج من الجهاز.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HealthGroup("⚠️ باسوردات ضعيفة (${r.weak.size})", "قصيرة أو بسيطة أو شائعة", r.weak, onOpen)
        Section("🔁 باسوردات مكررة (${r.reused.sumOf { it.size }})") {
            if (r.reused.isEmpty()) Text("لا يوجد ✅", color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.reused.forEachIndexed { i, g ->
                Text("مجموعة ${i + 1}", style = MaterialTheme.typography.labelLarge)
                g.forEach { e -> EntryLine(e, onOpen) }
            }
        }
        HealthGroup("🕰️ باسوردات قديمة (${r.old.size})", "لم تتغير من أكتر من سنة", r.old, onOpen)
    }
}

@Composable
private fun HealthGroup(title: String, hint: String, list: List<Entry>, onOpen: (Entry) -> Unit) {
    Section(title) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list.isEmpty()) Text("لا يوجد ✅", color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEach { e -> EntryLine(e, onOpen) }
    }
}

@Composable
private fun EntryLine(e: Entry, onOpen: (Entry) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(e) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(e.title, 32.dp)
        Column(Modifier.padding(start = 10.dp)) {
            Text(e.title, style = MaterialTheme.typography.titleSmall)
            Text(
                e.username.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
