package com.shawady.vault.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** A form field stored in `Entry.extra` under [key] (card / identity / IT types). */
class Field(
    val key: String,
    val label: String,
    val secret: Boolean = false,
    val numeric: Boolean = false,
    val multiline: Boolean = false,
)

/**
 * Registry of item types. Types are plain strings stored in `Entry.type`; type-specific data lives in
 * `Entry.extra` (no vault format change). Unknown types fall back to "login".
 *  - [creds]: the type uses the username / password fields of the entry.
 *  - [hasUser]: show the username field (Wi-Fi has only a password).
 */
class TypeInfo(
    val id: String,
    val label: String,
    val short: String,
    val icon: ImageVector,
    val color: Color,
    val fields: List<Field> = emptyList(),
    val creds: Boolean = false,
    val hasUser: Boolean = true,
    val isIt: Boolean = false,
    val passLabel: String = "الباسورد",
)

object EntryTypes {
    private val itCommon = listOf(
        Field("host", "IP / Hostname"),
        Field("mgmt", "رابط الإدارة (https://...)"),
        Field("vendor", "الشركة المصنعة"),
        Field("model", "الموديل"),
        Field("location", "المكان / الفرع"),
        Field("related", "حسابات مرتبطة (أسماء)"),
    )

    val all: List<TypeInfo> = listOf(
        TypeInfo("login", "تسجيل دخول", "دخول", K2Icons.Key, Color(0xFFA99BFF), creds = true),
        TypeInfo("device", "جهاز (كمبيوتر / لابتوب)", "جهاز", K2Icons.Laptop, Color(0xFF6FA8FF), itCommon, creds = true, isIt = true),
        TypeInfo("server", "سيرفر (Windows / Linux)", "سيرفر", K2Icons.Server, Color(0xFF55CFFF), itCommon, creds = true, isIt = true),
        TypeInfo("router", "راوتر / MikroTik", "راوتر", K2Icons.Router, Color(0xFF5EE0C4), itCommon, creds = true, isIt = true),
        TypeInfo("switch", "سويتش", "سويتش", K2Icons.Switch, Color(0xFF8FA6FF), itCommon, creds = true, isIt = true),
        TypeInfo("firewall", "فايروول (Sophos ...)", "فايروول", K2Icons.Shield, Color(0xFFFF8A9B), itCommon, creds = true, isIt = true),
        TypeInfo("ad", "Active Directory", "AD", K2Icons.Business, Color(0xFFC39BFF), listOf(Field("domain", "الدومين")) + itCommon, creds = true, isIt = true),
        TypeInfo(
            "wifi", "Wi-Fi", "Wi-Fi", K2Icons.Wifi, Color(0xFF4FD1FF),
            listOf(Field("ssid", "اسم الشبكة (SSID)"), Field("security", "نوع الأمان (WPA2 / WPA3)"), Field("location", "المكان")),
            creds = true, hasUser = false, isIt = true, passLabel = "باسورد الواي فاي",
        ),
        TypeInfo(
            "ssh", "مفتاح SSH", "SSH", K2Icons.Key, Color(0xFF9BE15D),
            listOf(
                Field("host", "السيرفر / المستخدم (user@host)"),
                Field("privkey", "المفتاح الخاص (Private key)", secret = true, multiline = true),
                Field("pubkey", "المفتاح العام (Public key)", multiline = true),
                Field("fingerprint", "البصمة (SHA256)"),
                Field("passphrase", "باسفريز المفتاح", secret = true),
            ),
            isIt = true,
        ),
        TypeInfo("note", "ملاحظة سرية", "ملاحظة", K2Icons.Note, Color(0xFFFFC76B)),
        TypeInfo(
            "card", "كارت دفع", "كارت", K2Icons.Card, Color(0xFFFF9F7A),
            listOf(
                Field("holder", "اسم حامل الكارت"),
                Field("number", "رقم الكارت", secret = true, numeric = true),
                Field("exp", "تاريخ الانتهاء (MM/YY)"),
                Field("cvv", "CVV", secret = true, numeric = true),
            ),
        ),
        TypeInfo(
            "identity", "هوية", "هوية", K2Icons.Person, Color(0xFF7BE0A8),
            listOf(
                Field("name", "الاسم الكامل"),
                Field("email", "الإيميل"),
                Field("phone", "الموبايل"),
                Field("address", "العنوان", multiline = true),
                Field("idnum", "الرقم القومي / جواز", secret = true),
            ),
        ),
    )

    /** Unknown ids (e.g. from a newer app version) are shown as plain logins. */
    fun of(id: String): TypeInfo = all.firstOrNull { it.id == id } ?: all[0]
    fun hasCreds(id: String) = of(id).creds
    fun isIt(id: String) = of(id).isIt
}
