// Link to the K2Pass Windows app running on the same computer (127.0.0.1:8767, loopback only).
// The app serves its encrypted vault file and the version of the extension it ships; it only answers this extension's origin.
export const DESKTOP = 'http://127.0.0.1:8767';

async function getText(path, ms) {
  const c = new AbortController(); const t = setTimeout(() => c.abort(), ms);
  try { const r = await fetch(DESKTOP + path, { signal: c.signal, cache: 'no-store' }); return r.ok ? await r.text() : null; }
  catch { return null; } finally { clearTimeout(t); }
}

/** {app:'k2pass', ext:'0.7.0', hasVault:true} or null when the Windows app isn't running. */
export async function desktopInfo(ms = 800) {
  const t = await getText('/info', ms);
  try { const j = t && JSON.parse(t); return j && j.app === 'k2pass' ? j : null; } catch { return null; }
}
export const desktopVault = (ms = 4000) => getText('/vault', ms);

/** True when [a] is a strictly newer dotted version than [b] ("0.7.1" > "0.7.0"). Never true for equal/older/garbage. */
export function isNewer(a, b) {
  const p = (v) => String(v || '').split('.').map((x) => parseInt(x, 10));
  const x = p(a), y = p(b);
  if (x.some(Number.isNaN) || y.some(Number.isNaN)) return false;
  for (let i = 0; i < Math.max(x.length, y.length); i++) { const d = (x[i] || 0) - (y[i] || 0); if (d) return d > 0; }
  return false;
}
