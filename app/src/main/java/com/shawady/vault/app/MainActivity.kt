package com.shawady.vault.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.shawady.vault.core.BiometricUnlock
import com.shawady.vault.core.Entry
import com.shawady.vault.core.Totp
import com.shawady.vault.core.VaultCrypto
import com.shawady.vault.core.VaultData
import com.shawady.vault.core.VaultMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab { Home, Vault, Tools, Settings }

sealed interface Screen {
    data object Setup : Screen
    data object Unlock : Screen
    data object Vault : Screen
    data class Edit(val entry: Entry?, val newType: String = "login") : Screen
    data object Health : Screen
}

class MainActivity : FragmentActivity() {

    private val store by lazy { VaultStore(applicationContext) }
    private val prefs by lazy { AppPrefs(applicationContext) }
    private val pinStore by lazy { PinStore(applicationContext) }

    private var screen by mutableStateOf<Screen>(Screen.Unlock)
    private var tab by mutableStateOf(Tab.Home)
    private var addDialog by mutableStateOf(false)
    private var entries by mutableStateOf<List<Entry>>(emptyList())
    private var message by mutableStateOf<String?>(null)
    private var pendingRemote by mutableStateOf<VaultCrypto.VaultFile?>(null)
    private var resumeTick by mutableStateOf(0)
    private var backgroundedAt = 0L
    private var needMasterForExport by mutableStateOf(false)
    private var trashTarget by mutableStateOf<Entry?>(null)
    private var pinDialog by mutableStateOf(false)

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { lockNow() }
    }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(store.rawJson().toByteArray(Charsets.UTF_8)) }
                    message = "اتصدّر الملف المشفر. انقله للجهاز التاني وامسح النسخة بعد الاستيراد."
                } catch (e: Exception) {
                    message = "فشل التصدير"
                }
            }
        }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    store.importRaw(readText(uri))
                    screen = Screen.Unlock
                } catch (e: Exception) {
                    message = "الملف غير صالح"
                }
            }
        }

    private val mergeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    handleMerge(readText(uri))
                } catch (e: Exception) {
                    message = "فشل قراءة الملف"
                }
            }
        }

    private val importEntriesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    doImport(readText(uri))
                } catch (e: Exception) {
                    message = "فشل قراءة الملف"
                }
            }
        }

    private val exportCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                try {
                    val list = Session.data?.entries?.filter { !it.deleted && it.trashedAt == 0L } ?: emptyList()
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(Importer.toCsv(list).toByteArray(Charsets.UTF_8)) }
                    message = "اتصدّر ${list.size} عنصر كنص عادي غير مشفر. امسح الملف أول ما تخلص!"
                } catch (e: Exception) {
                    message = "فشل التصدير"
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenFlag()
        ContextCompat.registerReceiver(
            this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screen = computeScreen()
        refreshEntries()
        setContent { AppTheme { Root() } }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (Session.unlocked && backgroundedAt != 0L &&
            SystemClock.elapsedRealtime() - backgroundedAt > prefs.autoLockMs
        ) {
            lockNow()
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        applyScreenFlag()
        // The autofill "add account" page may have saved a new entry while the app was open.
        if (Session.unlocked) refreshEntries()
    }

    // ---------- state helpers ----------

    private fun computeScreen(): Screen = when {
        !store.exists() -> Screen.Setup
        Session.unlocked -> Screen.Vault
        else -> Screen.Unlock
    }

    private fun refreshEntries() {
        entries = Session.data?.entries
            ?.filter { !it.deleted }
            ?.sortedBy { it.title.lowercase() }
            ?: emptyList()
    }

    @Suppress("DEPRECATION")
    private fun applyScreenFlag() {
        if (prefs.blockScreens) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun tombstone(e: Entry, now: Long) = e.copy(
        username = "", password = "", notes = "", totpSecret = null, deleted = true, updatedAt = now,
        history = emptyList(), extra = emptyMap(), trashedAt = 0L, favorite = false,
    )

    /** Items that stayed in the trash for more than 30 days become tombstones. */
    private fun purgeTrash() {
        val d = Session.data ?: return
        val now = System.currentTimeMillis()
        val limit = 30L * 24 * 3600 * 1000
        var changed = false
        val list = d.entries.map { e ->
            if (!e.deleted && e.trashedAt > 0L && now - e.trashedAt > limit) {
                changed = true
                tombstone(e, now)
            } else {
                e
            }
        }
        if (changed) Session.persist(store, VaultData(list))
    }

    private fun lockNow() {
        Session.lock()
        entries = emptyList()
        pendingRemote = null
        if (store.exists()) screen = Screen.Unlock
    }

    private fun readText(uri: Uri): String =
        contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }

    private fun saveEntry(e: Entry) {
        val d = Session.data ?: return
        Session.persist(store, VaultData(d.entries.filter { it.id != e.id } + e))
        refreshEntries()
        screen = Screen.Vault
    }

    /** Edit-screen delete = move to trash (restorable for 30 days). */
    private fun trashEntry(e: Entry) {
        val now = System.currentTimeMillis()
        saveEntry(e.copy(trashedAt = now, updatedAt = now, favorite = false))
        message = "اتنقل لسلة المهملات (30 يوم)"
    }

    private fun restoreEntry(e: Entry) {
        val d = Session.data ?: return
        Session.persist(store, VaultData(d.entries.filter { it.id != e.id } + e.copy(trashedAt = 0L, updatedAt = System.currentTimeMillis())))
        refreshEntries()
    }

    private fun eraseEntry(e: Entry) {
        val d = Session.data ?: return
        Session.persist(store, VaultData(d.entries.filter { it.id != e.id } + tombstone(e, System.currentTimeMillis())))
        refreshEntries()
    }

    private fun toggleFavorite(e: Entry) {
        val d = Session.data ?: return
        Session.persist(store, VaultData(d.entries.filter { it.id != e.id } + e.copy(favorite = !e.favorite, updatedAt = System.currentTimeMillis())))
        refreshEntries()
    }

    private fun doImport(text: String) {
        val d = Session.data ?: return
        val res = try {
            Importer.parse(text, System.currentTimeMillis())
        } catch (e: Exception) {
            message = e.message ?: "تعذر قراءة الملف"
            return
        }
        fun key(e: Entry) = Triple(e.url.lowercase().trimEnd('/'), e.username, e.password)
        val seen = d.entries.filter { !it.deleted && it.type == "login" }.map { key(it) }.toHashSet()
        val fresh = res.entries.filter { it.type != "login" || seen.add(key(it)) }
        if (fresh.isNotEmpty()) {
            Session.persist(store, VaultData(d.entries + fresh))
            refreshEntries()
        }
        message = "اتضاف ${fresh.size} عنصر، اتخطى ${res.entries.size - fresh.size} مكرر. امسح ملف الاستيراد (غير مشفر)!"
    }

    // ---------- manual sync ----------

    private fun handleMerge(text: String) {
        val local = Session.data
        val k = Session.keyCopy()
        if (local == null || k == null) {
            message = "الخزنة اتقفلت. افتحها وجرب تاني."
            return
        }
        val remote = try {
            VaultCrypto.VaultFile.fromJson(text)
        } catch (e: Exception) {
            k.fill(0)
            message = "الملف غير صالح"
            return
        }
        val remoteData = try {
            VaultData.fromBytes(VaultCrypto.readPayload(remote, k))
        } catch (e: Exception) {
            null
        } finally {
            k.fill(0)
        }
        if (remoteData != null) applyMerge(local, remoteData) else pendingRemote = remote
    }

    private fun applyMerge(local: VaultData, remote: VaultData) {
        val merged = VaultMerge.merge(local, remote)
        Session.persist(store, merged)
        refreshEntries()
        message = "تم الدمج (${entries.size} حساب). صدّر الملف وانقله للجهاز التاني عشان يتطابقوا."
    }

    // ---------- UI ----------

    @Composable
    private fun Root() {
        BackHandler(enabled = screen is Screen.Edit || screen is Screen.Health) { screen = Screen.Vault }
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                message?.let { m ->
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m, Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer)
                            TextButton(onClick = { message = null }) { Text("حسنًا") }
                        }
                    }
                }
                when (val s = screen) {
                    Screen.Setup -> SetupScreen()
                    Screen.Unlock -> UnlockScreen(
                        activity = this@MainActivity,
                        store = store,
                        onUnlocked = { f, k ->
                            try {
                                Session.open(f, k)
                                purgeTrash()
                                refreshEntries()
                                tab = Tab.Home
                                screen = Screen.Vault
                            } catch (e: Exception) {
                                k.fill(0)
                                message = "الملف تالف أو غير متوافق"
                            }
                        },
                    )
                    Screen.Vault -> Shell()
                    is Screen.Edit -> EntryEditor(
                        heading = if (s.entry == null) "عنصر جديد" else "تعديل",
                        orig = s.entry,
                        initialType = s.newType,
                        onSave = { saveEntry(it) },
                        onCancel = { screen = Screen.Vault },
                        onDelete = s.entry?.let { e -> { trashEntry(e) } },
                    )
                    Screen.Health -> HealthScreen(
                        entries = entries,
                        onOpen = { e -> screen = Screen.Edit(e) },
                        onBack = { tab = Tab.Tools; screen = Screen.Vault },
                    )
                }
            }
            pendingRemote?.let { RemotePasswordDialog(it) }
            trashTarget?.let { TrashDialog(it) }
            if (needMasterForExport) MasterConfirmDialog()
            if (pinDialog) PinSetupDialog()
            if (addDialog) AddTypeDialog()
        }
    }

    @Composable
    private fun RemotePasswordDialog(remote: VaultCrypto.VaultFile) {
        var pw by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRemote = null },
            title = { Text("ماستر باسورد الملف المستورد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الملف ده متشفر بمفتاح مختلف. اكتب الماستر باسورد بتاعه.")
                    PasswordField("الماستر باسورد", pw, !busy) { pw = it }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && pw.isNotEmpty(), onClick = {
                    busy = true
                    val chars = pw.toCharArray()
                    scope.launch {
                        val data = withContext(Dispatchers.Default) {
                            try {
                                val k = VaultCrypto.unlock(remote, chars)
                                try { VaultData.fromBytes(VaultCrypto.readPayload(remote, k)) } finally { k.fill(0) }
                            } catch (e: Exception) {
                                null
                            } finally {
                                chars.fill('\u0000')
                            }
                        }
                        busy = false
                        val local = Session.data
                        if (data == null) {
                            message = "كلمة المرور غير صحيحة"
                        } else if (local != null) {
                            applyMerge(local, data)
                            pendingRemote = null
                        } else {
                            pendingRemote = null
                        }
                    }
                }) { Text("دمج") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingRemote = null }) { Text("إلغاء") } },
        )
    }

    @Composable
    private fun SetupScreen() {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LogoMark(80.dp)
            Text("أهلاً في K2Pass", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "الماستر باسورد مش بيتخزن ومفيش طريقة لاسترجاعه. اختار جملة طويلة (10 حروف على الأقل).",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            PasswordField("الماستر باسورد", pw, !busy) { pw = it }
            PasswordField("تأكيد الماستر باسورد", pw2, !busy) { pw2 = it }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                if (pw.length < 10) {
                    message = "الماستر لازم يكون 10 حروف على الأقل"
                } else if (pw != pw2) {
                    message = "التأكيد مش مطابق"
                } else {
                    busy = true
                    val chars = pw.toCharArray()
                    scope.launch {
                        val created = withContext(Dispatchers.Default) {
                            try {
                                VaultCrypto.create(chars, VaultData(emptyList()).toBytes())
                            } finally {
                                chars.fill('\u0000')
                            }
                        }
                        store.save(created.first)
                        store.markMasterUnlock()
                        Session.open(created.first, created.second)
                        pw = ""
                        pw2 = ""
                        busy = false
                        refreshEntries()
                        screen = Screen.Vault
                    }
                }
            }) { Text(if (busy) "جاري الإنشاء..." else "إنشاء الخزنة") }
            OutlinedButton(
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = { importFileLauncher.launch(arrayOf("*/*")) },
            ) { Text("عندي ملف خزنة من جهاز تاني") }
        }
    }

    @Composable
    private fun VaultScreen() {
        var q by remember { mutableStateOf("") }
        var cat by remember { mutableStateOf<String?>(null) }
        var filter by remember { mutableStateOf("all") }
        val inTrash = filter == "trash"
        val active = entries.filter { it.trashedAt == 0L }
        val base = if (inTrash) entries.filter { it.trashedAt > 0L } else active
        val shown = base.filter {
            val byFilter = when (filter) {
                "fav" -> it.favorite
                "it" -> EntryTypes.isIt(it.type)
                "login", "note", "card", "identity" -> it.type == filter
                else -> true
            }
            byFilter && (inTrash || cat == null || it.category == cat) &&
                (q.isBlank() || it.title.contains(q, true) || it.username.contains(q, true) || it.url.contains(q, true))
        }.sortedWith(compareByDescending<Entry> { !inTrash && it.favorite }.thenBy { it.title.lowercase() })
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("K2Pass", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "${active.size} عنصر", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { lockNow() }) { Text("🔒 قفل") }
                }
                OutlinedTextField(
                    value = q, onValueChange = { q = it },
                    placeholder = { Text("ابحث في حساباتك") },
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in listOf(
                        "all" to "الكل", "fav" to "⭐ المفضلة", "login" to "🔑 دخول", "it" to "🖥️ IT", "note" to "📝 ملاحظات",
                        "card" to "💳 كروت", "identity" to "🪪 هويات", "trash" to "🗑 المهملات",
                    )) {
                        FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) })
                    }
                }
                if (!inTrash) CategoryChips(selected = cat, onSelect = { cat = it }, withAll = true)
                if (inTrash) {
                    Text(
                        "العناصر هنا بتتمسح نهائيًا بعد 30 يوم. اضغط على عنصر للاسترجاع أو الحذف.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                inTrash -> "سلة المهملات فاضية"
                                entries.isEmpty() -> "الخزنة فاضية.\nاضغط + في النص وضيف أول عنصر"
                                else -> "مفيش نتائج"
                            },
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(shown, key = { it.id }) { e -> EntryCard(e, inTrash) }
                    }
                }
            }
        }
    }

    private fun subtitle(e: Entry): String = when (e.type) {
        "note" -> e.notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "—"
        "card" -> e.extra["number"]?.takeIf { it.length >= 4 }?.let { "•••• " + it.takeLast(4) } ?: (e.extra["holder"] ?: "—")
        "identity" -> e.extra["name"] ?: e.extra["email"] ?: "—"
        else -> if (EntryTypes.isIt(e.type)) {
            listOfNotNull(e.extra["host"] ?: e.extra["ssid"], e.username.takeIf { it.isNotBlank() }).joinToString(" • ").ifBlank { "—" }
        } else {
            e.username.ifBlank { "—" }
        }
    }

    @Composable
    private fun EntryCard(e: Entry, inTrash: Boolean) {
        val ctx = LocalContext.current
        Card(
            Modifier.fillMaxWidth().clickable { if (inTrash) trashTarget = e else screen = Screen.Edit(e) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(e.title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (e.type == "login") "" else EntryTypes.of(e.type).emoji + " ") + e.title,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle(e), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!inTrash) {
                    if (EntryTypes.hasCreds(e.type)) {
                        if (!e.totpSecret.isNullOrBlank()) {
                            TextButton(onClick = {
                                val r = Totp.generate(e.totpSecret!!)
                                if (r != null) copySensitive(ctx, "totp", r.code) else message = "مفتاح TOTP غير صالح"
                            }) { Text("OTP") }
                        }
                        TextButton(onClick = { copySensitive(ctx, "password", e.password) }) { Text("نسخ") }
                    }
                    TextButton(onClick = { toggleFavorite(e) }) { Text(if (e.favorite) "⭐" else "☆") }
                } else {
                    Text(Categories.of(e.category).emoji, Modifier.padding(end = 12.dp))
                }
            }
        }
    }

    @Composable
    private fun TrashDialog(e: Entry) {
        val daysLeft = (30 - (System.currentTimeMillis() - e.trashedAt) / (24L * 3600 * 1000)).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = { trashTarget = null },
            title = { Text(e.title) },
            text = { Text("هيتمسح نهائيًا بعد $daysLeft يوم.") },
            confirmButton = { TextButton(onClick = { restoreEntry(e); trashTarget = null }) { Text("استرجاع") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { eraseEntry(e); trashTarget = null }) {
                        Text("حذف نهائي", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { trashTarget = null }) { Text("إلغاء") }
                }
            },
        )
    }

    /** Asks for the master password before an unencrypted export (the vault key proves who is asking). */
    @Composable
    private fun MasterConfirmDialog() {
        var pw by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var bad by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { if (!busy) needMasterForExport = false },
            title = { Text("تصدير غير مشفر") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الملف هيحتوي كل الباسوردات كنص عادي. اكتب الماستر باسورد للتأكيد.")
                    PasswordField("الماستر باسورد", pw, !busy) { pw = it; bad = false }
                    if (bad) Text("كلمة المرور غير صحيحة", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && pw.isNotEmpty(), onClick = {
                    busy = true
                    val chars = pw.toCharArray()
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) {
                            try {
                                VaultCrypto.unlock(Session.file!!, chars).fill(0)
                                true
                            } catch (e: Exception) {
                                false
                            } finally {
                                chars.fill('\u0000')
                            }
                        }
                        busy = false
                        if (ok) {
                            needMasterForExport = false
                            exportCsvLauncher.launch("k2pass-plain-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".csv")
                        } else {
                            bad = true
                        }
                    }
                }) { Text("تصدير") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { needMasterForExport = false }) { Text("إلغاء") } },
        )
    }

    @Composable
    private fun PinSetupDialog() {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var err by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { if (!busy) pinDialog = false },
            title = { Text("إعداد PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${PinStore.MIN_LEN} أرقام على الأقل. 5 محاولات غلط وبيتمسح. الماستر باسورد يفضل الأساسي.")
                    SecretField("PIN", p1, { v -> p1 = v.filter { it.isDigit() } }, false, {}, !busy, KeyboardType.NumberPassword)
                    PasswordField("تأكيد الـ PIN", p2, !busy) { v -> p2 = v.filter { it.isDigit() } }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (p1.length < PinStore.MIN_LEN) {
                        err = "الـ PIN لازم ${PinStore.MIN_LEN} أرقام على الأقل"
                    } else if (p1 != p2) {
                        err = "التأكيد مش مطابق"
                    } else {
                        val k = Session.keyCopy()
                        if (k == null) {
                            pinDialog = false
                        } else {
                            busy = true
                            val chars = p1.toCharArray()
                            scope.launch {
                                withContext(Dispatchers.Default) {
                                    try { pinStore.set(chars, k) } finally { chars.fill('\u0000'); k.fill(0) }
                                }
                                busy = false
                                pinDialog = false
                                message = "اتفعّل الـ PIN"
                            }
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pinDialog = false }) { Text("إلغاء") } },
        )
    }

    @Composable
    private fun Shell() {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.Home -> HomeScreen()
                    Tab.Vault -> VaultScreen()
                    Tab.Tools -> ToolsScreen()
                    Tab.Settings -> PrefsScreen()
                }
            }
            BottomBar()
        }
    }

    @Composable
    private fun BottomBar() {
        Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TabItem(Tab.Home, "🏠", "الرئيسية")
                TabItem(Tab.Vault, "🗄️", "الخزنة")
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = { addDialog = true }, shape = CircleShape,
                        contentPadding = PaddingValues(0.dp), modifier = Modifier.size(56.dp),
                    ) { Text("+", fontSize = 28.sp) }
                }
                TabItem(Tab.Tools, "🧰", "الأدوات")
                TabItem(Tab.Settings, "⚙️", "الإعدادات")
            }
        }
    }

    @Composable
    private fun RowScope.TabItem(t: Tab, emoji: String, label: String) {
        val sel = tab == t
        Column(
            Modifier.weight(1f).clickable { tab = t }.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji)
            Text(
                label, style = MaterialTheme.typography.labelSmall,
                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }

    @Composable
    private fun AddTypeDialog() {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("إضافة عنصر") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (t in EntryTypes.all) {
                        Row(
                            Modifier.fillMaxWidth().clickable { addDialog = false; screen = Screen.Edit(null, t.id) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(t.emoji)
                            Spacer(Modifier.width(12.dp))
                            Text(t.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addDialog = false }) { Text("إلغاء") } },
        )
    }

    @Composable
    private fun HomeScreen() {
        val rep = remember(entries) { Health.analyze(entries, System.currentTimeMillis()) }
        val active = entries.filter { it.trashedAt == 0L }
        val favs = active.filter { it.favorite }.sortedBy { it.title.lowercase() }.take(5)
        val recent = active.sortedByDescending { it.updatedAt }.take(5)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(48.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("K2Pass", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "${active.size} عنصر محفوظ", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { lockNow() }) { Text("🔒 قفل") }
            }
            if (active.isEmpty()) {
                Section("ابدأ من هنا") {
                    Text("اضغط + في النص وضيف أول عنصر: حساب، جهاز، سيرفر، راوتر، سويتش، فايروول، Wi-Fi، ملاحظة، كارت أو هوية.")
                }
            } else {
                Section("صحة الأمان") {
                    if (rep.total == 0) {
                        Text("مفيش باسوردات تتفحص لسه.")
                    } else {
                        Text("ضعيفة: ${rep.weak.size}  •  مكررة: ${rep.reused.sumOf { it.size }}  •  قديمة: ${rep.old.size}")
                        OutlinedButton(onClick = { screen = Screen.Health }) { Text("🩺 فتح التقرير") }
                    }
                }
            }
            if (favs.isNotEmpty()) {
                Text("⭐ المفضلة", style = MaterialTheme.typography.titleMedium)
                favs.forEach { EntryCard(it, false) }
            }
            if (recent.isNotEmpty()) {
                Text("🕘 آخر تعديل", style = MaterialTheme.typography.titleMedium)
                recent.forEach { EntryCard(it, false) }
            }
        }
    }

    @Composable
    private fun ToolsScreen() {
        val ctx = LocalContext.current
        var len by remember { mutableStateOf(20f) }
        var sym by remember { mutableStateOf(true) }
        var gen by remember { mutableStateOf(PasswordGenerator.generate(20, true)) }
        val withTotp = entries.filter { it.trashedAt == 0L && !it.totpSecret.isNullOrBlank() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("الأدوات", style = MaterialTheme.typography.titleLarge)

            Section("مولّد الباسورد") {
                Text(gen, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { gen = PasswordGenerator.generate(len.toInt(), sym) }) { Text("توليد") }
                    TextButton(onClick = { copySensitive(ctx, "password", gen) }) { Text("نسخ") }
                    Checkbox(checked = sym, onCheckedChange = { sym = it })
                    Text("رموز   الطول ${len.toInt()}")
                }
                Slider(value = len, onValueChange = { len = it }, valueRange = 8f..48f)
            }

            Section("المصادقة (TOTP)") {
                if (withTotp.isEmpty()) {
                    Text("مفيش حسابات فيها مفتاح TOTP. ضيف المفتاح في حساب من نوع تسجيل دخول.")
                }
                withTotp.forEach { e ->
                    Text(e.title, style = MaterialTheme.typography.labelLarge)
                    TotpLive(e.totpSecret!!)
                }
            }

            Section("صحة الخزنة") {
                Text("اكتشف الباسوردات الضعيفة والمكررة والقديمة.")
                OutlinedButton(onClick = { screen = Screen.Health }) { Text("🩺 فتح التقرير") }
            }

            Section("استيراد وتصدير") {
                Text("استيراد من Bitwarden (JSON) أو CSV من Chrome / Bitwarden / KeePassXC / LastPass. الملفات دي غير مشفرة، امسحها بعد الاستيراد.")
                OutlinedButton(onClick = { importEntriesLauncher.launch(arrayOf("*/*")) }) { Text("استيراد JSON / CSV") }
                OutlinedButton(onClick = { needMasterForExport = true }) { Text("تصدير CSV غير مشفر") }
            }

            Section("المزامنة اليدوية") {
                Text("١) استورد ودمج ملف الجهاز التاني  ٢) صدّر الملف المدموج وانقله له.")
                OutlinedButton(onClick = { mergeLauncher.launch(arrayOf("*/*")) }) { Text("استيراد ودمج ملف") }
                OutlinedButton(onClick = {
                    exportLauncher.launch("k2pass-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".vault")
                }) { Text("تصدير نسخة مشفرة") }
            }
        }
    }

    @Composable
    private fun PrefsScreen() {
        var bioOn by remember { mutableStateOf(store.loadBlob() != null) }
        var pinOn by remember(pinDialog) { mutableStateOf(pinStore.isSet()) }
        var autoLock by remember { mutableStateOf(prefs.autoLockMs) }
        var clipSec by remember { mutableStateOf(prefs.clipClearSec) }
        var blockScr by remember { mutableStateOf(prefs.blockScreens) }
        var copyTotp by remember { mutableStateOf(prefs.copyTotp) }
        var blocked by remember { mutableStateOf(prefs.blockedDomains) }
        var cur by remember { mutableStateOf("") }
        var new1 by remember { mutableStateOf("") }
        var new2 by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val autofillOn = remember(resumeTick) {
            (Settings.Secure.getString(contentResolver, "autofill_service") ?: "").startsWith(packageName)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("الإعدادات", style = MaterialTheme.typography.titleLarge)

            Section("الملء التلقائي") {
                Text("الحالة: " + if (autofillOn) "مُفعّل ✅" else "غير مُفعّل")
                if (!autofillOn) {
                    Button(onClick = {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:$packageName")),
                        )
                    }) { Text("تفعيل الملء التلقائي") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("نسخ كود TOTP بعد التعبئة", Modifier.weight(1f))
                    Switch(checked = copyTotp, onCheckedChange = { copyTotp = it; prefs.copyTotp = it })
                }
                Text("مواقع / تطبيقات ممنوعة (واحد في السطر، مثال: example.com أو androidapp://com.pkg)", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = blocked, onValueChange = { blocked = it; prefs.blockedDomains = it },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
            }

            Section("الأمان") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("فتح بالبصمة / الوجه", Modifier.weight(1f))
                    Switch(checked = bioOn, onCheckedChange = { want ->
                        val bio = BiometricUnlock(this@MainActivity)
                        if (want) {
                            val k = Session.keyCopy()
                            if (!bio.isAvailable()) {
                                message = "سجّل بصمة أو وجه في إعدادات الجهاز الأول"
                                k?.fill(0)
                            } else if (k != null) {
                                bio.enroll(
                                    k,
                                    onDone = { blob -> store.saveBlob(blob); bioOn = true; k.fill(0) },
                                    onError = { m -> message = m; k.fill(0) },
                                )
                            }
                        } else {
                            bio.deleteKey()
                            store.deleteBlob()
                            bioOn = false
                        }
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("فتح بـ PIN", Modifier.weight(1f))
                    Switch(checked = pinOn, onCheckedChange = { want ->
                        if (want) pinDialog = true else { pinStore.clear(); pinOn = false }
                    })
                }
                Text("القفل التلقائي بعد الخروج من التطبيق", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((ms, label) in listOf(60_000L to "دقيقة", 300_000L to "5 دقايق", 900_000L to "15 دقيقة", 3_600_000L to "ساعة")) {
                        FilterChip(selected = autoLock == ms, onClick = { autoLock = ms; prefs.autoLockMs = ms }, label = { Text(label) })
                    }
                }
                Text("مسح الحافظة بعد النسخ", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((sec, label) in listOf(10 to "10 ث", 20 to "20 ث", 30 to "30 ث", 60 to "60 ث", 0 to "مفيش")) {
                        FilterChip(selected = clipSec == sec, onClick = { clipSec = sec; prefs.clipClearSec = sec }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("منع لقطات الشاشة", Modifier.weight(1f))
                    Switch(checked = blockScr, onCheckedChange = { blockScr = it; prefs.blockScreens = it; applyScreenFlag() })
                }
                Text("تغيير الماستر باسورد", style = MaterialTheme.typography.titleSmall)
                PasswordField("الحالي", cur, !busy) { cur = it }
                PasswordField("الجديد (10 حروف على الأقل)", new1, !busy) { new1 = it }
                PasswordField("تأكيد الجديد", new2, !busy) { new2 = it }
                Button(enabled = !busy, onClick = {
                    if (new1.length < 10) {
                        message = "الماستر الجديد لازم يكون 10 حروف على الأقل"
                    } else if (new1 != new2) {
                        message = "التأكيد مش مطابق"
                    } else {
                        busy = true
                        val curChars = cur.toCharArray()
                        val newChars = new1.toCharArray()
                        scope.launch {
                            val nf = withContext(Dispatchers.Default) {
                                try {
                                    val f = Session.file!!
                                    VaultCrypto.unlock(f, curChars).fill(0) // verifies the current password
                                    val k = Session.keyCopy()!!
                                    try { VaultCrypto.changeMasterPassword(f, k, newChars) } finally { k.fill(0) }
                                } catch (e: Exception) {
                                    null
                                } finally {
                                    curChars.fill('\u0000')
                                    newChars.fill('\u0000')
                                }
                            }
                            busy = false
                            if (nf == null) {
                                message = "الماستر الحالي غلط"
                            } else {
                                store.save(nf)
                                Session.setFile(nf)
                                store.markMasterUnlock()
                                cur = ""; new1 = ""; new2 = ""
                                message = "اتغيّر الماستر باسورد"
                            }
                        }
                    }
                }) { Text("تغيير") }
            }

            Text(
                "K2Pass 0.4 — أوفلاين بالكامل",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
