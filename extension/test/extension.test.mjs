import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
globalThis.hashwasm = require('../lib/argon2.umd.min.js');

const { parseVaultFile, unlockVault, readEntries } = await import('../lib/vault.js');
const { totp } = await import('../lib/totp.js');
const { matchesHost, allUrls, isLive } = await import('../lib/match.js');

const text = readFileSync(new URL('./fixture.vault', import.meta.url), 'utf8');
const file = parseVaultFile(text);

test('unlocks a vault written by the reference (python) implementation', async () => {
  const key = await unlockVault(file, 'Correct-Horse-9!');
  assert.equal(key.length, 32);
  const e = await readEntries(file, key);
  assert.equal(e.length, 4);
  assert.equal(e.find((x) => x.id === '1').pass, 'pw-github');
  assert.equal(e.find((x) => x.id === '3').notes, 'الدور الثاني'); // UTF-8 round trip
});

test('wrong master password is rejected', async () => {
  await assert.rejects(unlockVault(file, 'nope'), /wrong/);
});

test('tampering with the header (AAD) is detected', async () => {
  const bad = { ...file, t: file.t + 0 , p: 1 };
  await assert.rejects(unlockVault(bad, 'Correct-Horse-9!'));
});

test('rejects non-vault files', () => {
  assert.throws(() => parseVaultFile('{"hello":1}'));
  assert.throws(() => parseVaultFile('not json'));
  assert.throws(() => parseVaultFile(JSON.stringify({ ...file, v: 2 })));
  assert.throws(() => parseVaultFile(JSON.stringify({ ...file, m: 99999999 })));
});

test('TOTP matches RFC 6238 vectors', async () => {
  const s1 = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ';
  const at = async (t) => (await totp(`otpauth://totp/x?secret=${s1}&digits=8`, t * 1000)).code;
  assert.equal(await at(59), '94287082');
  assert.equal(await at(1111111109), '07081804');
  assert.equal(await at(2000000000), '69279037');
  assert.equal((await totp(s1, 59000)).code, '287082');
  assert.equal(await totp('not base32 !!!'), null);
  const s256 = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA';
  assert.equal((await totp(`otpauth://totp/x?secret=${s256}&digits=8&algorithm=SHA256`, 59000)).code, '46119246');
});

test('URL matching: domain, subdomain, extra urls, trash, apps', async () => {
  const key = await unlockVault(file, 'Correct-Horse-9!');
  const e = await readEntries(file, key);
  const gh = e.find((x) => x.id === '1');
  assert.ok(matchesHost(gh, 'github.com'));
  assert.ok(matchesHost(gh, 'www.github.com'));
  assert.ok(matchesHost(gh, 'gist.github.com'));
  assert.ok(!matchesHost(gh, 'notgithub.com'));
  assert.ok(!matchesHost(gh, 'github.com.evil.io'));
  assert.deepEqual(allUrls(gh), ['https://github.com', 'androidapp://com.github.android', 'https://gist.github.com']);
  const old = e.find((x) => x.id === '2');
  assert.ok(!isLive(old));
  assert.ok(!matchesHost(old, 'old.example'));
});

// ---- in-page suggestions ----
const { pageAllowed, suggestionsFor, credentialFor } = await import('../lib/suggest.js');

test('suggestions: https/localhost only, matching entries, no passwords in the list', async () => {
  const e = await readEntries(file, await unlockVault(file, 'Correct-Horse-9!'));
  assert.ok(pageAllowed('https://github.com/login'));
  assert.ok(pageAllowed('http://localhost:3000/'));
  assert.ok(!pageAllowed('http://github.com/login'));
  assert.ok(!pageAllowed('file:///x'));
  const l = suggestionsFor(e, 'https://github.com/login');
  assert.deepEqual(l, [{ id: '1', title: 'GitHub', user: 'ibrahim' }]);
  assert.ok(!JSON.stringify(l).includes('pw-github'));
  assert.deepEqual(suggestionsFor(e, 'http://github.com/login'), []);
  assert.deepEqual(suggestionsFor(e, 'https://github.com.evil.io/'), []);
});

test('credential is released only for a page that matches the entry', async () => {
  const e = await readEntries(file, await unlockVault(file, 'Correct-Horse-9!'));
  assert.deepEqual(credentialFor(e, '1', 'https://gist.github.com/'), { user: 'ibrahim', pass: 'pw-github' });
  assert.equal(credentialFor(e, '1', 'https://evil.io/'), null);
  assert.equal(credentialFor(e, '3', 'https://github.com/'), null);   // a router entry never matches a site
  assert.equal(credentialFor(e, '1', 'http://github.com/'), null);
});

// ---- Windows-app link helpers ----
const { isNewer } = await import('../lib/desktop.js');
test('isNewer only says yes for a strictly newer version (no reload loops)', () => {
  assert.ok(isNewer('0.7.1', '0.7.0')); assert.ok(isNewer('0.10.0', '0.9.9')); assert.ok(isNewer('1.0', '0.9.9'));
  assert.ok(!isNewer('0.7.0', '0.7.0')); assert.ok(!isNewer('0.6.9', '0.7.0')); assert.ok(!isNewer('', '0.7.0')); assert.ok(!isNewer('x', '0.7.0'));
});

// ---- merge (must match vault-core VaultMerge; same vectors are asserted in Kotlin) ----
const { mergeEntries, editEntry } = await import('../lib/merge.js');
const { writeEntries, readEntries: readE2 } = await import('../lib/vault.js');
import { readFileSync as rf } from 'node:fs';
const vectors = JSON.parse(rf(new URL('./merge_vectors.json', import.meta.url), 'utf8'));
test('merge vectors: results are stable and commutative/idempotent', () => {
  for (const [name, c] of Object.entries(vectors)) {
    assert.deepEqual(mergeEntries(c.a, c.b), c.merged, name);
    assert.deepEqual(mergeEntries(c.b, c.a), c.merged, name + ' (commutative)');
    assert.deepEqual(mergeEntries(c.merged, c.merged), c.merged, name + ' (idempotent)');
  }
});
test('merge semantics (same expectations as VaultMergeTest.kt)', () => {
  const v = vectors;
  assert.equal(v.newerWins.merged[0].pass, 'new'); assert.deepEqual(v.newerWins.merged[0].hist.map((h) => h.pass), ['old']);
  assert.ok(v.deleteWins.merged[0].del && !v.deleteWins.merged[0].hist);
  assert.equal(v.historyCap.merged[0].hist.length, 20);
});
test('editEntry bumps ts and keeps the previous version in history only when content changed', () => {
  const o = { id: '1', title: 'a', user: 'u', pass: 'p', url: '', notes: '', ts: 10, del: false, cat: 'other', type: 'login', fav: false };
  const e = editEntry(o, { pass: 'q' }, 99);
  assert.equal(e.ts, 99); assert.deepEqual(e.hist.map((h) => h.pass), ['p']);
  assert.equal(editEntry(o, {}, 99).hist, undefined);
});
test('writeEntries produces a file the reader decrypts (fresh IV, same header and wk)', async () => {
  const key = await unlockVault(file, 'Correct-Horse-9!');
  const e = await readE2(file, key);
  const f2 = await writeEntries(file, key, [...e, { id: 'new', title: 'N', user: '', pass: 'x', url: '', notes: '', ts: 1, del: false, cat: 'other', type: 'login', fav: false }]);
  assert.equal(f2.wk, file.wk); assert.equal(f2.salt, file.salt); assert.notEqual(f2.data, file.data);
  assert.equal((await readE2(f2, key)).length, e.length + 1);
});

// ---- vault creation / master-password change / health / types ----
const { createVault, changeMaster } = await import('../lib/vault.js');
const { analyze, isWeak, strength } = await import('../lib/health.js');
const { typeOf, TYPES } = await import('../lib/types.js');
test('createVault + changeMaster round trip (new password opens, old one does not, entries intact)', async () => {
  globalThis.hashwasm = globalThis.hashwasm || (await import('hash-wasm').catch(() => null));
  if (!globalThis.hashwasm) return;                                   // argon2 lib not installed in this environment
  const { file, key } = await createVault('pw-one', [{ id: 'a', title: 'x' }], { m: 1024, t: 1, p: 1 });
  assert.deepEqual(await readEntries(file, await unlockVault(file, 'pw-one')), [{ id: 'a', title: 'x' }]);
  const nf = await changeMaster(file, key, 'pw-two', [{ id: 'a', title: 'x' }]);
  assert.notEqual(nf.salt, file.salt);
  assert.deepEqual(await readEntries(nf, await unlockVault(nf, 'pw-two')), [{ id: 'a', title: 'x' }]);
  await assert.rejects(unlockVault(nf, 'pw-one'));
});
test('health: weak / reused / old', () => {
  assert.ok(isWeak('short1A!') && isWeak('Password12345!') && !isWeak('r7#Kq9!zLm2$Xv'));
  assert.equal(strength('r7#Kq9!zLm2$XvAb'), 3);
  const now = 2e12, mk = (id, pass, ts) => ({ id, pass, ts });
  const r = analyze([mk(1, 'same-Pass-1!', now), mk(2, 'same-Pass-1!', now - 4e10), mk(3, '', now)], now);
  assert.equal(r.total, 2); assert.equal(r.reused.length, 1); assert.equal(r.old.length, 1);
});
test('types registry: unknown falls back to login; wifi has no user', () => {
  assert.equal(typeOf('zzz').id, 'login'); assert.equal(typeOf('wifi').hasUser, false); assert.ok(TYPES.length >= 12);
});
