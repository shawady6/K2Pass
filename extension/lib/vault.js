// K2Pass vault file reader (format v1) — mirrors vault-core VaultCrypto.kt.
//   file = {"v":1,"m":KiB,"t":iterations,"p":parallelism,"salt":b64,"wk":b64,"data":b64}
//   KEK = Argon2id(password UTF-8, salt, m, t, p) -> 32 bytes
//   wk / data = iv(12) || AES-256-GCM ciphertext+tag, AAD = "v|m|t|p|b64(salt)"
const enc = new TextEncoder();
const dec = new TextDecoder();

export const b64d = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
export const b64e = (u8) => { let s = ''; for (const b of u8) s += String.fromCharCode(b); return btoa(s); };

export function parseVaultFile(text) {
  let o;
  try { o = JSON.parse(text); } catch { throw new Error('الملف مش ملف خزنة K2Pass'); }
  if (!o || o.v !== 1 || !o.salt || !o.wk || !o.data) throw new Error('الملف مش ملف خزنة K2Pass (أو نسخته مش مدعومة)');
  for (const k of ['m', 't', 'p']) if (!Number.isInteger(o[k]) || o[k] <= 0) throw new Error('إعدادات التشفير في الملف غير صالحة');
  if (o.m > 1048576) throw new Error('إعدادات التشفير في الملف مبالغ فيها'); // refuse > 1 GiB
  return o;
}

const aad = (f) => enc.encode(`${f.v}|${f.m}|${f.t}|${f.p}|${f.salt}`);

async function aesGcmDecrypt(keyBytes, blob, aadBytes) {
  if (blob.length <= 12 + 16) throw new Error('corrupt');
  const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-GCM', false, ['decrypt']);
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: blob.slice(0, 12), additionalData: aadBytes, tagLength: 128 }, key, blob.slice(12));
  return new Uint8Array(pt);
}

/** Returns the 32-byte vault key; throws 'wrong' on a wrong master password or a tampered file. */
export async function unlockVault(file, password) {
  const hw = globalThis.hashwasm;
  if (!hw) throw new Error('argon2 غير محمّل');
  const kek = await hw.argon2id({
    password, salt: b64d(file.salt), parallelism: file.p, iterations: file.t,
    memorySize: file.m, hashLength: 32, outputType: 'binary',
  });
  try {
    return await aesGcmDecrypt(kek, b64d(file.wk), aad(file));
  } catch {
    throw new Error('wrong');
  } finally {
    kek.fill(0);
  }
}

/** Decrypts the entries (raw JSON objects exactly as stored by the app). */
export async function readEntries(file, vaultKey) {
  const pt = await aesGcmDecrypt(vaultKey, b64d(file.data), aad(file));
  return JSON.parse(dec.decode(pt)).entries;
}

async function aesGcmEncrypt(keyBytes, plain, aadBytes) {
  const key = await crypto.subtle.importKey('raw', keyBytes, 'AES-GCM', false, ['encrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: aadBytes, tagLength: 128 }, key, plain));
  const out = new Uint8Array(12 + ct.length); out.set(iv); out.set(ct, 12);
  return out;
}

/** New vault file = [file]'s header and wrapped key + [entries] re-encrypted with a fresh IV (mirrors VaultCrypto.writePayload). */
export async function writeEntries(file, vaultKey, entries) {
  const data = await aesGcmEncrypt(vaultKey, enc.encode(JSON.stringify({ entries })), aad(file));
  return { ...file, data: b64e(data) };
}

export const DEFAULT_KDF = { m: 65536, t: 3, p: 2 };   // same as vault-core KdfParams()

async function kekFor(password, salt, f) {
  const hw = globalThis.hashwasm;
  if (!hw) throw new Error('argon2 غير محمّل');
  return hw.argon2id({ password, salt, parallelism: f.p, iterations: f.t, memorySize: f.m, hashLength: 32, outputType: 'binary' });
}

/** Brand-new vault (mirrors VaultCrypto.create). Returns { file, key }. */
export async function createVault(password, entries = [], kdf = DEFAULT_KDF) {
  const salt = crypto.getRandomValues(new Uint8Array(16)), key = crypto.getRandomValues(new Uint8Array(32));
  const f = { v: 1, m: kdf.m, t: kdf.t, p: kdf.p, salt: b64e(salt) };
  const kek = await kekFor(password, salt, f);
  try {
    const wk = await aesGcmEncrypt(kek, key, aad(f));
    const data = await aesGcmEncrypt(key, enc.encode(JSON.stringify({ entries })), aad(f));
    return { file: { ...f, wk: b64e(wk), data: b64e(data) }, key };
  } finally { kek.fill(0); }
}

/** New salt + new KEK; the vault key and entries stay the same (mirrors VaultCrypto.changeMasterPassword). */
export async function changeMaster(file, vaultKey, newPassword, entries) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const f = { v: file.v, m: file.m, t: file.t, p: file.p, salt: b64e(salt) };
  const kek = await kekFor(newPassword, salt, f);
  try {
    const wk = await aesGcmEncrypt(kek, vaultKey, aad(f));
    const data = await aesGcmEncrypt(vaultKey, enc.encode(JSON.stringify({ entries })), aad(f));
    return { ...f, wk: b64e(wk), data: b64e(data) };
  } finally { kek.fill(0); }
}
