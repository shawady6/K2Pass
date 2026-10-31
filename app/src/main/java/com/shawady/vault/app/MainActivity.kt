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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.shawady.vault.core.BiometricUnlock
import com.shawady.vault.core.Entry
import com.shawady.vault.core.VaultCrypto
import com.shawady.vault.core.VaultData
import com.shawady.vault.core.VaultMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Setup : Screen
    data object Unlock : Screen
    data object Vault : Screen
    data class Edit(val entry: Entry?) : Screen
    data object Prefs : Screen
}

class MainActivity : FragmentActivity() {

    private val store by lazy { VaultStore(applicationContext) }

    private var screen by mutableStateOf<Screen>(Screen.Unlock)
    private var entries by mutableStateOf<List<Entry>>(emptyList())
    private var message by mutableStateOf<String?>(null)
    private var pendingRemote by mutableStateOf<VaultCrypto.VaultFile?>(null)
    private var resumeTick by mutableStateOf(0)
    private var backgroundedAt = 0L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
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
            SystemClock.elapsedRealtime() - backgroundedAt > AUTO_LOCK_MS
        ) {
            lockNow()
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
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

    private fun deleteEntry(e: Entry) {
        saveEntry(
            e.copy(
                username = "", password = "", notes = "", totpSecret = null,
                deleted = true, updatedAt = System.currentTimeMillis(), history = emptyList(),
            ),
        )
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
        BackHandler(enabled = screen is Screen.Edit || screen is Screen.Prefs) { screen = Screen.Vault }
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
                                refreshEntries()
                                screen = Screen.Vault
                            } catch (e: Exception) {
                                k.fill(0)
                                message = "الملف تالف أو غير متوافق"
                            }
                        },
                    )
                    Screen.Vault -> VaultScreen()
                    is Screen.Edit -> EntryEditor(
                        heading = if (s.entry == null) "حساب جديد" else "تعديل الحساب",
                        orig = s.entry,
                        onSave = { saveEntry(it) },
                        onCancel = { screen = Screen.Vault },
                        onDelete = s.entry?.let { e -> { deleteEntry(e) } },
                    )
                    Screen.Prefs -> PrefsScreen()
                }
            }
            pendingRemote?.let { RemotePasswordDialog(it) }
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
        val shown = entries.filter {
            (cat == null || it.category == cat) &&
                (q.isBlank() || it.title.contains(q, true) || it.username.contains(q, true) || it.url.contains(q, true))
        }
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("K2Pass", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "${entries.size} حساب", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { screen = Screen.Prefs }) { Text("إعدادات") }
                    TextButton(onClick = { lockNow() }) { Text("🔒 قفل") }
                }
                OutlinedTextField(
                    value = q, onValueChange = { q = it },
                    placeholder = { Text("ابحث في حساباتك") },
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                CategoryChips(selected = cat, onSelect = { cat = it }, withAll = true)
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            if (entries.isEmpty()) "الخزنة فاضية.\nاضغط \"+ حساب جديد\" وضيف أول حساب" else "مفيش نتائج",
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                        items(shown, key = { it.id }) { e -> EntryCard(e) }
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { screen = Screen.Edit(null) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Text("+ حساب جديد") }
        }
    }

    @Composable
    private fun EntryCard(e: Entry) {
        Card(
            Modifier.fillMaxWidth().clickable { screen = Screen.Edit(e) },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(e.title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        e.username.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(Categories.of(e.category).emoji)
            }
        }
    }

    @Composable
    private fun PrefsScreen() {
        var bioOn by remember { mutableStateOf(store.loadBlob() != null) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الإعدادات", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { screen = Screen.Vault }) { Text("رجوع") }
            }

            Section("الملء التلقائي") {
                Text("الحالة: " + if (autofillOn) "مُفعّل ✅" else "غير مُفعّل")
                if (!autofillOn) {
                    Button(onClick = {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:$packageName")),
                        )
                    }) { Text("تفعيل الملء التلقائي") }
                }
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

            Section("المزامنة اليدوية") {
                Text("١) استورد ودمج ملف الجهاز التاني  ٢) صدّر الملف المدموج وانقله له.")
                OutlinedButton(onClick = { mergeLauncher.launch(arrayOf("*/*")) }) { Text("استيراد ودمج ملف") }
                OutlinedButton(onClick = {
                    exportLauncher.launch("k2pass-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".vault")
                }) { Text("تصدير نسخة مشفرة") }
            }

            Text(
                "K2Pass 0.2 — أوفلاين بالكامل",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    private companion object {
        const val AUTO_LOCK_MS = 60_000L
    }
}
