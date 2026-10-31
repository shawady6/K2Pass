package com.shawady.vault.app

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/**
 * Recognises login / sign-up fields from the screen structure the system hands to the autofill service.
 * Signals used, strongest first: autofill hints, HTML attributes (type / autocomplete / name / id),
 * input type, then keywords in resource id / hint / content description (English + Arabic).
 * A second password field, or a "new-password" hint, marks the form as a registration form.
 */
object StructureParser {

    class Parsed {
        var userId: AutofillId? = null
        var passId: AutofillId? = null
        var confirmId: AutofillId? = null
        var domain: String? = null
        var pkg: String? = null
        var userText: String? = null
        var registration = false
    }

    private class F(
        val id: AutofillId,
        val pass: Boolean,
        val newPass: Boolean,
        val user: Boolean,
        val text: String?,
    )

    private val userWords = listOf(
        "user", "login", "email", "e-mail", "mail", "account", "phone", "mobile", "identifier",
        "اسم المستخدم", "المستخدم", "البريد", "الايميل", "الإيميل", "الهاتف", "الموبايل", "رقم", "حساب",
    )
    private val passWords = listOf("password", "passwd", "pwd", "passcode", "كلمة المرور", "كلمة السر", "الرقم السري", "باسورد")
    private val skipWords = listOf("search", "query", "بحث", "captcha", "coupon", "promo")

    fun parse(s: AssistStructure): Parsed {
        val p = Parsed()
        p.pkg = s.activityComponent?.packageName
        val fields = ArrayList<F>()
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode, p, fields)

        val firstPass = fields.indexOfFirst { it.pass }
        if (firstPass >= 0) {
            p.passId = fields[firstPass].id
            val second = fields.drop(firstPass + 1).firstOrNull { it.pass }
            p.confirmId = second?.id
            p.registration = second != null || fields[firstPass].newPass
            val before = fields.subList(0, firstPass)
            val u = before.lastOrNull { it.user } ?: before.lastOrNull()
            p.userId = u?.id
            p.userText = u?.text
        } else {
            val u = fields.firstOrNull { it.user }
            p.userId = u?.id
            p.userText = u?.text
        }
        return p
    }

    private fun walk(n: AssistStructure.ViewNode, p: Parsed, out: MutableList<F>) {
        // Hidden nodes are ignored so credentials are never poured into invisible (honeypot) fields.
        if (n.visibility != View.VISIBLE) return

        val d = n.webDomain
        if (!d.isNullOrBlank() && p.domain == null) {
            // Never fill on plain-http pages ("" disables web matching).
            p.domain = if (n.webScheme == "http") "" else d
        }

        val id = n.autofillId
        if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT) classify(n, id)?.let { out.add(it) }

        for (c in 0 until n.childCount) walk(n.getChildAt(c), p, out)
    }

    private fun classify(n: AssistStructure.ViewNode, id: AutofillId): F? {
        val hints = n.autofillHints?.map { it.lowercase() }.orEmpty()
        val attrs = HashMap<String, String>()
        n.htmlInfo?.attributes?.forEach { attrs[it.first.lowercase()] = it.second.lowercase() }

        val auto = (attrs["autocomplete"] ?: "") + " " + hints.joinToString(" ")
        val sig = listOf(n.idEntry, n.hint, attrs["name"], attrs["id"], attrs["placeholder"], attrs["aria-label"], n.contentDescription?.toString())
            .filterNotNull().joinToString(" ").lowercase()

        val inputType = n.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isText = cls == InputType.TYPE_CLASS_TEXT
        val multiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        if (multiLine || skipWords.any { it in sig }) return null

        val passVariation = isText && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
        val isPass = attrs["type"] == "password" || "password" in auto || passVariation || passWords.any { it in sig }
        val newPass = isPass && ("new" in auto || "new-password" in sig || "newpassword" in sig || "new_password" in sig || "confirm" in sig)

        val emailVariation = isText && (
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
        val isUser = !isPass && (
            "username" in auto || "email" in auto || "tel" in auto || "phone" in auto ||
                attrs["type"] == "email" || attrs["type"] == "tel" || emailVariation ||
                cls == InputType.TYPE_CLASS_PHONE || userWords.any { it in sig }
            )

        val text = n.autofillValue?.takeIf { it.isText }?.textValue?.toString()
        return F(id, isPass, newPass, isUser, text)
    }
}
