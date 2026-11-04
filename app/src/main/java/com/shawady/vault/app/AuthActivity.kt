package com.shawady.vault.app

import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.shawady.vault.core.Entry
import com.shawady.vault.core.Totp
import com.shawady.vault.core.VaultCrypto
import com.shawady.vault.core.VaultData

/**
 * Autofill screen. Always requires the user to authenticate (biometrics, PIN or master password)
 * before any credential is returned or saved.
 *  - Fill mode, accounts found -> returned for filling (TOTP code copied when a single account has one).
 *  - Fill mode, none found     -> "add account" page prefilled from the page; saving also fills the form.
 *  - Save mode ("save to K2Pass?" after a login) -> new account editor, or an "update password?" prompt
 *    when the same username already exists with a different password.
 * The vault key is wiped as soon as the work is done; this does NOT unlock the app itself.
 */
class AuthActivity : FragmentActivity() {

    private class Unlocked(val file: VaultCrypto.VaultFile, val key: ByteArray, val data: VaultData)

    private var unlocked by mutableStateOf<Unlocked?>(null)
    private var updateTarget by mutableStateOf<Entry?>(null)
    private var linkTarget by mutableStateOf<Entry?>(null)
    private val store by lazy { VaultStore(applicationContext) }
    private val prefs by lazy { AppPrefs(applicationContext) }

    private var userId: AutofillId? = null
    private var passId: AutofillId? = null
    private var confirmId: AutofillId? = null
    private var domain: String? = null
    private var pkg: String? = null
    private var userText: String? = null
    private var registration = false
    private var saveMode = false
    private var savePass = ""

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        saveMode = intent.getStringExtra(VaultAutofillService.EXTRA_MODE) == VaultAutofillService.MODE_SAVE
        if (saveMode) {
            val ps = PendingSave.take()
            if (ps == null || !store.exists()) {
                cancel()
                return
            }
            domain = ps.domain
            pkg = ps.pkg
            userText = ps.user
            savePass = ps.pass
        } else {
            userId = intent.getParcelableExtra<AutofillId>(VaultAutofillService.EXTRA_USER_ID)
            passId = intent.getParcelableExtra<AutofillId>(VaultAutofillService.EXTRA_PASS_ID)
            confirmId = intent.getParcelableExtra<AutofillId>(VaultAutofillService.EXTRA_CONFIRM_ID)
            domain = intent.getStringExtra(VaultAutofillService.EXTRA_DOMAIN)
            pkg = intent.getStringExtra(VaultAutofillService.EXTRA_PKG)
            userText = intent.getStringExtra(VaultAutofillService.EXTRA_USER_TEXT)
            registration = intent.getBooleanExtra(VaultAutofillService.EXTRA_REG, false)
            if (!store.exists() || (userId == null && passId == null)) {
                cancel()
                return
            }
        }

        setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    val u = unlocked
                    if (u == null) {
                        UnlockScreen(
                            activity = this@AuthActivity,
                            store = store,
                            onUnlocked = { f, k -> onVaultUnlocked(f, k) },
                        ) {
                            OutlinedButton(onClick = { cancel() }) { Text("إلغاء") }
                        }
                    } else if (saveMode && updateTarget != null) {
                        UpdatePrompt(u, updateTarget!!)
                    } else if (saveMode && linkTarget != null) {
                        LinkPrompt(u, linkTarget!!)
                    } else {
                        EntryEditor(
                            heading = when {
                                saveMode -> "حفظ الحساب في K2Pass؟"
                                registration -> "حساب جديد على الموقع ده"
                                else -> "مفيش حساب محفوظ هنا — أضف حساب"
                            },
                            orig = null,
                            prefill = buildPrefill(),
                            autoGenerate = registration && !saveMode,
                            allowTypes = false,
                            saveLabel = if (saveMode) "حفظ" else "حفظ وتعبئة",
                            onSave = { e -> if (saveMode) saveOnly(u, e) else saveAndFill(u, e) },
                            onCancel = { cancel() },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        unlocked?.key?.fill(0)
        unlocked = null
        super.onDestroy()
    }

    private fun newIdentifier(): String? = when {
        pkg != null -> "androidapp://$pkg"
        domain != null -> "https://$domain"
        else -> null
    }

    @androidx.compose.runtime.Composable
    private fun LinkPrompt(u: Unlocked, target: Entry) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ربط بالحساب الموجود؟", style = MaterialTheme.typography.titleLarge)
            Text("\"${target.title}\" (${target.username}) محفوظ بنفس اليوزر والباسورد. تربط ${newIdentifier()} بيه بدل ما تعمل نسخة مكررة؟")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val id = newIdentifier() ?: return@Button
                    val urls = ((target.extra[URLS_KEY] ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() } + id).distinct().joinToString("\n")
                    val ne = target.copy(extra = target.extra + (URLS_KEY to urls), updatedAt = System.currentTimeMillis())
                    if (persist(u, VaultData(u.data.entries.filter { it.id != target.id } + ne))) {
                        Toast.makeText(this@AuthActivity, "اتربط بالحساب الموجود", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        cancel()
                    }
                }) { Text("ربط") }
                OutlinedButton(onClick = { linkTarget = null }) { Text("حفظ كعنصر جديد") }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun UpdatePrompt(u: Unlocked, target: Entry) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("تحديث الباسورد؟", style = MaterialTheme.typography.titleLarge)
            Text("\"${target.title}\" (${target.username}) محفوظ بباسورد مختلف. تحدّثه للباسورد الجديد؟ القديم هيفضل في السجل.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val hist = (listOf(target.copy(history = emptyList()).withoutVoice()) + target.history).take(20)
                    val ne = target.copy(password = savePass, updatedAt = System.currentTimeMillis(), history = hist)
                    if (persist(u, VaultData(u.data.entries.filter { it.id != target.id } + ne))) {
                        Toast.makeText(this@AuthActivity, "اتحدّث الباسورد", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        cancel()
                    }
                }) { Text("تحديث") }
                OutlinedButton(onClick = { cancel() }) { Text("لأ") }
            }
        }
    }

    private fun onVaultUnlocked(f: VaultCrypto.VaultFile, k: ByteArray) {
        val data = try {
            VaultData.fromBytes(VaultCrypto.readPayload(f, k))
        } catch (e: Exception) {
            k.fill(0)
            cancel()
            return
        }
        val matches = data.entries.filter { Matcher.matches(it, domain, pkg) }
        if (saveMode) {
            val same = matches.firstOrNull { userText.orEmpty().isNotEmpty() && it.username.equals(userText, true) }
            when {
                same != null && same.password == savePass -> {
                    k.fill(0)
                    Toast.makeText(this, "محفوظ بالفعل في K2Pass", Toast.LENGTH_SHORT).show()
                    cancel()
                }
                same != null -> {
                    updateTarget = same
                    unlocked = Unlocked(f, k, data)
                }
                else -> {
                    // Same account saved under another site/app? Offer to link instead of duplicating.
                    val twin = if (userText.orEmpty().isNotEmpty())
                        data.entries.firstOrNull { it.type == "login" && !it.deleted && it.trashedAt == 0L &&
                            it.username.equals(userText, true) && it.password == savePass } else null
                    if (twin != null && newIdentifier() != null) linkTarget = twin
                    unlocked = Unlocked(f, k, data)
                }
            }
            return
        }
        if (matches.isNotEmpty()) {
            k.fill(0)
            respond(matches)
        } else {
            unlocked = Unlocked(f, k, data)
        }
    }

    /** Writes [newData] with the open key, wipes the key, and tells the app (if open) to reload. */
    private fun persist(u: Unlocked, newData: VaultData): Boolean {
        return try {
            val nf = VaultCrypto.writePayload(u.file, u.key, newData.toBytes())
            store.save(nf)
            Session.reloadIfOpen(nf)
            true
        } catch (e: Exception) {
            false
        } finally {
            u.key.fill(0)
            unlocked = null
        }
    }

    private fun saveOnly(u: Unlocked, entry: Entry) {
        if (persist(u, VaultData(u.data.entries + entry))) {
            Toast.makeText(this, "اتحفظ في K2Pass", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else {
            cancel()
        }
    }

    private fun saveAndFill(u: Unlocked, entry: Entry) {
        if (persist(u, VaultData(u.data.entries + entry))) respond(listOf(entry)) else cancel()
    }

    private fun buildPrefill(): Entry {
        val site = domain?.takeIf { it.isNotBlank() }?.let { baseDomain(it) }
        val title = if (site != null) siteName(site) else appLabel(pkg)
        val url = if (site != null) "https://$site" else "androidapp://${pkg.orEmpty()}"
        return Entry(
            id = "", title = title, username = userText.orEmpty(), password = if (saveMode) savePass else "",
            url = url, notes = "", totpSecret = null, updatedAt = 0L,
        )
    }

    private fun appLabel(p: String?): String {
        if (p == null) return ""
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
        } catch (e: Exception) {
            p.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun respond(list: List<Entry>) {
        val resp = FillResponse.Builder()
        for (e in list) {
            val pres = RemoteViews(packageName, R.layout.autofill_item)
            pres.setTextViewText(R.id.text, e.title.ifBlank { e.username })
            @Suppress("DEPRECATION")
            val ds = Dataset.Builder(pres)
            userId?.let { ds.setValue(it, AutofillValue.forText(e.username)) }
            passId?.let { ds.setValue(it, AutofillValue.forText(e.password)) }
            confirmId?.let { ds.setValue(it, AutofillValue.forText(e.password)) }
            resp.addDataset(ds.build())
        }
        VaultAutofillService.saveInfo(userId, passId)?.takeIf { !prefs.isBlocked(domain, pkg) }?.let { resp.setSaveInfo(it) }

        // One matching account with 2FA: put the current code on the clipboard, ready to paste.
        if (list.size == 1 && prefs.copyTotp) {
            list[0].totpSecret?.takeIf { it.isNotBlank() }?.let { secret ->
                Totp.generate(secret)?.let { copySensitive(this, "totp", it.code) }
            }
        }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, resp.build()))
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
