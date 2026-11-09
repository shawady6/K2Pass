// Port of vault-core VaultMerge.kt + Entry JSON (VaultModels.kt). Both devices must pick the same winner, so keep them in step:
// the shared vectors in test/merge_vectors.json are asserted here and in vault-core's VaultMergeTest.
const MAX_HISTORY = 20;

/** Raw stored JSON -> canonical entry (the same defaults Entry.fromJson applies). */
export function norm(o) {
  return {
    id: o.id, title: o.title ?? '', user: o.user ?? '', pass: o.pass ?? '', url: o.url ?? '', notes: o.notes ?? '',
    totp: o.totp === undefined ? null : o.totp, ts: o.ts, del: !!o.del, cat: o.cat ?? 'other', type: o.type ?? 'login',
    fav: !!o.fav, trash: o.trash || 0, extra: o.extra || {}, hist: (o.hist || []).map(norm),
  };
}
/** Canonical entry -> stored JSON (the same optional-field rules as Entry.toJson). */
export function toJson(e) {
  const o = { id: e.id, title: e.title, user: e.user, pass: e.pass, url: e.url, notes: e.notes, ts: e.ts, del: e.del, cat: e.cat, type: e.type, fav: e.fav };
  if (e.totp !== null && e.totp !== undefined) o.totp = e.totp;
  if (e.trash > 0) o.trash = e.trash;
  if (Object.keys(e.extra).length) o.extra = e.extra;
  if (e.hist.length) o.hist = e.hist.map(toJson);
  return o;
}

const eqExtra = (a, b) => { const ka = Object.keys(a), kb = Object.keys(b); return ka.length === kb.length && ka.every((k) => a[k] === b[k]); };
const sameContent = (x, y) => x.title === y.title && x.user === y.user && x.pass === y.pass && x.url === y.url && x.notes === y.notes &&
  x.totp === y.totp && x.del === y.del && x.ts === y.ts && x.cat === y.cat && x.type === y.type && x.fav === y.fav && x.trash === y.trash && eqExtra(x.extra, y.extra);

// Kotlin: extra.toSortedMap().toString() -> "{a=1, b=2}"
const extraKey = (m) => '{' + Object.keys(m).sort().map((k) => `${k}=${m[k]}`).join(', ') + '}';
const key = (e) => [e.title, e.user, e.pass, e.url, e.notes, e.totp ?? '', e.cat, e.type, String(e.fav), String(e.trash), extraKey(e.extra)].join('\u0000');
const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

function newer(x, y) {
  if (x.ts !== y.ts) return x.ts > y.ts;
  if (x.del !== y.del) return x.del;
  return key(x) > key(y);
}
function mergeHistory(h1, h2, extra) {
  const all = [...h1, ...h2, ...(extra ? [extra] : [])].map((e) => ({ ...e, hist: [] }));
  const seen = new Set(), uniq = [];
  for (const e of all) { const k = JSON.stringify([e.ts, e.pass, e.user]); if (!seen.has(k)) { seen.add(k); uniq.push(e); } }
  uniq.sort((a, b) => (b.ts - a.ts) || cmp(a.pass, b.pass));       // stable, like Kotlin's sortedWith
  return uniq.slice(0, MAX_HISTORY);
}
function mergeEntry(x, y) {
  if (sameContent(x, y)) return { ...x, hist: mergeHistory(x.hist, y.hist, null) };
  const [w, l] = newer(x, y) ? [x, y] : [y, x];
  if (w.del) return { ...w, hist: [] };
  const extra = !l.del ? { ...l, hist: [] } : null;
  return { ...w, hist: mergeHistory(w.hist, l.hist, extra) };
}

/** Merge two lists of raw stored entries; returns raw stored entries sorted by id (exactly like VaultMerge.merge). */
export function mergeEntries(a, b) {
  const A = new Map(a.map((e) => [e.id, norm(e)])), B = new Map(b.map((e) => [e.id, norm(e)]));
  const ids = [...new Set([...A.keys(), ...B.keys()])];
  const out = ids.map((id) => (!A.has(id) ? B.get(id) : !B.has(id) ? A.get(id) : mergeEntry(A.get(id), B.get(id))));
  out.sort((p, q) => cmp(p.id, q.id));
  return out.map(toJson);
}

/** New content for an edited entry: bumps ts, pushes the previous version to history (same rule as the phone's editor). */
export function editEntry(orig, changes, now = Date.now()) {
  const o = norm(orig), n = { ...o, ...changes, ts: now, hist: o.hist };
  const content = !sameContent({ ...o, ts: 0 }, { ...n, ts: 0 });
  if (content) n.hist = [{ ...o, hist: [] }, ...o.hist].slice(0, MAX_HISTORY);
  return toJson(n);
}
