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

// ---- LAN sync client ----
const { parsePair, macHex, pullVault } = await import('../lib/sync.js');
import http from 'node:http';
import { createHmac } from 'node:crypto';

const SECRET_B64URL = Buffer.from(Uint8Array.from({ length: 32 }, (_, i) => i)).toString('base64url');

test('HMAC vector equals the Kotlin server vector (vault-core LanSyncServerTest)', async () => {
  assert.equal(await macHex(SECRET_B64URL, 'v|abc123'), 'c014485e217b5e52e47739d96c38968362350723cffe5695d34aa737f274d2af');
});

test('parsePair accepts private-network links only', () => {
  const ok = parsePair(`k2sync://192.168.1.20:8765/${SECRET_B64URL}`);
  assert.deepEqual([ok.ip, ok.port, ok.secret], ['192.168.1.20', 8765, SECRET_B64URL]);
  assert.ok(parsePair(`k2sync://10.0.0.5:9000/${SECRET_B64URL}`));
  assert.ok(parsePair(`k2sync://172.20.1.1:9000/${SECRET_B64URL}`));
  assert.equal(parsePair(`k2sync://8.8.8.8:8765/${SECRET_B64URL}`), null);       // public IP refused
  assert.equal(parsePair(`k2sync://172.40.1.1:8765/${SECRET_B64URL}`), null);
  assert.equal(parsePair(`k2sync://192.168.1.999:8765/${SECRET_B64URL}`), null);
  assert.equal(parsePair('https://192.168.1.2:8765/x'), null);
  assert.equal(parsePair('k2sync://192.168.1.2:8765/short'), null);
});

test('pullVault speaks the protocol (mock phone) and handles denial / offline', async () => {
  const nonces = new Set();
  const srv = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    if (u.pathname === '/n') { const n = 'ab' + nonces.size + 'cd'; nonces.add(n); return res.end(n); }
    if (u.pathname === '/v') {
      const [n, mac] = (u.searchParams.get('a') || '').split('.');
      const want = createHmac('sha256', Buffer.from(SECRET_B64URL, 'base64url')).update('v|' + n).digest('hex');
      if (nonces.delete(n) && mac === want) return res.end(readFileSync(new URL('./fixture.vault', import.meta.url)));
    }
    res.statusCode = 403; res.end('forbidden');
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const port = srv.address().port;
  try {
    const got = await pullVault({ ip: '127.0.0.1', port, secret: SECRET_B64URL });
    assert.equal(got, text);
    await assert.rejects(pullVault({ ip: '127.0.0.1', port, secret: Buffer.alloc(32, 1).toString('base64url') }), /denied/);
  } finally { srv.close(); }
  await assert.rejects(pullVault({ ip: '127.0.0.1', port, secret: SECRET_B64URL }, 1000), /offline/);
});

// ---- discovery (extension shows QR, phone scans, extension finds the phone) ----
const { newSecret, pairText, discover, isPrivateIp } = await import('../lib/sync.js');

test('newSecret / pairText shape', () => {
  const a = newSecret(), b = newSecret();
  assert.match(a, /^[A-Za-z0-9_-]{43}$/);
  assert.notEqual(a, b);
  assert.equal(pairText(a), 'k2pair://' + a);
});

test('discover finds the phone among other hosts, ignores strangers, never leaves private ranges', async () => {
  const phoneIp = '192.168.43.77';
  const secret = newSecret();
  const asked = [];
  const phone = async (url, opts) => {   // fake fetch: only the phone answers; a stranger on :8765 answers /n but denies /v
    const u = new URL(url); asked.push(u.hostname);
    if (u.hostname === '192.168.43.20') return u.pathname === '/n' ? new Response('aa11') : new Response('no', { status: 403 });
    if (u.hostname !== phoneIp) throw new TypeError('unreachable');
    if (u.pathname === '/n') return new Response('0123456789abcdef0123456789abcdef');
    const [n, mac] = u.searchParams.get('a').split('.');
    const want = await macHex(secret, 'v|' + n);
    return mac === want ? new Response(text) : new Response('no', { status: 403 });
  };
  const hit = await discover(secret, { fetchImpl: phone, probeMs: 500 });
  assert.equal(hit.pair.ip, phoneIp);
  assert.equal(hit.vault, text);
  assert.ok(asked.every(isPrivateIp));
  // prefer: the previous IP's /24 is tried first when the phone moved within the same subnet
  const hit2 = await discover(secret, { fetchImpl: phone, prefer: '192.168.43.5', subnets: [], probeMs: 500 });
  assert.equal(hit2.pair.ip, phoneIp);
  // nobody home -> null
  assert.equal(await discover(secret, { fetchImpl: async () => { throw new TypeError('x'); }, subnets: ['10.9.9'], probeMs: 200 }), null);
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

// ---- local subnet detection ----
const { subnetsFromCandidates, wideSubnets } = await import('../lib/sync.js');
test('subnets come from private ICE candidates only (mDNS and public ignored)', () => {
  const l = [
    'candidate:1 1 udp 2113937151 192.168.31.57 54321 typ host',
    'candidate:2 1 udp 2113937151 abcd-1234.local 54322 typ host',
    'candidate:3 1 udp 1677729535 41.196.10.5 54323 typ srflx raddr 0.0.0.0',
    'c=IN IP4 10.20.30.4',
    'candidate:4 1 udp 2113937151 192.168.31.99 54324 typ host',
  ];
  assert.deepEqual(subnetsFromCandidates(l), ['192.168.31', '10.20.30']);
  assert.deepEqual(subnetsFromCandidates(['candidate:x 1 udp 1 abc.local 1 typ host']), []);
});
test('wide sweep covers 192.168.0-255 minus what was already tried', () => {
  const w = wideSubnets(['192.168.1', '192.168.0']);
  assert.equal(w.length, 254); assert.ok(!w.includes('192.168.1')); assert.ok(w.includes('192.168.31'));
});
