package com.shawady.vault.app

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.shawady.vault.core.VaultCrypto
import com.shawady.vault.core.VaultData

/**
 * Shown by the system when the user taps the autofill suggestion. Always requires the user
 * to authenticate (biometrics, or master password) before any credential is returned.
 * The unlocked key is wiped right after the datasets are built; this does NOT unlock the app.
 */
class AuthActivity : FragmentActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val userId = intent.getParcelableExtra<AutofillId>(VaultAutofillService.EXTRA_USER_ID)
        val passId = intent.getParcelableExtra<AutofillId>(VaultAutofillService.EXTRA_PASS_ID)
        val domain = intent.getStringExtra(VaultAutofillService.EXTRA_DOMAIN)
        val pkg = intent.getStringExtra(VaultAutofillService.EXTRA_PKG)
        val store = VaultStore(applicationContext)

        if (!store.exists() || (userId == null && passId == null)) {
            cancel()
            return
        }

        setContent {
            AppTheme {
                Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    UnlockScreen(
                        activity = this@AuthActivity,
                        store = store,
                        onUnlocked = { f, k -> finishWithMatches(f, k, userId, passId, domain, pkg) },
                    ) {
                        OutlinedButton(onClick = { cancel() }) { Text("إلغاء") }
                    }
                }
            }
        }
    }

    private fun finishWithMatches(
        f: VaultCrypto.VaultFile,
        k: ByteArray,
        userId: AutofillId?,
        passId: AutofillId?,
        domain: String?,
        pkg: String?,
    ) {
        val data = try {
            VaultData.fromBytes(VaultCrypto.readPayload(f, k))
        } catch (e: Exception) {
            null
        } finally {
            k.fill(0)
        }
        if (data == null) {
            cancel()
            return
        }
        val matches = data.entries.filter { !it.deleted && Matcher.matches(it, domain, pkg) }
        if (matches.isEmpty()) {
            cancel()
            return
        }
        val resp = FillResponse.Builder()
        for (e in matches) {
            val pres = RemoteViews(packageName, R.layout.autofill_item)
            pres.setTextViewText(R.id.text, e.title.ifBlank { e.username })
            @Suppress("DEPRECATION")
            val ds = Dataset.Builder(pres)
            if (userId != null) ds.setValue(userId, AutofillValue.forText(e.username))
            if (passId != null) ds.setValue(passId, AutofillValue.forText(e.password))
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

/** Finds username/password fields and the web domain / package from the screen structure. */
object StructureParser {
    class Parsed {
        var userId: AutofillId? = null
        var passId: AutofillId? = null
        var domain: String? = null
        var pkg: String? = null
        var lastTextId: AutofillId? = null
    }

    fun parse(s: AssistStructure): Parsed {
        val p = Parsed()
        p.pkg = s.activityComponent?.packageName
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode, p)
        return p
    }

    private fun walk(n: AssistStructure.ViewNode, p: Parsed) {
        val domain = n.webDomain
        if (!domain.isNullOrBlank() && p.domain == null) {
            // Never fill on plain-http pages.
            p.domain = if (n.webScheme == "http") "" else domain
        }
        val id = n.autofillId
        if (id != null && n.autofillType == android.view.View.AUTOFILL_TYPE_TEXT) {
            val hints = n.autofillHints?.toList().orEmpty()
            val variation = n.inputType and android.text.InputType.TYPE_MASK_VARIATION
            val isText = (n.inputType and android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT
            val isPass = android.view.View.AUTOFILL_HINT_PASSWORD in hints ||
                (isText && (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
            val isUser = android.view.View.AUTOFILL_HINT_USERNAME in hints ||
                android.view.View.AUTOFILL_HINT_EMAIL_ADDRESS in hints
            if (isPass) {
                if (p.passId == null) p.passId = id
                if (p.userId == null) p.userId = p.lastTextId
            } else if (isUser) {
                if (p.userId == null) p.userId = id
            } else if (isText) {
                p.lastTextId = id
            }
        }
        for (c in 0 until n.childCount) walk(n.getChildAt(c), p)
    }
}
