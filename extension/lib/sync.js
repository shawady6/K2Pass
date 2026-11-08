// LAN sync client — talks to the phone's LanSyncServer (vault-core). Protocol (GET only):
//   /n -> nonce ;  /v?a=<nonce>.<hex(HMAC-SHA256(secret, "v|"+nonce))> -> vault file (encrypted JSON)
// The pairing secret comes from the QR shown by the phone:  k2sync://<private-ip>:<port>/<secret base64url>
const PRIVATE = /^(10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)$/;

export function parsePair(text) {
  const m = /^k2sync:\/\/([0-9.]+):(\d{2,5})\/([A-Za-z0-9_-]{43})$/.exec((text || '').trim());
  if (!m) return null;
  const [, ip, port, secret] = m;
  if (!PRIVATE.test(ip) || +port > 65535 || ip.split('.').some((o) => +o > 255)) return null; // never talk to public hosts
  return { ip, port: +port, secret };
}

const unb64url = (s) => { const b = atob(s.replace(/-/g, '+').replace(/_/g, '/')); return Uint8Array.from(b, (c) => c.charCodeAt(0)); };

export async function macHex(secretB64url, msg) {
  const key = await crypto.subtle.importKey('raw', unb64url(secretB64url), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = new Uint8Array(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(msg)));
  return [...sig].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Downloads the encrypted vault file text from the paired phone. Throws Error('offline' | 'denied' | ...) */
export async function pullVault(pair, timeoutMs = 4000, fetchImpl = fetch) {
  const base = `http://${pair.ip}:${pair.port}`;
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    let r;
    try { r = await fetchImpl(`${base}/n`, { signal: ctl.signal, cache: 'no-store' }); } catch { throw new Error('offline'); }
    if (!r.ok) throw new Error('offline');
    const nonce = (await r.text()).trim();
    const mac = await macHex(pair.secret, `v|${nonce}`);
    try { r = await fetchImpl(`${base}/v?a=${nonce}.${mac}`, { signal: ctl.signal, cache: 'no-store' }); } catch { throw new Error('offline'); }
    if (r.status === 403) throw new Error('denied');
    if (!r.ok) throw new Error('offline');
    return await r.text();
  } finally { clearTimeout(t); }
}
