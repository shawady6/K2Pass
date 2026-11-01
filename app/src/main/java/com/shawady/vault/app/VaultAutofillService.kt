package com.shawady.vault.app

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId

/**
 * Offers a single "open K2Pass" suggestion whenever a login / sign-up form is detected, and asks
 * "save to K2Pass?" after a login. Credentials are never read here: the vault stays encrypted until
 * the user authenticates in [AuthActivity].
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
        if (AppPrefs(this).isBlocked(parsed.domain, parsed.pkg)) {
            callback.onSuccess(null)
            return
        }

        val intent = Intent(this, AuthActivity::class.java)
            .putExtra(EXTRA_USER_ID, parsed.userId)
            .putExtra(EXTRA_PASS_ID, parsed.passId)
            .putExtra(EXTRA_CONFIRM_ID, parsed.confirmId)
            .putExtra(EXTRA_DOMAIN, parsed.domain?.ifBlank { null })
            .putExtra(EXTRA_PKG, parsed.pkg)
            .putExtra(EXTRA_USER_TEXT, parsed.userText)
            .putExtra(EXTRA_REG, parsed.registration)

        // Must be mutable: the autofill framework adds its own extras to this intent.
        val sender = PendingIntent.getActivity(
            this,
            (System.nanoTime() and 0x7fffffff).toInt(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

        val ids = listOfNotNull(parsed.userId, parsed.passId, parsed.confirmId).toTypedArray()
        val presentation = android.widget.RemoteViews(packageName, R.layout.autofill_item)
        presentation.setTextViewText(R.id.text, if (parsed.registration) "K2Pass — حساب جديد" else "فتح K2Pass")

        @Suppress("DEPRECATION")
        val builder = FillResponse.Builder().setAuthentication(ids, sender, presentation)
        saveInfo(parsed.userId, parsed.passId)?.let { builder.setSaveInfo(it) }
        callback.onSuccess(builder.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null) {
                callback.onSuccess()
                return
            }
            val p = StructureParser.parse(structure)
            val pass = p.passText.orEmpty()
            if (pass.isEmpty() || AppPrefs(this).isBlocked(p.domain, p.pkg)) {
                callback.onSuccess()
                return
            }
            PendingSave.put(
                PendingSave.Data(p.domain?.ifBlank { null }, p.pkg, p.userText.orEmpty().trim(), pass, System.currentTimeMillis()),
            )
            val intent = Intent(this, AuthActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_SAVE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val sender = PendingIntent.getActivity(
                this,
                (System.nanoTime() and 0x7fffffff).toInt(),
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).intentSender
            callback.onSuccess(sender)
        } catch (e: Exception) {
            callback.onFailure(null)
        }
    }

    companion object {
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_PASS_ID = "pass_id"
        const val EXTRA_CONFIRM_ID = "confirm_id"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_USER_TEXT = "user_text"
        const val EXTRA_REG = "registration"
        const val EXTRA_MODE = "mode"
        const val MODE_SAVE = "save"

        /** Asks the system to offer "save to K2Pass?" once the password field is submitted. */
        fun saveInfo(userId: AutofillId?, passId: AutofillId?): SaveInfo? {
            if (passId == null) return null
            val b = SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(passId))
            if (userId != null) b.setOptionalIds(arrayOf(userId))
            b.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            return b.build()
        }
    }
}
