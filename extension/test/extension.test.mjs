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
