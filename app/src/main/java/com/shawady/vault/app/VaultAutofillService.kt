package com.shawady.vault.app

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.widget.RemoteViews

/**
 * Offers a single "open vault" suggestion whenever a login form is detected. Credentials are
 * never read here: the vault stays encrypted until the user authenticates in [AuthActivity].
 */
class VaultAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        val parsed = StructureParser.parse(structure)
        if (parsed.userId == null && parsed.passId == null) {
            callback.onSuccess(null)
            return
        }

        val intent = Intent(this, AuthActivity::class.java)
            .putExtra(EXTRA_USER_ID, parsed.userId)
            .putExtra(EXTRA_PASS_ID, parsed.passId)
            .putExtra(EXTRA_DOMAIN, parsed.domain?.ifBlank { null })
            .putExtra(EXTRA_PKG, parsed.pkg)

        // Must be mutable: the autofill framework adds its own extras to this intent.
        val sender = PendingIntent.getActivity(
            this,
            (System.nanoTime() and 0x7fffffff).toInt(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

        val ids = listOfNotNull(parsed.userId, parsed.passId).toTypedArray()
        val presentation = RemoteViews(packageName, R.layout.autofill_item)
        presentation.setTextViewText(R.id.text, "فتح الخزنة")

        @Suppress("DEPRECATION")
        val response = FillResponse.Builder().setAuthentication(ids, sender, presentation).build()
        callback.onSuccess(response)
    }

    // Saving new credentials from other apps is intentionally not supported (no SaveInfo is declared).
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    companion object {
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_PASS_ID = "pass_id"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_PKG = "pkg"
    }
}
