// In-page suggestions. Pure helpers shared by background.js (and tested in Node).
import { matchesHost } from './match.js';

/** Only secure pages (or localhost) get suggestions / fills from the page itself; the popup ⚡ still works anywhere. */
export function pageAllowed(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' || (u.protocol === 'http:' && (u.hostname === 'localhost' || u.hostname === '127.0.0.1'));
  } catch { return false; }
}

/** Logins matching the page URL, as metadata only (NO passwords). */
export function suggestionsFor(entries, url) {
  if (!pageAllowed(url)) return [];
  const host = new URL(url).hostname;
  return entries.filter((e) => matchesHost(e, host))
    .sort((a, b) => (b.fav ? 1 : 0) - (a.fav ? 1 : 0) || (a.title || '').localeCompare(b.title || ''))
    .slice(0, 6)
    .map((e) => ({ id: e.id, title: e.title || '', user: e.user || '' }));
}

/** The credential for [id], but only if that entry really matches the page that asks (never trust the content script). */
export function credentialFor(entries, id, url) {
  if (!pageAllowed(url)) return null;
  const e = entries.find((x) => x.id === id);
  return e && matchesHost(e, new URL(url).hostname) ? { user: e.user || '', pass: e.pass || '' } : null;
}
