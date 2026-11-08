// LAN sync client — talks to the phone's LanSyncServer (vault-core). Protocol (GET only):
//   /n -> nonce ;  /v?a=<nonce>.<hex(HMAC-SHA256(secret, "v|"+nonce))> -> vault file (encrypted JSON)
// Pairing: the EXTENSION shows a QR  k2pair://<secret base64url>  and the phone scans it. The browser cannot accept
// connections, so afterwards the extension finds the phone on the LAN (port 8765, see discover()).
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

export const PORT = 8765;
export const newSecret = () => {
  const b = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};
export const pairText = (secret) => `k2pair://${secret}`;
export const isPrivateIp = (ip) => PRIVATE.test(ip) && ip.split('.').every((o) => +o <= 255);

// Most common home / hotspot subnets, most likely first (Android hotspot 192.168.43.x, iPhone hotspot 172.20.10.x).
export const DEFAULT_SUBNETS = ['192.168.1', '192.168.0', '192.168.43', '10.0.0', '172.20.10', '192.168.100', '192.168.8', '192.168.2',
  '192.168.10', '192.168.3', '10.0.1', '192.168.4', '192.168.178', '192.168.88', '10.1.1', '192.168.5'];

/**
 * Looks for the paired phone on the LAN: probes http://<ip>:8765/n on each candidate address and, for every answer,
 * proves knowledge of the secret by downloading the vault. Returns {pair, vault} or null. Only private addresses are touched.
 * `subnets` = list like '192.168.1' (scans .1–.254); `prefer` = an IP whose /24 is scanned first.
 */
export async function discover(secret, { subnets = DEFAULT_SUBNETS, prefer = null, probeMs = 900, concurrency = 100, signal = null, fetchImpl = fetch } = {}) {
  const list = [...subnets];
  if (prefer && isPrivateIp(prefer)) { const p = prefer.split('.').slice(0, 3).join('.'); list.unshift(p); }
  const seen = new Set(), ips = [];
  for (const sn of list) if (!seen.has(sn)) { seen.add(sn); for (let i = 1; i <= 254; i++) ips.push(`${sn}.${i}`); }
  if (prefer && isPrivateIp(prefer)) ips.unshift(prefer);
  let next = 0, found = null;
  async function worker() {
    while (!found && !(signal && signal.aborted)) {
      const ip = ips[next++]; if (!ip) return;
      try {
        const pair = { ip, port: PORT, secret };
        const vault = await pullVault(pair, probeMs, fetchImpl);
        if (!found) found = { pair, vault };
      } catch (e) { /* offline / denied -> not our phone */ }
    }
  }
  await Promise.all(Array.from({ length: concurrency }, worker));
  return found;
}

/** Pure: /24 prefixes ("192.168.31") of private IPv4 addresses found in ICE candidate lines (mDNS ".local" names are ignored). */
export function subnetsFromCandidates(lines) {
  const out = [];
  for (const l of lines) for (const ip of l.match(/\b\d{1,3}(?:\.\d{1,3}){3}\b/g) || []) {
    if (!isPrivateIp(ip)) continue;
    const sn = ip.split('.').slice(0, 3).join('.');
    if (!out.includes(sn)) out.push(sn);
  }
  return out;
}

/** This computer's real LAN /24(s), via a throw-away WebRTC connection (no traffic leaves the machine). [] when hidden/unavailable. */
export async function localSubnets(timeoutMs = 1500) {
  if (typeof RTCPeerConnection === 'undefined') return [];
  const pc = new RTCPeerConnection({ iceServers: [] });
  const lines = [];
  try {
    pc.createDataChannel('k2');
    pc.onicecandidate = (e) => { if (e.candidate) lines.push(e.candidate.candidate); };
    await pc.setLocalDescription(await pc.createOffer());
    await new Promise((r) => { const t = setTimeout(r, timeoutMs); pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') { clearTimeout(t); r(); } }; });
    lines.push(...(pc.localDescription.sdp.split('\n')));
  } catch { /* blocked */ } finally { pc.close(); }
  return subnetsFromCandidates(lines);
}

/** Every 192.168.x /24 not in [skip] — the slow, last-resort sweep for unusual router setups. */
export const wideSubnets = (skip = []) => Array.from({ length: 256 }, (_, i) => `192.168.${i}`).filter((x) => !skip.includes(x));
