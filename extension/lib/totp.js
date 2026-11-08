// RFC 6238 TOTP — mirrors vault-core Totp.kt (base32 secret or otpauth:// URI).
function base32(src) {
  const s = src.toUpperCase().replace(/[\s=-]/g, '');
  if (!s) return null;
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, val = 0; const out = [];
  for (const ch of s) {
    const i = A.indexOf(ch);
    if (i < 0) return null;
    val = (val << 5) | i; bits += 5;
    if (bits >= 8) { out.push((val >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  return out.length ? Uint8Array.from(out) : null;
}

function parse(input) {
  const s = (input || '').trim();
  if (!s) return null;
  let secret = s, digits = 6, period = 30, algo = 'SHA-1';
  if (/^otpauth:\/\//i.test(s)) {
    const q = s.includes('?') ? s.slice(s.indexOf('?') + 1) : '';
    const m = {};
    for (const kv of q.split('&')) { const i = kv.indexOf('='); if (i > 0) m[kv.slice(0, i).toLowerCase()] = decodeURIComponent(kv.slice(i + 1)); }
    if (!m.secret) return null;
    secret = m.secret;
    if (m.digits) digits = Math.min(8, Math.max(6, parseInt(m.digits, 10) || 6));
    if (m.period && parseInt(m.period, 10) > 0) period = parseInt(m.period, 10);
    const a = (m.algorithm || '').toUpperCase();
    if (a === 'SHA256') algo = 'SHA-256'; else if (a === 'SHA512') algo = 'SHA-512';
  }
  const key = base32(secret);
  return key ? { key, digits, period, algo } : null;
}

/** -> {code, secondsLeft, period} or null for an invalid secret. */
export async function totp(input, nowMs = Date.now()) {
  const p = parse(input);
  if (!p) return null;
  const counter = BigInt(Math.floor(nowMs / 1000 / p.period));
  const msg = new Uint8Array(8);
  new DataView(msg.buffer).setBigUint64(0, counter);
  const key = await crypto.subtle.importKey('raw', p.key, { name: 'HMAC', hash: p.algo }, false, ['sign']);
  const h = new Uint8Array(await crypto.subtle.sign('HMAC', key, msg));
  const off = h[h.length - 1] & 0x0f;
  const bin = ((h[off] & 0x7f) << 24) | (h[off + 1] << 16) | (h[off + 2] << 8) | h[off + 3];
  const code = String(bin % 10 ** p.digits).padStart(p.digits, '0');
  return { code, secondsLeft: p.period - (Math.floor(nowMs / 1000) % p.period), period: p.period };
}
