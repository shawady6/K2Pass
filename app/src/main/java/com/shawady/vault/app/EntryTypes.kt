package com.shawady.vault.app

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
    val emoji: String,
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
        TypeInfo("login", "تسجيل دخول", "🔑", creds = true),
        TypeInfo("device", "جهاز (كمبيوتر / لابتوب)", "💻", itCommon, creds = true, isIt = true),
        TypeInfo("server", "سيرفر (Windows / Linux)", "🖥️", itCommon, creds = true, isIt = true),
        TypeInfo("router", "راوتر / MikroTik", "📡", itCommon, creds = true, isIt = true),
        TypeInfo("switch", "سويتش", "🔀", itCommon, creds = true, isIt = true),
        TypeInfo("firewall", "فايروول (Sophos ...)", "🛡️", itCommon, creds = true, isIt = true),
        TypeInfo("ad", "Active Directory", "🏢", listOf(Field("domain", "الدومين")) + itCommon, creds = true, isIt = true),
        TypeInfo(
            "wifi", "Wi-Fi", "📶",
            listOf(Field("ssid", "اسم الشبكة (SSID)"), Field("security", "نوع الأمان (WPA2 / WPA3)"), Field("location", "المكان")),
            creds = true, hasUser = false, isIt = true, passLabel = "باسورد الواي فاي",
        ),
        TypeInfo("note", "ملاحظة سرية", "📝"),
        TypeInfo(
            "card", "كارت دفع", "💳",
            listOf(
                Field("holder", "اسم حامل الكارت"),
                Field("number", "رقم الكارت", secret = true, numeric = true),
                Field("exp", "تاريخ الانتهاء (MM/YY)"),
                Field("cvv", "CVV", secret = true, numeric = true),
            ),
        ),
        TypeInfo(
            "identity", "هوية", "🪪",
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
