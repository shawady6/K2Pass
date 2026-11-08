import { parseVaultFile, unlockVault, readEntries, b64e, b64d } from './lib/vault.js';
import { totp } from './lib/totp.js';
import { matchesHost, isLive, FOLDER_TYPE, folderKey, allUrls } from './lib/match.js';
import { fillInPage } from './lib/fill.js';

const $ = (id) => document.getElementById(id);
const LOCK_MIN = 5;           // auto-lock after 5 minutes without opening the popup
const CLIP_CLEAR_MS = 20000;  // best effort: only while the popup stays open

let vaultFile = null, entries = [], folders = [], pageHost = null, tabId = null;
let openId = null, otpTimer = null;

function show(view) {
  for (const v of ['v-import', 'v-unlock', 'v-list']) $(v).classList.toggle('hidden', v !== view);
  $('btn-lock').classList.toggle('hidden', view !== 'v-list');
  $('btn-menu').classList.toggle('hidden', view === 'v-import');
}
function toast(t) {
  const el = $('toast'); el.textContent = t; el.classList.remove('hidden');
  clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.add('hidden'), 1800);
}
async function copy(text, label) {
  await navigator.clipboard.writeText(text);
  toast(`اتنسخ ${label}`);
  clearTimeout(copy.t);
  copy.t = setTimeout(() => navigator.clipboard.writeText(' ').catch(() => {}), CLIP_CLEAR_MS);
}

async function init() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab) {
    tabId = tab.id;
    try { const u = new URL(tab.url); if (/^https?:$/.test(u.protocol)) pageHost = u.hostname; } catch {}
  }
  const { vault } = await chrome.storage.local.get('vault');
  if (!vault) return show('v-import');
  vaultFile = parseVaultFile(vault);
  const s = await chrome.storage.session.get(['k', 'until']);
  if (s.k && s.until > Date.now()) {
    try { await load(b64d(s.k)); return; } catch { /* fall through to unlock */ }
  }
  show('v-unlock'); $('pw').focus();
}

async function load(key) {
  const raw = await readEntries(vaultFile, key);
  folders = raw.filter((e) => e.type === FOLDER_TYPE && !e.del);
  entries = raw.filter((e) => e.type !== FOLDER_TYPE && isLive(e));
  const until = Date.now() + LOCK_MIN * 60000;
  await chrome.storage.session.set({ k: b64e(key), until });
  chrome.runtime.sendMessage({ type: 'schedule-lock', when: until });
  const sel = $('folder');
  sel.innerHTML = '';
  const add = (v, t) => { const o = document.createElement('option'); o.value = v; o.textContent = t; sel.appendChild(o); };
  add('', 'كل العناصر'); add('fav', '⭐ المفضلة');
  folders.sort((a, b) => a.title.localeCompare(b.title)).forEach((f) => add(folderKey(f), '📂 ' + f.title));
  show('v-list'); render();
}

function folderName(e) { const f = folders.find((x) => folderKey(x) === e.cat); return f ? f.title : ''; }
function subtitle(e) { return e.user || (e.extra && (e.extra.host || e.extra.ssid)) || ''; }
function matchQ(e, q) {
  q = q.toLowerCase();
  const secret = new Set(['privkey', 'passphrase', 'cvv', 'number', 'voice']);
  return [e.title, e.user, e.notes, folderName(e), ...allUrls(e),
    ...Object.entries(e.extra || {}).filter(([k]) => !secret.has(k) && k !== 'urls').map(([, v]) => v)]
    .some((x) => (x || '').toLowerCase().includes(q));
}

function render() {
  clearInterval(otpTimer);
  const q = $('q').value.trim(), f = $('folder').value;
  let list = entries.filter((e) => (!f || (f === 'fav' ? e.fav : e.cat === f)) && (!q || matchQ(e, q)));
  const byName = (a, b) => a.title.localeCompare(b.title);
  const here = !q && !f ? list.filter((e) => matchesHost(e, pageHost)).sort(byName) : [];
  const hereIds = new Set(here.map((e) => e.id));
  const rest = list.filter((e) => !hereIds.has(e.id)).sort((a, b) => (b.fav - a.fav) || byName(a, b));
  const box = $('list'); box.innerHTML = '';
  if (here.length) { box.appendChild(sec(`مطابق للموقع الحالي (${pageHost})`)); here.forEach((e) => box.appendChild(row(e))); box.appendChild(sec('الباقي')); }
  rest.forEach((e) => box.appendChild(row(e)));
  $('empty').classList.toggle('hidden', list.length > 0);
}
const sec = (t) => Object.assign(document.createElement('div'), { className: 'sec', textContent: t });

function btn(txt, title, fn) {
  const b = document.createElement('button'); b.textContent = txt; b.title = title;
  b.onclick = (ev) => { ev.stopPropagation(); fn(); }; return b;
}

function row(e) {
  const d = document.createElement('div'); d.className = 'row';
  const top = document.createElement('div'); top.className = 'row-top';
  const av = Object.assign(document.createElement('div'), { className: 'avatar', textContent: (e.title || '?').trim().slice(0, 1).toUpperCase() });
  const info = document.createElement('div'); info.className = 'info';
  info.append(Object.assign(document.createElement('div'), { className: 'title', textContent: (e.fav ? '⭐ ' : '') + e.title }),
    Object.assign(document.createElement('div'), { className: 'sub', textContent: subtitle(e) }));
  info.onclick = () => { openId = openId === e.id ? null : e.id; render(); };
  const acts = document.createElement('div'); acts.className = 'acts';
  if (e.user) acts.append(btn('👤', 'نسخ اسم المستخدم', () => copy(e.user, 'اسم المستخدم')));
  if (e.pass) acts.append(btn('🔑', 'نسخ الباسورد', () => copy(e.pass, 'الباسورد')));
  if ((e.type || 'login') === 'login' && tabId != null && pageHost) acts.append(btn('⚡', 'ملء الصفحة', () => fill(e)));
  top.append(av, info, acts); d.append(top);
  if (openId === e.id) d.append(details(e));
  return d;
}

function field(k, v, secret) {
  const f = document.createElement('div'); f.className = 'f';
  const val = Object.assign(document.createElement('span'), { className: 'v', textContent: secret ? '••••••••' : v });
  const eye = secret ? btn('👁', 'إظهار', () => { val.textContent = val.textContent === '••••••••' ? v : '••••••••'; }) : null;
  f.append(Object.assign(document.createElement('span'), { className: 'k', textContent: k }), val);
  if (eye) f.append(eye);
  f.append(btn('📋', 'نسخ', () => copy(v, k)));
  return f;
}

function details(e) {
  const d = document.createElement('div'); d.className = 'det';
  const fn = folderName(e); if (fn) d.append(field('الفولدر', '📂 ' + fn, false));
  if (e.user) d.append(field('المستخدم', e.user, false));
  if (e.pass) d.append(field('الباسورد', e.pass, true));
  for (const u of allUrls(e)) d.append(field('الرابط', u, false));
  if (e.totp) {
    const f = document.createElement('div'); f.className = 'f';
    const code = Object.assign(document.createElement('span'), { className: 'v otp', textContent: '------' });
    const left = Object.assign(document.createElement('span'), { className: 'k' });
    const tick = async () => { const r = await totp(e.totp); if (r) { code.textContent = r.code; left.textContent = r.secondsLeft + ' ث'; code.dataset.c = r.code; } };
    tick(); otpTimer = setInterval(tick, 1000);
    f.append(Object.assign(document.createElement('span'), { className: 'k', textContent: 'كود TOTP' }), code, left,
      btn('📋', 'نسخ الكود', () => copy(code.dataset.c || '', 'كود TOTP')));
    d.append(f);
  }
  const secretKeys = new Set(['privkey', 'passphrase', 'cvv', 'number', 'password']);
  for (const [k, v] of Object.entries(e.extra || {})) {
    if (k === 'urls' || k === 'voice' || !v) continue;
    d.append(field(k, v, secretKeys.has(k)));
  }
  if (e.notes) d.append(field('ملاحظات', e.notes, false));
  return d;
}

async function fill(e) {
  try {
    const [r] = await chrome.scripting.executeScript({ target: { tabId }, func: fillInPage, args: [e.user || '', e.pass || ''] });
    toast(r && r.result ? 'اتملّت الخانات' : 'ماقدرتش ألاقي خانات تسجيل دخول');
  } catch { toast('الصفحة دي مش مسموح الملء فيها'); }
}

async function doUnlock() {
  $('unlock-err').textContent = '';
  const pw = $('pw').value; if (!pw) return;
  const b = $('btn-unlock'); b.disabled = true; b.textContent = '…بفتح';
  try {
    const key = await unlockVault(vaultFile, pw);
    $('pw').value = '';
    await load(key);
  } catch (e) {
    $('unlock-err').textContent = e.message === 'wrong' ? 'الماستر باسورد غلط' : 'تعذّر فتح الخزنة: ' + e.message;
  } finally { b.disabled = false; b.textContent = 'فتح'; }
}

async function lock() {
  chrome.runtime.sendMessage({ type: 'lock-now' });
  entries = []; folders = []; $('list').innerHTML = ''; $('q').value = '';
  show('v-unlock'); $('pw').focus();
}

$('btn-unlock').onclick = doUnlock;
$('pw').onkeydown = (e) => { if (e.key === 'Enter') doUnlock(); };
$('btn-lock').onclick = lock;
$('q').oninput = render; $('folder').onchange = render;
$('btn-menu').onclick = () => $('menu').classList.toggle('hidden');
$('m-replace').onclick = () => { $('menu').classList.add('hidden'); chrome.runtime.sendMessage({ type: 'lock-now' }); show('v-import'); };
$('m-forget').onclick = async () => {
  if (!confirm('تمسح الخزنة من المتصفح؟ (الملف الأصلي على جهازك مش هيتأثر)')) return;
  chrome.runtime.sendMessage({ type: 'lock-now' });
  await chrome.storage.local.remove('vault'); $('menu').classList.add('hidden'); show('v-import');
};
$('file').onchange = async (ev) => {
  const f = ev.target.files[0]; if (!f) return;
  $('import-err').textContent = '';
  try {
    const text = await f.text(); parseVaultFile(text);
    await chrome.storage.local.set({ vault: text });
    vaultFile = parseVaultFile(text);
    show('v-unlock'); $('pw').focus();
  } catch (e) { $('import-err').textContent = e.message; }
};

init().catch((e) => { toast('خطأ: ' + e.message); show('v-import'); });
