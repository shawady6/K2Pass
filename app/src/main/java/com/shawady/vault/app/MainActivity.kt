package com.shawady.vault.app

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
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

enum class Tab { Vault, Generator, Tools, Settings }

sealed interface Screen {
    data object Setup : Screen
    data object Unlock : Screen
    data object Vault : Screen
    data class Edit(val entry: Entry?, val newType: String = "login", val prefill: Entry? = null) : Screen
    data class View(val entry: Entry) : Screen
    data object Health : Screen
}

class MainActivity : FragmentActivity() {

    private val store by lazy { VaultStore(applicationContext) }
    private val prefs by lazy { AppPrefs(applicationContext) }
    private val pinStore by lazy { PinStore(applicationContext) }

    private var screen by mutableStateOf<Screen>(Screen.Unlock)
    private var tab by mutableStateOf(Tab.Vault)
    private var addDialog by mutableStateOf(false)
    private var entries by mutableStateOf<List<Entry>>(emptyList())
    private var folders by mutableStateOf<List<Entry>>(emptyList())
    /** Vault section/folder currently open ("" = root). Lives here so View/Edit → back returns to the same folder. */
    private var vaultFilter by mutableStateOf("")
    /** 0 = name, 1 = last modified (newest first). */
    private var vaultSort by mutableStateOf(0)
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
                    prefs.lastExportMs = System.currentTimeMillis()
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
                    val list = Session.data?.entries?.filter { !it.deleted && it.trashedAt == 0L && it.type != Folders.TYPE } ?: emptyList()
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
        Session.onSaved = { PcAutoSync.schedule(prefs, store) { refreshEntries() } }
        setContent { AppTheme { Root() } }
    }

    override fun onDestroy() {
        Session.onSaved = null
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
        folders = Folders.list(Session.data?.entries)
        entries = Session.data?.entries
            ?.filter { !it.deleted && it.type != Folders.TYPE }
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
        vaultFilter = ""
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

    private fun exportName() = "k2pass-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".vault"

    private fun moveToCategory(ids: List<String>, cat: String) {
        val d = Session.data ?: return
        val now = System.currentTimeMillis()
        val set = ids.toHashSet()
        Session.persist(store, VaultData(d.entries.map { if (it.id in set && it.category != cat) it.copy(category = cat, updatedAt = now) else it }))
        refreshEntries()
        message = "اتنقل ${ids.size} عنصر"
    }

    private fun createFolder(name: String) {
        val d = Session.data ?: return
        val f = Entry(id = java.util.UUID.randomUUID().toString(), title = name, username = "", password = "",
            updatedAt = System.currentTimeMillis(), type = Folders.TYPE)
        Session.persist(store, VaultData(d.entries + f)); refreshEntries()
    }

    private fun renameFolder(f: Entry, name: String) {
        val d = Session.data ?: return
        Session.persist(store, VaultData(d.entries.filter { it.id != f.id } + f.copy(title = name, updatedAt = System.currentTimeMillis())))
        refreshEntries()
    }

    private fun deleteFolder(f: Entry) {
        val d = Session.data ?: return
        val now = System.currentTimeMillis()
        val key = Folders.catOf(f)
        val moved = d.entries.map { if (!it.deleted && it.category == key) it.copy(category = "other", updatedAt = now) else it }
        Session.persist(store, VaultData(moved.filter { it.id != f.id } + tombstone(f, now)))
        refreshEntries()
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
        BackHandler(enabled = screen is Screen.Edit || screen is Screen.Health || screen is Screen.View) {
            val cur = screen
            screen = if (cur is Screen.Edit && cur.entry != null) Screen.View(cur.entry) else Screen.Vault
        }
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
                                tab = Tab.Vault
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
                        prefill = s.prefill,
                        initialType = s.newType,
                        onSave = { saveEntry(it) },
                        onCancel = { screen = if (s.entry != null) Screen.View(s.entry) else Screen.Vault },
                        onDelete = s.entry?.let { e -> { trashEntry(e) } },
                    )
                    is Screen.View -> EntryView(
                        e = s.entry,
                        folderName = Folders.nameOf(s.entry.category, folders),
                        onEdit = { screen = Screen.Edit(s.entry) },
                        onClone = {
                            val c = s.entry.copy(
                                id = "", title = s.entry.title + " (نسخة)", history = emptyList(), updatedAt = 0L,
                                trashedAt = 0L, favorite = false, totpSecret = null, extra = s.entry.extra - VOICE_KEY,
                            )
                            screen = Screen.Edit(null, s.entry.type, c)
                        },
                        onBack = { screen = Screen.Vault },
                    )
                    Screen.Health -> HealthScreen(
                        entries = entries,
                        onOpen = { e -> screen = Screen.View(e) },
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
        // "" = root sections; "all", "fav", "trash", "t:<type>", "t:it", or a folder category "f:<id>"
        var folderDlg by remember { mutableStateOf(false) }
        var bulk by remember { mutableStateOf<String?>(null) } // "add" | "remove"
        var backupHidden by remember { mutableStateOf(false) }
        val active = entries.filter { it.trashedAt == 0L }
        val trashed = entries.filter { it.trashedAt > 0L }
        val inTrash = vaultFilter == "trash"
        val searching = q.isNotBlank()
        val rep = remember(entries) { Health.analyze(entries, System.currentTimeMillis()) }
        androidx.activity.compose.BackHandler(enabled = vaultFilter != "" && !searching) { vaultFilter = "" }

        fun inSection(e: Entry): Boolean = when {
            vaultFilter == "all" || vaultFilter == "" -> true
            vaultFilter == "fav" -> e.favorite
            vaultFilter == "t:it" -> EntryTypes.isIt(e.type) && e.type != "ssh"
            vaultFilter.startsWith("t:") -> e.type == vaultFilter.removePrefix("t:")
            vaultFilter.startsWith(Folders.PREFIX) -> e.category == vaultFilter
            else -> true
        }
        val pool = if (inTrash) trashed else active
        // Searches title, username, URLs, notes, folder name and all NON-secret extra fields (IP, host, SSID...).
        val matchQ: (Entry) -> Boolean = { e ->
            val secretKeys = EntryTypes.of(e.type).fields.filter { it.secret }.map { it.key }.toSet() + VOICE_KEY
            e.title.contains(q, true) || e.username.contains(q, true) || e.allUrls().any { u -> u.contains(q, true) } ||
                e.notes.contains(q, true) || (Folders.nameOf(e.category, folders)?.contains(q, true) == true) ||
                e.extra.any { (k, v) -> k !in secretKeys && k != URLS_KEY && v.contains(q, true) }
        }
        val listMode = searching || vaultFilter != ""
        val shown = if (!listMode) emptyList() else pool.filter { (searching || inTrash || inSection(it)) && (!searching || matchQ(it)) }
            .sortedWith(compareByDescending<Entry> { !inTrash && it.favorite }.then(sortCmp()))
        val noFolder = active.filter { Folders.nameOf(it.category, folders) == null }
            .sortedWith(compareByDescending<Entry> { it.favorite }.then(sortCmp()))
        val typeRows = listOf(
            Triple("t:login", "دخول", active.count { it.type == "login" }),
            Triple("t:it", "IT (أجهزة وشبكات)", active.count { EntryTypes.isIt(it.type) && it.type != "ssh" }),
            Triple("t:note", "ملاحظات سرية", active.count { it.type == "note" }),
            Triple("t:card", "كروت", active.count { it.type == "card" }),
            Triple("t:identity", "هويات", active.count { it.type == "identity" }),
            Triple("t:ssh", "مفاتيح SSH", active.count { it.type == "ssh" }),
        )
        val title = when {
            vaultFilter == "all" -> "كل العناصر"
            vaultFilter == "fav" -> "المفضلة"
            vaultFilter == "trash" -> "المهملات"
            vaultFilter.startsWith("t:") -> typeRows.firstOrNull { it.first == vaultFilter }?.second ?: "الأنواع"
            vaultFilter.startsWith(Folders.PREFIX) -> "📂 " + (Folders.nameOf(vaultFilter, folders) ?: "فولدر")
            else -> "K2Pass"
        }
        Box(Modifier.fillMaxSize()) {
            if (folderDlg) {
                FolderDialog(
                    folders = folders,
                    counts = active.groupBy { it.category }.mapValues { it.value.size },
                    favCount = active.count { it.favorite },
                    trashCount = trashed.size,
                    totalCount = active.size,
                    filter = vaultFilter, onFilter = { vaultFilter = it },
                    onCreate = { createFolder(it) }, onRename = { f, n -> renameFolder(f, n) },
                    onDelete = { f -> if (vaultFilter == Folders.catOf(f)) vaultFilter = ""; deleteFolder(f) },
                    onClose = { folderDlg = false },
                )
            }
            if (bulk == "add") {
                val cands = active.filter { it.category != vaultFilter }
                    .sortedWith(compareBy<Entry> { Folders.nameOf(it.category, folders) != null }.thenBy { it.title.lowercase() })
                BulkPickDialog(
                    title = "إضافة عناصر للفولدر", confirmLabel = "إضافة", items = cands,
                    hint = { e -> Folders.nameOf(e.category, folders)?.let { "حاليًا في: $it" } ?: "بدون فولدر" },
                    onConfirm = { ids -> moveToCategory(ids, vaultFilter) }, onClose = { bulk = null },
                )
            }
            if (bulk == "remove") {
                BulkPickDialog(
                    title = "إزالة عناصر من الفولدر", confirmLabel = "إزالة", items = shown,
                    hint = { e -> e.username },
                    onConfirm = { ids -> moveToCategory(ids, "other") }, onClose = { bulk = null },
                )
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vaultFilter != "") {
                            IconButton(onClick = { vaultFilter = "" }) { Icon(K2Icons.Back, contentDescription = "رجوع", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            LogoMark(44.dp); Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            Text("${active.size} عنصر", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { lockNow() }) { Icon(K2Icons.LockClosed, contentDescription = "قفل", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                item {
                    OutlinedTextField(
                        colors = k2FieldColors(),
                        value = q, onValueChange = { q = it },
                        placeholder = { Text("ابحث في حساباتك") },
                        leadingIcon = { Icon(K2Icons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        singleLine = true, shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!listMode && !backupHidden && active.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val last = prefs.lastExportMs
                    val days = if (last == 0L) -1L else (now - last) / 86_400_000L
                    if ((last == 0L || days >= 14) && now > prefs.backupSnoozeUntilMs) {
                        item {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("نسخة احتياطية", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        if (last == 0L) "لسه ما صدّرتش نسخة مشفرة. لو الموبايل ضاع هتخسر كل الباسوردات."
                                        else "آخر نسخة احتياطية من $days يوم.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {
                                            backupHidden = true
                                            exportLauncher.launch(exportName())
                                        }) { Text("صدّر دلوقتي") }
                                        TextButton(onClick = { prefs.backupSnoozeUntilMs = now + 7 * 86_400_000L; backupHidden = true }) { Text("بعد أسبوع") }
                                    }
                                }
                            }
                        }
                    }
                }
                if (inTrash && !searching) {
                    item {
                        Text(
                            "العناصر هنا بتتمسح نهائيًا بعد 30 يوم. اضغط على عنصر للاسترجاع أو الحذف.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (listMode && shown.size > 1) item { Row { Spacer(Modifier.weight(1f)); SortButton() } }
                if (listMode && !searching && vaultFilter.startsWith(Folders.PREFIX)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { bulk = "add" }, modifier = Modifier.weight(1f)) { Text("+ إضافة عناصر") }
                            OutlinedButton(enabled = shown.isNotEmpty(), onClick = { bulk = "remove" }, modifier = Modifier.weight(1f)) { Text("إزالة عناصر") }
                        }
                    }
                }
                if (listMode) {
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                when {
                                    searching -> "مفيش نتائج"
                                    inTrash -> "سلة المهملات فاضية"
                                    else -> "مفيش عناصر هنا"
                                },
                                Modifier.fillMaxWidth().padding(top = 40.dp), textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(shown, key = { it.id }) { e -> EntryCard(e, inTrash) }
                } else {
                    if (rep.total > 0) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                                    .background(Brush.linearGradient(listOf(Color(0xFF4A3FB5), Color(0xFF1E6FA0))))
                                    .clickable { screen = Screen.Health }.padding(14.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("صحة الباسوردات", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        StatTile("ضعيفة", rep.weak.size)
                                        StatTile("مكررة", rep.reused.sumOf { it.size })
                                        StatTile("قديمة", rep.old.size)
                                    }
                                }
                            }
                        }
                    }
                    item { SectionHeader("الأنواع") }
                    item {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column {
                                SectionRow("📋", "كل العناصر", active.size) { vaultFilter = "all" }
                                SectionRow("⭐", "المفضلة", active.count { it.favorite }) { vaultFilter = "fav" }
                                for ((k, l, n) in typeRows) SectionRow(mapOf("t:login" to "🔑", "t:it" to "🖥", "t:note" to "📝", "t:card" to "💳", "t:identity" to "🪪", "t:ssh" to "🔐")[k] ?: "•", l, n) { vaultFilter = k }
                                SectionRow("🗑", "المهملات", trashed.size) { vaultFilter = "trash" }
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionHeader("الفولدرات (${folders.size})") }
                            TextButton(onClick = { folderDlg = true }) { Text("إدارة / جديد") }
                        }
                    }
                    if (folders.isNotEmpty()) {
                        item {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column {
                                    for (f in folders) {
                                        val key = Folders.catOf(f)
                                        SectionRow("📂", f.title, active.count { it.category == key }) { vaultFilter = key }
                                    }
                                }
                            }
                        }
                    }
                    item { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { SectionHeader("بدون فولدر (${noFolder.size})") }; if (noFolder.size > 1) SortButton() } }
                    if (active.isEmpty()) {
                        item {
                            Text(
                                "الخزنة فاضية.\nاضغط + في النص وضيف أول عنصر، أو استورد باسوردات من Bitwarden أو أي ملف.",
                                Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            OutlinedButton(onClick = { importFileLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                                Text("⬇️ استيراد من ملف (Bitwarden / CSV / خزنة)")
                            }
                        }
                    }
                    items(noFolder, key = { it.id }) { e -> EntryCard(e, false) }
                }
            }
        }
    }

    private fun sortCmp(): Comparator<Entry> =
        if (vaultSort == 1) compareByDescending<Entry> { it.updatedAt } else compareBy<Entry> { it.title.lowercase() }

    @Composable
    private fun SortButton() {
        TextButton(onClick = { vaultSort = 1 - vaultSort }) { Text(if (vaultSort == 0) "↕ الترتيب: الاسم" else "↕ الترتيب: آخر تعديل") }
    }

    @Composable
    private fun SectionHeader(text: String) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }

    @Composable
    private fun SectionRow(emoji: String, label: String, n: Int, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(emoji) }
            Spacer(Modifier.width(12.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("$n", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text("  ‹", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        val info = EntryTypes.of(e.type)
        GlassCard(
            Modifier.fillMaxWidth(),
            onClick = { if (inTrash) trashTarget = e else screen = Screen.View(e) },
        ) {
            Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.type == "login") Avatar(e.title) else IconTile(info.icon, info.color)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("OTP") }
                        }
                        if (info.hasUser && e.username.isNotBlank()) {
                            IconButton(onClick = { copySensitive(ctx, "username", e.username); message = "اتنسخ اسم المستخدم" }, modifier = Modifier.size(40.dp)) {
                                Icon(K2Icons.Person, contentDescription = "نسخ اسم المستخدم", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                        IconButton(onClick = { copySensitive(ctx, "password", e.password); message = "اتنسخ الباسورد" }, modifier = Modifier.size(40.dp)) {
                            Icon(K2Icons.Key, contentDescription = "نسخ الباسورد", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = { toggleFavorite(e) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (e.favorite) K2Icons.Star else K2Icons.StarOutline,
                            contentDescription = "مفضل",
                            tint = if (e.favorite) Color(0xFFFFC76B) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(10.dp))
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
                    Tab.Generator -> GeneratorScreen(onCreate = { mode, v ->
                        val base = Entry(id = "", title = "", username = if (mode == 2) v else "", password = if (mode == 2) "" else v, updatedAt = 0L)
                        screen = Screen.Edit(null, "login", base)
                    })
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
        Surface(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            shape = RoundedCornerShape(30.dp),
            color = Color(0xFF161D3A).copy(alpha = 0.96f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
            shadowElevation = 12.dp,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TabItem(Tab.Vault, K2Icons.Vault, "الخزنة")
                TabItem(Tab.Generator, K2Icons.Sync, "المولّد")
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(BrandBrush).clickable { addDialog = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(K2Icons.Add, contentDescription = "إضافة", tint = Color(0xFF0E1330), modifier = Modifier.size(30.dp)) }
                }
                TabItem(Tab.Tools, K2Icons.Tools, "الأدوات")
                TabItem(Tab.Settings, K2Icons.Settings, "الإعدادات")
            }
        }
    }

    @Composable
    private fun RowScope.TabItem(t: Tab, icon: ImageVector, label: String) {
        val sel = tab == t
        val tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { tab = t }.padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.background(
                    if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    RoundedCornerShape(16.dp),
                ).padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
            Text(
                label, style = MaterialTheme.typography.labelSmall, color = tint,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }

    @Composable
    private fun AddTypeDialog() {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("إضافة عنصر") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (row in EntryTypes.all.chunked(3)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (t in row) {
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                        .background(Color.White.copy(alpha = 0.06f))
                                        .clickable { addDialog = false; screen = Screen.Edit(null, t.id) }
                                        .padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    IconTile(t.icon, t.color, 44.dp)
                                    Text(t.short, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addDialog = false }) { Text("إلغاء") } },
        )
    }

    @Composable
    private fun RowScope.StatTile(label: String, n: Int) {
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.14f)).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$n", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
        }
    }

    @Composable
    private fun ToolsScreen() {
        val ctx = LocalContext.current
        val withTotp = entries.filter { it.trashedAt == 0L && !it.totpSecret.isNullOrBlank() }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("الأدوات", style = MaterialTheme.typography.titleLarge)

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
                OutlinedButton(onClick = { screen = Screen.Health }) { Text("فتح التقرير") }
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
                    exportLauncher.launch(exportName())
                }) { Text("تصدير نسخة مشفرة") }
            }

            LanSyncSection(rawVault = { try { store.rawJson() } catch (_: Exception) { null } }, prefs = prefs)
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
        var pwOpen by remember { mutableStateOf(false) }
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
                Text("الحالة: " + if (autofillOn) "مُفعّل" else "غير مُفعّل")
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
                    colors = k2FieldColors(),
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
                OutlinedButton(onClick = { pwOpen = !pwOpen }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (pwOpen) "إخفاء تغيير الماستر باسورد" else "تغيير الماستر باسورد")
                }
                if (pwOpen) {
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
            }

            UpdateSection()

            Text(
                "K2Pass — الخزنة أوفلاين بالكامل، والإنترنت لزر التحديث فقط",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
