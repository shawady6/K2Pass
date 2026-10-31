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
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.shawady.vault.core.Entry
import com.shawady.vault.core.VaultCrypto
import com.shawady.vault.core.VaultData

/**
 * Shown by the system when the user taps the autofill suggestion. Always requires the user to
 * authenticate (biometrics, or master password) before any credential is returned.
 *  - Matching accounts found  -> they are returned for filling.
 *  - No account for this site/app -> an "add account" page opens, prefilled from the page
 *    (site name, url, any typed username, a generated password on sign-up forms). Saving it
 *    stores the account and fills the form with it.
 * The vault key is wiped as soon as the response is built; this does NOT unlock the app itself.
 */
class AuthActivity : FragmentActivity() {

    private class Unlocked(val file: VaultCrypto.VaultFile, val key: ByteArray, val data: VaultData)

    private var unlocked by mutableStateOf<Unlocked?>(null)
    private val store by lazy { VaultStore(applicationContext) }

    private var userId: AutofillId? = null
    private var passId: AutofillId? = null
    private var confirmId: AutofillId? = null
    private var domain: String? = null
    private var pkg: String? = null
    private var userText: String? = null
    private var registration = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

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
                    } else {
                        EntryEditor(
                            heading = if (registration) "حساب جديد على الموقع ده" else "مفيش حساب محفوظ هنا — أضف حساب",
                            orig = null,
                            prefill = buildPrefill(),
                            autoGenerate = registration,
                            saveLabel = "حفظ وتعبئة",
                            onSave = { e -> saveAndFill(u, e) },
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

    private fun onVaultUnlocked(f: VaultCrypto.VaultFile, k: ByteArray) {
        val data = try {
            VaultData.fromBytes(VaultCrypto.readPayload(f, k))
        } catch (e: Exception) {
            k.fill(0)
            cancel()
            return
        }
        val matches = data.entries.filter { !it.deleted && Matcher.matches(it, domain, pkg) }
        if (matches.isNotEmpty()) {
            k.fill(0)
            respond(matches)
        } else {
            unlocked = Unlocked(f, k, data)
        }
    }

    private fun saveAndFill(u: Unlocked, entry: Entry) {
        try {
            val newData = VaultData(u.data.entries + entry)
            val nf = VaultCrypto.writePayload(u.file, u.key, newData.toBytes())
            store.save(nf)
            Session.reloadIfOpen(nf)
        } catch (e: Exception) {
            cancel()
            return
        } finally {
            u.key.fill(0)
        }
        unlocked = null
        respond(listOf(entry))
    }

    private fun buildPrefill(): Entry {
        val site = domain?.takeIf { it.isNotBlank() }?.let { baseDomain(it) }
        val title = if (site != null) siteName(site) else appLabel(pkg)
        val url = if (site != null) "https://$site" else "androidapp://${pkg.orEmpty()}"
        return Entry(
            id = "", title = title, username = userText.orEmpty(), password = "",
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
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, resp.build()))
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
