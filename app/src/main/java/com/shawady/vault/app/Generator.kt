package com.shawady.vault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DigitColor = Color(0xFF6FA8FF)
private val SymbolColor = Color(0xFFFF8AC8)

/** Generator tab: Password / Passphrase / Username. [onCreate] opens a new item with the result filled in. */
@Composable
fun GeneratorScreen(onCreate: (mode: Int, value: String) -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("generator", 0) }
    var mode by remember { mutableIntStateOf(0) }
    // password
    var len by remember { mutableStateOf(sp.getInt("len", 16).toFloat()) }
    var up by remember { mutableStateOf(sp.getBoolean("up", true)) }
    var lo by remember { mutableStateOf(sp.getBoolean("lo", true)) }
    var dg by remember { mutableStateOf(sp.getBoolean("dg", true)) }
    var sy by remember { mutableStateOf(sp.getBoolean("sy", true)) }
    var amb by remember { mutableStateOf(sp.getBoolean("amb", false)) }
    // passphrase
    var words by remember { mutableIntStateOf(sp.getInt("words", 4)) }
    var sep by remember { mutableStateOf(sp.getString("sep", "-") ?: "-") }
    var cap by remember { mutableStateOf(sp.getBoolean("cap", true)) }
    var num by remember { mutableStateOf(sp.getBoolean("num", false)) }
    // username
    var ukind by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }

    var tick by remember { mutableIntStateOf(0) }
    fun save() = sp.edit().putInt("len", len.toInt()).putBoolean("up", up).putBoolean("lo", lo).putBoolean("dg", dg)
        .putBoolean("sy", sy).putBoolean("amb", amb).putInt("words", words).putString("sep", sep)
        .putBoolean("cap", cap).putBoolean("num", num).apply()

    // recomputed on every state change or tick
    @Suppress("UNUSED_EXPRESSION") tick
    val result = when (mode) {
        0 -> Gen.password(len.toInt(), up, lo, dg, sy, amb)
        1 -> Gen.passphrase(ctx, words, sep, cap, num)
        else -> Gen.username(ctx, ukind, email, domain)
    }
    val shownResult = remember(result) { result }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("المولّد", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("باسورد", "عبارة مرور", "اسم مستخدم").forEachIndexed { i, l ->
                FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(l) })
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val styled = buildAnnotatedString {
                    for (c in shownResult) {
                        val col = when {
                            c.isDigit() -> DigitColor
                            !c.isLetterOrDigit() && c != '-' && c != '.' && c != '@' -> SymbolColor
                            else -> Color.Unspecified
                        }
                        withStyle(SpanStyle(color = col)) { append(c) }
                    }
                }
                Text(
                    if (shownResult.isEmpty()) "اكتب الإيميل/الدومين تحت" else "",
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (shownResult.isNotEmpty()) {
                    Text(styled, fontFamily = FontFamily.Monospace, fontSize = 20.sp, modifier = Modifier.weight(1f))
                }
                IconButton(onClick = { tick++ }) { Icon(K2Icons.Sync, contentDescription = "جديد") }
                IconButton(onClick = { if (shownResult.isNotEmpty()) copySensitive(ctx, "K2Pass", shownResult) }) {
                    Icon(K2Icons.Copy, contentDescription = "نسخ")
                }
            }
        }
        Button(onClick = { if (shownResult.isNotEmpty()) onCreate(mode, shownResult) }, modifier = Modifier.fillMaxWidth()) {
            Text("إنشاء عنصر بالنتيجة دي")
        }

        when (mode) {
            0 -> Section("خيارات الباسورد") {
                Text("الطول: ${len.toInt()}")
                Slider(value = len, onValueChange = { len = it; save() }, valueRange = 8f..64f)
                Opt("A–Z", up) { up = it; save() }
                Opt("a–z", lo) { lo = it; save() }
                Opt("0–9", dg) { dg = it; save() }
                Opt("رموز !@#\$%^&*", sy) { sy = it; save() }
                Opt("تجنّب الحروف المتشابهة (l I O 0)", amb) { amb = it; save() }
            }
            1 -> Section("خيارات عبارة المرور") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("عدد الكلمات"); Text("$words", style = MaterialTheme.typography.titleMedium) }
                    OutlinedButton(enabled = words > 3, onClick = { words--; save() }) { Text("−") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = words < 10, onClick = { words++; save() }) { Text("+") }
                }
                OutlinedTextField(
                    colors = k2FieldColors(), value = sep, onValueChange = { sep = it.take(3); save() },
                    label = { Text("الفاصل") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Opt("أول حرف كابيتال", cap) { cap = it; save() }
                Opt("إضافة رقم", num) { num = it; save() }
                Text("قايمة الكلمات: EFF Long (7776 كلمة، بدون إنترنت).", style = MaterialTheme.typography.bodySmall)
            }
            else -> Section("خيارات اسم المستخدم") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("إيميل بـ +", "كل الإيميلات", "كلمات عشوائية").forEachIndexed { i, l ->
                        FilterChip(selected = ukind == i, onClick = { ukind = i }, label = { Text(l) })
                    }
                }
                when (ukind) {
                    0 -> OutlinedTextField(
                        colors = k2FieldColors(), value = email, onValueChange = { email = it.trim() },
                        label = { Text("إيميلك (مطلوب)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    1 -> OutlinedTextField(
                        colors = k2FieldColors(), value = domain, onValueChange = { domain = it.trim() },
                        label = { Text("الدومين (example.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun Opt(label: String, v: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = v, onCheckedChange = onChange)
    }
}
