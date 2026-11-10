import { parseVaultFile, unlockVault, readEntries, writeEntries } from './lib/vault.js';
import { mergeEntries, editEntry, toJson, norm } from './lib/merge.js';
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

let lastView = 'v-import', raw = [], editing = null, vaultFile = null, key = null, entries = [], folders = [], openId = null, otpTimer = null, idle = null;

const VIEWS = ['v-import', 'v-unlock', 'v-list', 'v-pair', 'v-ext', 'v-edit'];
function show(v) {
  for (const x of VIEWS) $(x).classList.toggle('hidden', x !== v);
  $('btn-lock').classList.toggle('hidden', v !== 'v-list');
  $('btn-pair').classList.toggle('hidden', v === 'v-import' || v === 'v-pair');
  $('btn-ext').classList.toggle('hidden', v === 'v-ext');
  if (v !== 'v-ext') lastView = v;
}
function toast(t) { const el = $('toast'); el.textContent = t; el.classList.remove('hidden'); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.add('hidden'), 2200); }
async function copy(text, label) { await navigator.clipboard.writeText(text); toast(`اتنسخ ${label}`); clearTimeout(copy.t); copy.t = setTimeout(() => navigator.clipboard.writeText(' ').catch(() => {}), 20000); }
function bump() { clearTimeout(idle); if (key) idle = setTimeout(lock, LOCK_MS); }
['mousemove', 'keydown', 'click'].forEach((e) => addEventListener(e, bump, true));

const publish = (t) => invoke('vault_publish', { text: t }).catch(() => {});
function start() {
  const text = store.get('vault', null);
  publish(text);
  if (!text) return show('v-import');
  vaultFile = parseVaultFile(text);
  show('v-unlock'); $('pw').focus();
}

function derive() {
  folders = raw.filter((e) => e.type === FOLDER_TYPE && !e.del);
  entries = raw.filter((e) => e.type !== FOLDER_TYPE && isLive(e));
  const sel = $('folder'), cur = sel.value; sel.innerHTML = '';
  const add = (v, t) => { const o = document.createElement('option'); o.value = v; o.textContent = t; sel.appendChild(o); };
  add('', 'كل العناصر'); add('fav', 'المفضلة');
  folders.sort((a, b) => a.title.localeCompare(b.title)).forEach((f) => add(folderKey(f), f.title));
  sel.value = [...sel.options].some((o) => o.value === cur) ? cur : '';
}

async function load(k) {
  raw = await readEntries(vaultFile, k);
  key = k;
  derive();
  const pend = store.get('pending', null);
  if (pend) { store.set('pending', null); await mergeIn(pend, true); }
  show('v-list'); render(); bump();
}

// ---- saving & merging (same rules as the phone: newest edit wins, losers go to history, deletes are final) ----
async function saveFile(f, entriesRaw, dirty) {
  vaultFile = f; raw = entriesRaw;
  const text = JSON.stringify(f);
  store.set('vault', text); store.set('dirty', dirty); publish(text);
  derive(); render();
}
async function commit(newRaw) { await saveFile(await writeEntries(vaultFile, key, newRaw), newRaw, true); }

/** Merge a vault file received from the phone into the open vault. Returns false when it belongs to a different vault. */
async function mergeIn(text, quiet) {
  const remote = parseVaultFile(text);
  let rem;
  try { rem = await readEntries(remote, key); } catch { return false; }
  const merged = mergeEntries(raw, rem);
  const same = JSON.stringify(merged) === JSON.stringify(mergeEntries(rem, rem));     // nothing the phone lacks
  await saveFile(await writeEntries(remote, key, merged), merged, !same);
  if (!quiet) toast(`اتدمجت الخزنة (${entries.length} حساب)`);
  return true;
}

function lock() { key = null; raw = []; entries = []; folders = []; openId = null; clearInterval(otpTimer); $('list').innerHTML = ''; $('detail').innerHTML = '<p class="empty">اختار حساب من القايمة</p>'; $('q').value = ''; if (vaultFile) { show('v-unlock'); $('pw').focus(); } }

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
  const d = $('detail'); d.innerHTML = '';
  const hd = mk('div', 'dh'); hd.append(mk('h2', '', e.title), btn('edit', 'تعديل', () => openEdit(raw.find((x) => x.id === e.id) || e))); d.append(hd);
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
let pairSecret = null, scanning = 0;
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
    scanLoop();
  } catch (e) { msg('تعذّر تشغيل الاستقبال: ' + e, 'bad'); }
}
// Second path (works even when the PC firewall blocks the phone): this app looks for the phone's server and pulls from it.
async function scanLoop() {
  const me = ++scanning;
  while (me === scanning && !$('v-pair').classList.contains('hidden')) {
    try {
      const hit = await invoke('phone_find', { secrets: store.get('secrets', []) });
      if (hit && me === scanning) { scanning++; return onPushed(hit.vault); }
    } catch { /* retry */ }
    await new Promise((r) => setTimeout(r, 2500));
  }
}
async function manualPull() {
  const a = $('manual').value.trim(); if (!a) return;
  msg('بكلّم الموبايل…');
  try { scanning++; await onPushed(await invoke('phone_pull', { addr: a, secrets: store.get('secrets', []) })); }
  catch (e) { msg(String(e), 'bad'); scanLoop(); }
}
async function onPushed(text) {
  try {
    const f = parseVaultFile(text);
    if (key) {
      if (await mergeIn(text, false)) { msg('وصلت الخزنة من الموبايل واتدمجت ✔', 'ok'); return; }
      key = null; raw = [];                                  // a different vault: replace it
    } else if (store.get('dirty', false) && vaultFile) {
      store.set('pending', text);                            // we have local edits: merge after the next unlock
      msg('وصلت الخزنة. هتتدمج مع تعديلاتك أول ما تفتح الخزنة ✔', 'ok'); toast('اكتب الماستر باسورد عشان تتدمج'); show('v-unlock'); $('pw').focus(); return;
    }
    store.set('vault', text); store.set('dirty', false); store.set('pending', null); vaultFile = f; publish(text);
    msg('وصلت الخزنة من الموبايل ✔', 'ok'); toast('اتحدّثت الخزنة من الموبايل');
    show('v-unlock'); $('pw').focus();
  } catch (e) { msg('الملف اللي وصل مش خزنة صالحة.', 'bad'); }
}
if (T) { T.event.listen('vault-pushed', (ev) => onPushed(ev.payload)); T.event.listen('vault-served', () => store.set('dirty', false)); }
window.__k2push = onPushed;                                // test hook (plain-browser tests)

$('btn-unlock').onclick = doUnlock; $('pw').onkeydown = (e) => { if (e.key === 'Enter') doUnlock(); };
$('btn-lock').onclick = lock; $('q').oninput = render; $('folder').onchange = render;
$('btn-pair').onclick = () => openPair(false); $('go-pair').onclick = () => openPair(true);
$('use-manual').onclick = manualPull;
$('new-code').onclick = () => openPair(true);
$('pair-back').onclick = () => { scanning++; if (key) show('v-list'); else if (vaultFile) show('v-unlock'); else show('v-import'); };
$('forget').onclick = () => { if (!confirm('تمسح الخزنة من هذا الكمبيوتر؟')) return; store.set('vault', null); localStorage.removeItem('vault'); publish(null); vaultFile = null; show('v-import'); };
$('file').onchange = async (ev) => {
  const f = ev.target.files[0]; if (!f) return; $('import-err').textContent = '';
  try { const text = await f.text(); vaultFile = parseVaultFile(text); store.set('vault', text); publish(text); show('v-unlock'); $('pw').focus(); } catch (e) { $('import-err').textContent = e.message; }
};
async function openExt() {
  show('v-ext');
  try { const i = await invoke('ext_info'); $('ext-path').textContent = i.path; $('ext-status').textContent = i.serving ? `الإضافة المرفقة: ${i.version}. التطبيق جاهز يخدّمها.` : 'تعذّر تشغيل الخدمة المحلية (البورت 8767 مشغول؟).'; }
  catch { $('ext-status').textContent = 'متاح في تطبيق الويندوز بس.'; }
}
$('btn-ext').onclick = openExt;
async function checkUpdate(manual) {
  try {
    if (await invoke('update_check')) {
      if (confirm('فيه نسخة جديدة من K2Pass. تحدّث دلوقتي؟ (التطبيق هيقفل ويتثبت)')) { toast('بنزّل التحديث...'); await invoke('update_install'); }
    } else if (manual) toast('إنت على آخر نسخة');
  } catch (e) { if (manual) toast('مقدرتش أفحص التحديث (النت؟)'); }
}
$('upd').onclick = () => checkUpdate(true);
if (window.__TAURI__) setTimeout(() => checkUpdate(false), 3000);
$('fw-open').onclick = async () => { try { await invoke('allow_firewall'); toast('تم السماح — الموبايل يقدر يبعت تلقائي'); } catch (e) { toast('اتلغى'); } };
$('ext-open').onclick = () => invoke('ext_open_folder');
$('ext-copy').onclick = () => copy($('ext-path').textContent, 'المسار');
$('ext-back').onclick = () => show(lastView);
// ---- add / edit / trash ----
const rnd = (n = 20) => { const set = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%^&*-_'; const b = crypto.getRandomValues(new Uint32Array(n)); return [...b].map((x) => set[x % set.length]).join(''); };
function openEdit(e) {
  editing = e || null; show('v-edit');
  $('ed-title-h').textContent = e ? 'تعديل الحساب' : 'حساب جديد';
  const o = e ? norm(e) : { title: '', user: '', pass: '', url: '', notes: '', totp: null, fav: false, cat: 'other' };
  $('ed-title').value = o.title; $('ed-user').value = o.user; $('ed-pass').value = o.pass; $('ed-pass').type = 'password';
  $('ed-url').value = o.url; $('ed-notes').value = o.notes; $('ed-totp').value = o.totp || ''; $('ed-fav').checked = o.fav;
  const f = $('ed-folder'); f.innerHTML = '';
  const add = (v, t) => { const x = document.createElement('option'); x.value = v; x.textContent = t; f.appendChild(x); };
  add('other', 'بدون فولدر'); folders.forEach((x) => add(folderKey(x), x.title));
  if (![...f.options].some((x) => x.value === o.cat)) add(o.cat, o.cat);
  f.value = o.cat;
  $('ed-del').classList.toggle('hidden', !e); $('ed-err').textContent = ''; $('ed-title').focus();
}
async function saveEdit() {
  const title = $('ed-title').value.trim();
  if (!title) { $('ed-err').textContent = 'اكتب اسم للحساب'; return; }
  const ch = { title, user: $('ed-user').value, pass: $('ed-pass').value, url: $('ed-url').value.trim(), notes: $('ed-notes').value, totp: $('ed-totp').value.trim() || null, fav: $('ed-fav').checked, cat: $('ed-folder').value };
  const now = Date.now();
  const out = editing ? editEntry(editing, ch, now) : toJson({ id: crypto.randomUUID(), ...ch, ts: now, del: false, type: 'login', trash: 0, extra: {}, hist: [] });
  await commit([...raw.filter((x) => x.id !== out.id), out]);
  openId = out.id; show('v-list'); render(); detail(entries.find((x) => x.id === out.id) || out); toast('اتحفظ ✔');
}
async function trashEdit() {
  if (!editing || !confirm('تنقل الحساب للسلة؟ (ممكن ترجّعه من الموبايل)')) return;
  const now = Date.now();
  await commit(raw.map((x) => (x.id === editing.id ? toJson({ ...norm(x), trash: now, ts: now }) : x)));
  openId = null; $('detail').innerHTML = '<p class="empty">اختار حساب من القايمة</p>'; show('v-list'); render(); toast('اتنقل للسلة');
}
$('btn-new').onclick = () => openEdit(null);
$('ed-save').onclick = saveEdit; $('ed-del').onclick = trashEdit;
$('ed-cancel').onclick = () => show('v-list');
$('ed-gen').onclick = () => { $('ed-pass').value = rnd(); $('ed-pass').type = 'text'; };
$('ed-eye').onclick = () => { const p = $('ed-pass'); p.type = p.type === 'password' ? 'text' : 'password'; };
start();
