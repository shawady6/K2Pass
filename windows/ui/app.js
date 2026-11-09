import { parseVaultFile, unlockVault, readEntries } from './lib/vault.js';
import { totp } from './lib/totp.js';
import { FOLDER_TYPE, folderKey, isLive, allUrls } from './lib/match.js';
import { icon, hydrate } from './lib/icons.js';

hydrate();
const $ = (id) => document.getElementById(id);
const T = window.__TAURI__ || null;                       // absent when the UI is opened in a plain browser (tests)
const invoke = (c, a) => (T ? T.core.invoke(c, a) : Promise.reject(new Error('no-tauri')));
const LOCK_MS = 5 * 60 * 1000;
const store = {
  get: (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set: (k, v) => localStorage.setItem(k, JSON.stringify(v)),
};

let vaultFile = null, key = null, entries = [], folders = [], openId = null, otpTimer = null, idle = null;

const VIEWS = ['v-import', 'v-unlock', 'v-list', 'v-pair'];
function show(v) {
  for (const x of VIEWS) $(x).classList.toggle('hidden', x !== v);
  $('btn-lock').classList.toggle('hidden', v !== 'v-list');
  $('btn-pair').classList.toggle('hidden', v === 'v-import' || v === 'v-pair');
}
function toast(t) { const el = $('toast'); el.textContent = t; el.classList.remove('hidden'); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.add('hidden'), 2200); }
async function copy(text, label) { await navigator.clipboard.writeText(text); toast(`اتنسخ ${label}`); clearTimeout(copy.t); copy.t = setTimeout(() => navigator.clipboard.writeText(' ').catch(() => {}), 20000); }
function bump() { clearTimeout(idle); if (key) idle = setTimeout(lock, LOCK_MS); }
['mousemove', 'keydown', 'click'].forEach((e) => addEventListener(e, bump, true));

function start() {
  const text = store.get('vault', null);
  if (!text) return show('v-import');
  vaultFile = parseVaultFile(text);
  show('v-unlock'); $('pw').focus();
}

async function load(k) {
  const raw = await readEntries(vaultFile, k);
  key = k;
  folders = raw.filter((e) => e.type === FOLDER_TYPE && !e.del);
  entries = raw.filter((e) => e.type !== FOLDER_TYPE && isLive(e));
  const sel = $('folder'); sel.innerHTML = '';
  const add = (v, t) => { const o = document.createElement('option'); o.value = v; o.textContent = t; sel.appendChild(o); };
  add('', 'كل العناصر'); add('fav', 'المفضلة');
  folders.sort((a, b) => a.title.localeCompare(b.title)).forEach((f) => add(folderKey(f), f.title));
  show('v-list'); render(); bump();
}

function lock() { key = null; entries = []; folders = []; openId = null; clearInterval(otpTimer); $('list').innerHTML = ''; $('detail').innerHTML = '<p class="empty">اختار حساب من القايمة</p>'; $('q').value = ''; if (vaultFile) { show('v-unlock'); $('pw').focus(); } }

const subtitle = (e) => e.user || (e.extra && (e.extra.host || e.extra.ssid)) || '';
const folderName = (e) => (folders.find((x) => folderKey(x) === e.cat) || {}).title || '';
function matchQ(e, q) {
  q = q.toLowerCase();
  const secret = new Set(['privkey', 'passphrase', 'cvv', 'number', 'voice']);
  return [e.title, e.user, e.notes, folderName(e), ...allUrls(e), ...Object.entries(e.extra || {}).filter(([k]) => !secret.has(k) && k !== 'urls').map(([, v]) => v)]
    .some((x) => (x || '').toLowerCase().includes(q));
}
function mk(tag, cls, text) { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = text; return n; }
function btn(ic, title, fn) { const b = mk('button'); b.append(icon(ic, 16)); b.title = title; b.onclick = (ev) => { ev.stopPropagation(); fn(); }; return b; }

function render() {
  const q = $('q').value.trim(), f = $('folder').value;
  const list = entries.filter((e) => (!f || (f === 'fav' ? e.fav : e.cat === f)) && (!q || matchQ(e, q)))
    .sort((a, b) => (b.fav ? 1 : 0) - (a.fav ? 1 : 0) || a.title.localeCompare(b.title));
  const box = $('list'); box.innerHTML = '';
  for (const e of list) {
    const d = mk('div', 'row' + (e.id === openId ? ' sel' : '')), top = mk('div', 'row-top');
    const info = mk('div', 'info'), ttl = mk('div', 'title');
    if (e.fav) ttl.append(icon('star', 13));
    ttl.append(document.createTextNode(e.title)); info.append(ttl, mk('div', 'sub', subtitle(e)));
    const acts = mk('div', 'acts');
    if (e.user) acts.append(btn('user', 'نسخ اسم المستخدم', () => copy(e.user, 'اسم المستخدم')));
    if (e.pass) acts.append(btn('key', 'نسخ الباسورد', () => copy(e.pass, 'الباسورد')));
    top.append(mk('div', 'avatar', (e.title || '?').trim().slice(0, 1).toUpperCase()), info, acts); d.append(top);
    d.onclick = () => { openId = e.id; render(); detail(e); };
    box.append(d);
  }
  $('empty').classList.toggle('hidden', list.length > 0);
}

function field(k, v, secret) {
  const f = mk('div', 'f'), val = mk('span', 'v', secret ? '••••••••' : v);
  f.append(mk('span', 'k', k), val);
  if (secret) { const eye = btn('eye', 'إظهار', () => { const hid = val.textContent === '••••••••'; val.textContent = hid ? v : '••••••••'; eye.replaceChildren(icon(hid ? 'eyeoff' : 'eye', 16)); }); f.append(eye); }
  f.append(btn('copy', 'نسخ', () => copy(v, k))); return f;
}
function detail(e) {
  clearInterval(otpTimer);
  const d = $('detail'); d.innerHTML = ''; d.append(mk('h2', '', e.title));
  const fn = folderName(e); if (fn) d.append(field('الفولدر', fn, false));
  if (e.user) d.append(field('المستخدم', e.user, false));
  if (e.pass) d.append(field('الباسورد', e.pass, true));
  for (const u of allUrls(e)) d.append(field('الرابط', u, false));
  if (e.totp) {
    const f = mk('div', 'f'), code = mk('span', 'v otp', '------'), left = mk('span', 'k');
    const tick = async () => { const r = await totp(e.totp); if (r) { code.textContent = r.code; left.textContent = r.secondsLeft + ' ث'; code.dataset.c = r.code; } };
    tick(); otpTimer = setInterval(tick, 1000);
    f.append(mk('span', 'k', 'كود TOTP'), code, left, btn('copy', 'نسخ الكود', () => copy(code.dataset.c || '', 'كود TOTP'))); d.append(f);
  }
  const secretKeys = new Set(['privkey', 'passphrase', 'cvv', 'number', 'password']);
  for (const [k, v] of Object.entries(e.extra || {})) { if (k === 'urls' || k === 'voice' || !v) continue; d.append(field(k, v, secretKeys.has(k))); }
  if (e.notes) d.append(field('ملاحظات', e.notes, false));
}

async function doUnlock() {
  $('unlock-err').textContent = '';
  const pw = $('pw').value; if (!pw) return;
  const b = $('btn-unlock'); b.disabled = true; b.textContent = 'بفتح…';
  try { const k = await unlockVault(vaultFile, pw); $('pw').value = ''; await load(k); }
  catch (e) { $('unlock-err').textContent = e.message === 'wrong' ? 'الماستر باسورد غلط' : 'تعذّر فتح الخزنة: ' + e.message; }
  finally { b.disabled = false; b.textContent = 'فتح'; }
}

// ---- phone pairing: this app LISTENS, the phone pushes its encrypted vault here ----
let pairSecret = null;
function msg(t, cls) { $('msg').textContent = t; $('status').className = 'status' + (cls ? ' ' + cls : ''); }
async function openPair(fresh) {
  show('v-pair');
  if (!T) { msg('الربط شغّال في تطبيق الويندوز بس.', 'bad'); return; }
  try {
    if (fresh || !pairSecret) { pairSecret = await invoke('new_secret'); const l = store.get('secrets', []); l.push(pairSecret); store.set('secrets', l.slice(-5)); }
    const r = await invoke('lan_start', { secrets: store.get('secrets', []) });
    const ip = r.ips[0];
    if (!ip) { msg('مفيش شبكة محلية. وصّل الكمبيوتر بالواي فاي.', 'bad'); return; }
    const text = `k2pair://${pairSecret}@${r.ips.slice(0, 3).map((x) => `${x}:${r.port}`).join(',')}`;   // phone tries each address
    const q = qrcode(0, 'M'); q.addData(text); q.make();
    $('qr').innerHTML = q.createSvgTag({ cellSize: 4, margin: 0, scalable: true });
    $('qr').dataset.pair = text;
    $('addr').textContent = r.ips.map((x) => `${x}:${r.port}`).join('   ');
    msg('في انتظار الموبايل… امسح الكود.');
  } catch (e) { msg('تعذّر تشغيل الاستقبال: ' + e, 'bad'); }
}
async function onPushed(text) {
  try {
    const f = parseVaultFile(text);
    store.set('vault', text); vaultFile = f;
    msg('وصلت الخزنة من الموبايل ✔', 'ok'); toast('اتحدّثت الخزنة من الموبايل');
    if (key) { try { await load(key); return; } catch { key = null; } }
    show('v-unlock'); $('pw').focus();
  } catch (e) { msg('الملف اللي وصل مش خزنة صالحة.', 'bad'); }
}
if (T) T.event.listen('vault-pushed', (ev) => onPushed(ev.payload));
window.__k2push = onPushed;                                // test hook (plain-browser tests)

$('btn-unlock').onclick = doUnlock; $('pw').onkeydown = (e) => { if (e.key === 'Enter') doUnlock(); };
$('btn-lock').onclick = lock; $('q').oninput = render; $('folder').onchange = render;
$('btn-pair').onclick = () => openPair(false); $('go-pair').onclick = () => openPair(true);
$('new-code').onclick = () => openPair(true);
$('pair-back').onclick = () => { if (key) show('v-list'); else if (vaultFile) show('v-unlock'); else show('v-import'); };
$('forget').onclick = () => { if (!confirm('تمسح الخزنة من هذا الكمبيوتر؟')) return; store.set('vault', null); localStorage.removeItem('vault'); vaultFile = null; show('v-import'); };
$('file').onchange = async (ev) => {
  const f = ev.target.files[0]; if (!f) return; $('import-err').textContent = '';
  try { const text = await f.text(); vaultFile = parseVaultFile(text); store.set('vault', text); show('v-unlock'); $('pw').focus(); } catch (e) { $('import-err').textContent = e.message; }
};
start();
