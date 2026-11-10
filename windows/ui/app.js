import { parseVaultFile, unlockVault, readEntries, writeEntries, createVault, changeMaster } from './lib/vault.js';
import { mergeEntries, editEntry, toJson, norm } from './lib/merge.js';
import { totp } from './lib/totp.js';
import { FOLDER_TYPE, folderKey, isLive, allUrls } from './lib/match.js';
import { icon, hydrate } from './lib/icons.js';
import { TYPES, typeOf } from './lib/types.js';
import { analyze, strength } from './lib/health.js';

hydrate();
const $ = (id) => document.getElementById(id);
const T = window.__TAURI__ || null;                       // absent when the UI is opened in a plain browser (tests)
const invoke = (c, a) => (T ? T.core.invoke(c, a) : Promise.reject(new Error('no-tauri')));
const store = {
  get: (k, d) => { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set: (k, v) => localStorage.setItem(k, JSON.stringify(v)),
};

const settings = { lockMin: 5, clipSec: 20, ...store.get('settings', {}) };
const saveSettings = () => store.set('settings', settings);
let lastView = 'v-import', raw = [], editing = null, vaultFile = null, key = null, entries = [], folders = [], openId = null, otpTimer = null, idle = null;

const VIEWS = ['v-import', 'v-create', 'v-unlock', 'v-list', 'v-gen', 'v-health', 'v-trash', 'v-set', 'v-pair', 'v-ext', 'v-edit'];
const NAV = ['v-list', 'v-gen', 'v-health', 'v-trash', 'v-set'];
function show(v) {
  for (const x of VIEWS) $(x).classList.toggle('hidden', x !== v);
  $('btn-lock').classList.toggle('hidden', !key);
  $('btn-pair').classList.toggle('hidden', v === 'v-import' || v === 'v-create' || v === 'v-pair');
  $('nav').classList.toggle('hidden', !key || !NAV.includes(v));
  document.querySelectorAll('#nav button').forEach((b) => b.classList.toggle('on', b.dataset.go === v));
  if (v !== 'v-ext') lastView = v;
  if (v === 'v-gen') gen(); if (v === 'v-health') renderHealth(); if (v === 'v-trash') renderTrash(); if (v === 'v-set') renderSettings();
}
function toast(t) { const el = $('toast'); el.textContent = t; el.classList.remove('hidden'); clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.add('hidden'), 2200); }
async function copy(text, label) { await navigator.clipboard.writeText(text); toast(`اتنسخ ${label}`); clearTimeout(copy.t); if (settings.clipSec) copy.t = setTimeout(() => navigator.clipboard.writeText(' ').catch(() => {}), settings.clipSec * 1000); }
function bump() { clearTimeout(idle); if (key && settings.lockMin) idle = setTimeout(lock, settings.lockMin * 60000); }
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
  if (pend) { store.set('pending', null); if (!(await mergeIn(pend, true))) toast('خزنة الموبايل المعلّقة مختلفة، اتجاهلت'); }
  await purgeTrash();
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
  await saveFile(await writeEntries(vaultFile, key, merged), merged, !same);
  if (!quiet) toast(`اتدمجت الخزنة (${entries.length} حساب)`);
  return true;
}

function lock() { key = null; raw = []; entries = []; folders = []; openId = null; clearInterval(otpTimer); $('list').innerHTML = ''; $('detail').innerHTML = '<p class="empty">اختار عنصر من القايمة</p>'; $('q').value = ''; if (vaultFile) { show('v-unlock'); $('pw').focus(); } }

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
  const t = typeOf(e.type), known = new Map(t.fields.map((x) => [x.key, x]));
  const secretKeys = new Set(['privkey', 'passphrase', 'cvv', 'number', 'password', 'idnum']);
  for (const [k, v] of Object.entries(e.extra || {})) { if (k === 'urls' || k === 'voice' || !v) continue; const kf = known.get(k); d.append(field(kf ? kf.label : k, v, kf ? !!kf.secret : secretKeys.has(k))); }
  if (e.notes) d.append(field('ملاحظات', e.notes, false));
  const rawE = raw.find((x) => x.id === e.id), h = rawE ? norm(rawE).hist : [];
  if (h.length) {
    d.append(mk('div', 'hl', `السجل (${h.length})`));
    for (const v of h) {
      const r = mk('div', 'mini'); r.append(mk('div', 'grow', `${new Date(v.ts).toLocaleString('ar-EG')}  ·  ${v.user || '—'}`));
      const cp = btn('copy', 'نسخ باسورد النسخة دي', () => copy(v.pass, 'الباسورد القديم')), rs = mk('button', '', 'استرجاع');
      rs.onclick = async () => {
        if (!confirm('ترجّع النسخة دي؟ النسخة الحالية هتتحفظ في السجل.')) return;
        const out = editEntry(rawE, { title: v.title, user: v.user, pass: v.pass, url: v.url, notes: v.notes, totp: v.totp, type: v.type, extra: v.extra }, Date.now());
        await commit([...raw.filter((x) => x.id !== out.id), out]); detail(entries.find((x) => x.id === out.id) || out); toast('اتسترجعت');
      };
      r.append(cp, rs); d.append(r);
    }
  }
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
      if (!confirm('الخزنة اللي جاية من الموبايل مختلفة عن خزنة الكمبيوتر. تستبدل خزنة الكمبيوتر بيها؟')) { msg('اتلغى الاستبدال.', 'bad'); return; }
      lock(); store.set('pending', null);                      // a different vault: replace it
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
$('btn-lock').onclick = lock; $('s-pair').onclick = () => openPair(false); $('q').oninput = render; $('folder').onchange = render;
$('btn-pair').onclick = () => openPair(false); $('go-pair').onclick = () => openPair(true);
$('use-manual').onclick = manualPull;
$('new-code').onclick = () => openPair(true);
$('pair-back').onclick = () => { scanning++; if (key) show('v-set'); else if (vaultFile) show('v-unlock'); else show('v-import'); };
$('forget').onclick = $('forget2').onclick = () => { if (!confirm('تمسح الخزنة من هذا الكمبيوتر؟')) return; store.set('vault', null); localStorage.removeItem('vault'); publish(null); lock(); vaultFile = null; show('v-import'); };
$('file').onchange = async (ev) => {
  const f = ev.target.files[0]; if (!f) return; $('import-err').textContent = '';
  try { const text = await f.text(); vaultFile = parseVaultFile(text); store.set('vault', text); publish(text); show('v-unlock'); $('pw').focus(); } catch (e) { $('import-err').textContent = e.message; }
};
async function openExt() {
  show('v-ext');
  try { const i = await invoke('ext_info'); $('ext-path').textContent = i.path; $('ext-status').textContent = i.serving ? `الإضافة المرفقة: ${i.version}. التطبيق جاهز يخدّمها.` : 'تعذّر تشغيل الخدمة المحلية (البورت 8767 مشغول؟).'; }
  catch { $('ext-status').textContent = 'متاح في تطبيق الويندوز بس.'; }
}
$('s-ext').onclick = openExt;
async function checkUpdate(manual) {
  try {
    if (await invoke('update_check')) {
      if (confirm('فيه نسخة جديدة من K2Pass. تحدّث دلوقتي؟ (التطبيق هيقفل ويتثبت)')) { toast('بنزّل التحديث...'); await invoke('update_install'); }
    } else if (manual) toast('إنت على آخر نسخة');
  } catch (e) { if (manual) toast('مقدرتش أفحص التحديث (النت؟)'); }
}
$('upd').onclick = () => checkUpdate(true);
$('fw-open').onclick = async () => { try { await invoke('allow_firewall'); toast('تم السماح — الموبايل يقدر يبعت تلقائي'); } catch (e) { toast('اتلغى'); } };
$('ext-open').onclick = () => invoke('ext_open_folder');
$('ext-copy').onclick = () => copy($('ext-path').textContent, 'المسار');
$('ext-back').onclick = () => show(key ? 'v-set' : lastView);
// ---- add / edit / trash ----
const rnd = (n = 20, o = {}) => {
  const sets = [o.lo !== false && 'abcdefghijkmnpqrstuvwxyz', o.up !== false && 'ABCDEFGHJKLMNPQRSTUVWXYZ', o.di !== false && '23456789', o.sy !== false && '!@#$%^&*-_?'].filter(Boolean);
  if (!sets.length) sets.push('abcdefghijkmnpqrstuvwxyz');
  const all = sets.join(''), pick = (set) => set[crypto.getRandomValues(new Uint32Array(1))[0] % set.length];
  const out = sets.map(pick);                                    // at least one of every chosen class
  while (out.length < n) out.push(pick(all));
  for (let i = out.length - 1; i > 0; i--) { const j = crypto.getRandomValues(new Uint32Array(1))[0] % (i + 1); [out[i], out[j]] = [out[j], out[i]]; }
  return out.join('');
};
const blank = { title: '', user: '', pass: '', url: '', notes: '', totp: null, fav: false, cat: 'other', type: 'login', extra: {} };
function drawExtra(typeId, vals) {
  const t = typeOf(typeId), box = $('ed-extra'); box.innerHTML = '';
  for (const f of t.fields) {
    const el = f.multiline ? document.createElement('textarea') : document.createElement('input');
    if (f.multiline) el.rows = 3; else el.type = f.secret ? 'password' : 'text';
    el.placeholder = f.label; el.value = vals[f.key] || ''; el.dataset.k = f.key; el.autocomplete = 'off'; el.dir = 'ltr';
    box.append(el);
  }
  $('ed-user').classList.toggle('hidden', !(t.creds && t.hasUser));
  $('ed-passrow').classList.toggle('hidden', !t.creds); $('ed-pass').placeholder = t.passLabel;
  $('ed-url').classList.toggle('hidden', !t.creds); $('ed-urls').classList.toggle('hidden', !t.creds); $('ed-totp').classList.toggle('hidden', !t.creds);
}
function openEdit(e) {
  editing = e || null; show('v-edit');
  $('ed-title-h').textContent = e ? 'تعديل' : 'عنصر جديد';
  const o = e ? norm(e) : blank;
  const ts = $('ed-type'); ts.innerHTML = '';
  TYPES.forEach((t) => { const x = document.createElement('option'); x.value = t.id; x.textContent = t.label; ts.appendChild(x); });
  ts.value = typeOf(o.type).id === o.type ? o.type : 'login';
  ts.onchange = () => drawExtra(ts.value, collectExtra());
  $('ed-title').value = o.title; $('ed-user').value = o.user; $('ed-pass').value = o.pass; $('ed-pass').type = 'password';
  $('ed-url').value = o.url; $('ed-urls').value = o.extra.urls || ''; $('ed-notes').value = o.notes; $('ed-totp').value = o.totp || ''; $('ed-fav').checked = o.fav;
  drawExtra(ts.value, o.extra);
  const f = $('ed-folder'); f.innerHTML = '';
  const add = (v, t) => { const x = document.createElement('option'); x.value = v; x.textContent = t; f.appendChild(x); };
  add('other', 'بدون فولدر'); folders.forEach((x) => add(folderKey(x), x.title));
  if (![...f.options].some((x) => x.value === o.cat)) add(o.cat, o.cat);
  f.value = o.cat;
  $('ed-del').classList.toggle('hidden', !e); $('ed-err').textContent = ''; $('ed-title').focus();
}
const collectExtra = () => { const x = {}; $('ed-extra').querySelectorAll('[data-k]').forEach((el) => { if (el.value.trim()) x[el.dataset.k] = el.value.trim(); }); return x; };
async function saveEdit() {
  const title = $('ed-title').value.trim();
  if (!title) { $('ed-err').textContent = 'اكتب اسم للعنصر'; return; }
  const type = $('ed-type').value, t = typeOf(type), extra = collectExtra();
  const urls = $('ed-urls').value.split('\n').map((x) => x.trim()).filter(Boolean).join('\n');
  if (t.creds && urls) extra.urls = urls;
  const ch = { title, user: t.creds && t.hasUser ? $('ed-user').value : '', pass: t.creds ? $('ed-pass').value : '', url: t.creds ? $('ed-url').value.trim() : '',
    notes: $('ed-notes').value, totp: t.creds ? $('ed-totp').value.trim() || null : null, fav: $('ed-fav').checked, cat: $('ed-folder').value, type, extra };
  const now = Date.now();
  const out = editing ? editEntry(editing, ch, now) : toJson({ id: crypto.randomUUID(), ...ch, ts: now, del: false, trash: 0, hist: [] });
  await commit([...raw.filter((x) => x.id !== out.id), out]);
  openId = out.id; show('v-list'); render(); detail(entries.find((x) => x.id === out.id) || out); toast('اتحفظ ✔');
}
const tombstone = (x, now) => toJson({ ...norm(x), user: '', pass: '', notes: '', totp: null, del: true, ts: now, hist: [], extra: {}, trash: 0, fav: false });
async function trashEdit() {
  if (!editing || !confirm('تنقل العنصر للسلة؟ (تقدر ترجّعه خلال 30 يوم)')) return;
  const now = Date.now();
  await commit(raw.map((x) => (x.id === editing.id ? toJson({ ...norm(x), trash: now, ts: now }) : x)));
  openId = null; $('detail').innerHTML = '<p class="empty">اختار عنصر من القايمة</p>'; show('v-list'); render(); toast('اتنقل للسلة');
}
$('btn-new').onclick = () => openEdit(null);
$('ed-save').onclick = saveEdit; $('ed-del').onclick = trashEdit;
$('ed-cancel').onclick = () => show('v-list');
$('ed-gen').onclick = () => { $('ed-pass').value = rnd(20); $('ed-pass').type = 'text'; };
$('ed-eye').onclick = () => { const p = $('ed-pass'); p.type = p.type === 'password' ? 'text' : 'password'; };

// ---- trash ----
const DAY = 86400000;
async function purgeTrash() {                                  // trash older than 30 days becomes a tombstone (same as the phone)
  const now = Date.now(), old = raw.filter((x) => !x.del && x.trash > 0 && now - x.trash > 30 * DAY);
  if (old.length) await commit(raw.map((x) => (old.includes(x) ? tombstone(x, now) : x)));
}
function renderTrash() {
  const box = $('trash'); box.innerHTML = '';
  const items = raw.filter((x) => !x.del && x.trash > 0 && x.type !== FOLDER_TYPE).sort((a, b) => b.trash - a.trash);
  if (!items.length) box.append(mk('p', 'empty', 'السلة فاضية'));
  for (const x of items) {
    const r = mk('div', 'mini'), left = Math.max(0, 30 - Math.floor((Date.now() - x.trash) / DAY));
    const g = mk('div', 'grow', `${x.title}  ·  فاضل ${left} يوم`);
    const rs = mk('button', '', 'استرجاع'), del = mk('button', '', 'حذف نهائي'); del.classList.add('danger');
    rs.onclick = async () => { await commit(raw.map((y) => (y.id === x.id ? toJson({ ...norm(y), trash: 0, ts: Date.now() }) : y))); renderTrash(); toast('اتسترجع'); };
    del.onclick = async () => { if (!confirm('حذف نهائي؟ مش هترجع تاني.')) return; await commit(raw.map((y) => (y.id === x.id ? tombstone(y, Date.now()) : y))); renderTrash(); };
    r.append(g, rs, del); box.append(r);
  }
  $('trash-empty').classList.toggle('hidden', !items.length);
}
$('trash-empty').onclick = async () => {
  if (!confirm('تفضّي السلة نهائيًا؟')) return;
  const now = Date.now(); await commit(raw.map((y) => (!y.del && y.trash > 0 && y.type !== FOLDER_TYPE ? tombstone(y, now) : y))); renderTrash();
};

// ---- password generator ----
function gen() {
  const o = { up: $('g-up').checked, lo: $('g-lo').checked, di: $('g-di').checked, sy: $('g-sy').checked }, n = +$('g-len').value;
  const p = rnd(n, o); $('g-out').textContent = p; $('g-lenv').textContent = n; $('g-str').dataset.s = strength(p);
}
['g-len', 'g-up', 'g-lo', 'g-di', 'g-sy'].forEach((i) => ($(i).oninput = gen));
$('g-new').onclick = gen; $('g-copy').onclick = () => copy($('g-out').textContent, 'الباسورد');

// ---- health ----
function renderHealth() {
  const r = analyze(entries), box = $('health'); box.innerHTML = '';
  box.append(mk('p', 'hint', `فُحص ${r.total} عنصر. الباسوردات مش بتخرج من الجهاز.`));
  const group = (title, list) => {
    box.append(mk('div', 'hl', `${title} (${list.length})`));
    if (!list.length) box.append(mk('p', 'hint', 'لا يوجد'));
    for (const e of list) { const row = mk('div', 'mini'); row.append(mk('div', 'grow', `${e.title}${e.user ? ' · ' + e.user : ''}`)); const b = mk('button', '', 'فتح'); b.onclick = () => { openId = e.id; show('v-list'); render(); detail(e); }; row.append(b); box.append(row); }
  };
  group('باسوردات ضعيفة', r.weak); group('باسوردات مكررة', r.reused.flat()); group('باسوردات قديمة (+ سنة)', r.old);
}

// ---- settings ----
function chips(id, opts, cur, set) {
  const box = $(id); box.innerHTML = '';
  for (const [v, label] of opts) { const b = mk('button', cur === v ? 'on' : '', label); b.onclick = () => { set(v); saveSettings(); bump(); chips(id, opts, v, set); }; box.append(b); }
}
async function renderSettings() {
  chips('set-lock', [[1, 'دقيقة'], [5, '5 دقايق'], [15, '15 دقيقة'], [60, 'ساعة'], [0, 'أبدًا']], settings.lockMin, (v) => (settings.lockMin = v));
  chips('set-clip', [[10, '10 ث'], [20, '20 ث'], [30, '30 ث'], [60, '60 ث'], [0, 'مفيش']], settings.clipSec, (v) => (settings.clipSec = v));
  const box = $('set-folders'); box.innerHTML = '';
  if (!folders.length) box.append(mk('p', 'hint', 'مفيش فولدرات'));
  for (const f of [...folders].sort((a, b) => a.title.localeCompare(b.title))) {
    const r = mk('div', 'mini'), n = entries.filter((e) => e.cat === folderKey(f)).length;
    const ren = mk('button', '', 'تسمية'), del = mk('button', '', 'حذف'); del.classList.add('danger');
    ren.onclick = async () => { const t = prompt('الاسم الجديد', f.title); if (!t || !t.trim()) return; await commit(raw.map((x) => (x.id === f.id ? toJson({ ...norm(x), title: t.trim(), ts: Date.now() }) : x))); renderSettings(); };
    del.onclick = async () => {
      if (!confirm(`تحذف الفولدر «${f.title}»؟ العناصر اللي جواه هتفضل بدون فولدر.`)) return;
      const now = Date.now(), k = folderKey(f);
      await commit(raw.map((x) => (x.id === f.id ? tombstone(x, now) : x.cat === k && !x.del ? toJson({ ...norm(x), cat: 'other', ts: now }) : x))); renderSettings();
    };
    r.append(mk('div', 'grow', `${f.title}  ·  ${n}`), ren, del); box.append(r);
  }
  const last = store.get('lastBackup', 0);
  $('bk-note').textContent = last ? 'آخر نسخة احتياطية: ' + new Date(last).toLocaleString('ar-EG') : 'لسه ما عملتش نسخة احتياطية من الكمبيوتر.';
  try { $('app-ver').textContent = 'K2Pass للكمبيوتر ' + (await invoke('app_version')); } catch { $('app-ver').textContent = 'K2Pass للكمبيوتر'; }
}
$('nf-add').onclick = async () => {
  const name = $('nf-name').value.trim(); if (!name) return;
  await commit([...raw, toJson({ id: crypto.randomUUID(), title: name, user: '', pass: '', url: '', notes: '', totp: null, ts: Date.now(), del: false, cat: 'other', type: FOLDER_TYPE, fav: false, trash: 0, extra: {}, hist: [] })]);
  $('nf-name').value = ''; renderSettings();
};
$('mp-go').onclick = async () => {
  const o = $('mp-old').value, n = $('mp-new').value, n2 = $('mp-new2').value, err = $('mp-err'); err.textContent = '';
  if (n.length < 10) { err.textContent = 'الجديد لازم 10 حروف على الأقل'; return; }
  if (n !== n2) { err.textContent = 'التأكيد مش مطابق'; return; }
  const b = $('mp-go'); b.disabled = true; b.textContent = 'بغيّر…';
  try {
    await unlockVault(vaultFile, o);
    const nf = await changeMaster(vaultFile, key, n, raw);
    await saveFile(nf, raw, true);
    ['mp-old', 'mp-new', 'mp-new2'].forEach((i) => ($(i).value = '')); toast('اتغيّر الماستر باسورد ✔');
  } catch (e) { err.textContent = e.message === 'wrong' ? 'الباسورد الحالي غلط' : e.message; }
  finally { b.disabled = false; b.textContent = 'تغيير'; }
};
$('bk-save').onclick = async () => {
  try {
    const name = 'K2Pass-backup-' + new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-') + '.json';
    const path = await invoke('backup_save', { name, text: JSON.stringify(vaultFile) });
    store.set('lastBackup', Date.now()); renderSettings(); toast('اتحفظت: ' + path);
  } catch (e) { toast('تعذّر الحفظ: ' + e); }
};
$('bk-file').onchange = async (ev) => {
  const f = ev.target.files[0]; ev.target.value = ''; if (!f) return;
  try { const text = await f.text(); parseVaultFile(text); await onPushed(text); } catch (e) { toast(e.message); }
};

// ---- navigation, creating a vault ----
document.querySelectorAll('#nav button').forEach((b) => (b.onclick = () => show(b.dataset.go)));
$('go-create').onclick = () => { $('cr-pw').value = $('cr-pw2').value = ''; $('cr-err').textContent = ''; show('v-create'); $('cr-pw').focus(); };
$('cr-back').onclick = () => show('v-import');
$('cr-go').onclick = async () => {
  const p = $('cr-pw').value, e = $('cr-err'); e.textContent = '';
  if (p.length < 10) { e.textContent = 'الماستر باسورد لازم 10 حروف على الأقل'; return; }
  if (p !== $('cr-pw2').value) { e.textContent = 'التأكيد مش مطابق'; return; }
  const b = $('cr-go'); b.disabled = true; b.textContent = 'بجهّز…';
  try {
    const { file, key: k } = await createVault(p, []);
    vaultFile = file; store.set('vault', JSON.stringify(file)); store.set('dirty', true); publish(JSON.stringify(file));
    $('cr-pw').value = $('cr-pw2').value = ''; await load(k);
  } catch (x) { e.textContent = String(x.message || x); } finally { b.disabled = false; b.textContent = 'إنشاء'; }
};
start();
