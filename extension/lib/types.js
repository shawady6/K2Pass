// Item types — port of the app's EntryTypes.kt. Type-specific data lives in entry.extra.
const f = (key, label, o = {}) => ({ key, label, ...o });
const itCommon = [f('host', 'IP / Hostname'), f('mgmt', 'رابط الإدارة (https://...)'), f('vendor', 'الشركة المصنعة'), f('model', 'الموديل'), f('location', 'المكان / الفرع'), f('related', 'حسابات مرتبطة (أسماء)')];
const T = (id, label, o = {}) => ({ id, label, fields: [], creds: false, hasUser: true, passLabel: 'الباسورد', ...o });

export const TYPES = [
  T('login', 'تسجيل دخول', { creds: true }),
  T('device', 'جهاز (كمبيوتر / لابتوب)', { fields: itCommon, creds: true }),
  T('server', 'سيرفر (Windows / Linux)', { fields: itCommon, creds: true }),
  T('router', 'راوتر / MikroTik', { fields: itCommon, creds: true }),
  T('switch', 'سويتش', { fields: itCommon, creds: true }),
  T('firewall', 'فايروول (Sophos ...)', { fields: itCommon, creds: true }),
  T('ad', 'Active Directory', { fields: [f('domain', 'الدومين'), ...itCommon], creds: true }),
  T('wifi', 'Wi-Fi', { fields: [f('ssid', 'اسم الشبكة (SSID)'), f('security', 'نوع الأمان (WPA2 / WPA3)'), f('location', 'المكان')], creds: true, hasUser: false, passLabel: 'باسورد الواي فاي' }),
  T('ssh', 'مفتاح SSH', { fields: [f('host', 'السيرفر / المستخدم (user@host)'), f('privkey', 'المفتاح الخاص (Private key)', { secret: true, multiline: true }), f('pubkey', 'المفتاح العام (Public key)', { multiline: true }), f('fingerprint', 'البصمة (SHA256)'), f('passphrase', 'باسفريز المفتاح', { secret: true })] }),
  T('note', 'ملاحظة سرية'),
  T('card', 'كارت دفع', { fields: [f('holder', 'اسم حامل الكارت'), f('number', 'رقم الكارت', { secret: true }), f('exp', 'تاريخ الانتهاء (MM/YY)'), f('cvv', 'CVV', { secret: true })] }),
  T('identity', 'هوية', { fields: [f('name', 'الاسم الكامل'), f('email', 'الإيميل'), f('phone', 'الموبايل'), f('address', 'العنوان', { multiline: true }), f('idnum', 'الرقم القومي / جواز', { secret: true })] }),
];
/** Unknown ids (from a newer app) are treated as a plain login. */
export const typeOf = (id) => TYPES.find((t) => t.id === id) || TYPES[0];
