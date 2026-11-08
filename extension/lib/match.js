// Entry helpers — mirrors the app's Matcher / Entry.allUrls().
export const allUrls = (e) =>
  [e.url || '', ...((e.extra && e.extra.urls) || '').split('\n')].map((x) => x.trim()).filter(Boolean);

const hostOf = (u) => {
  try { return new URL(/^[a-z]+:\/\//i.test(u) ? u : 'https://' + u).hostname.toLowerCase().replace(/^www\./, ''); } catch { return null; }
};

/** True when a login should be offered for the page host (domain or any subdomain of the saved host). */
export function matchesHost(e, pageHost) {
  if (e.del || e.trash > 0 || (e.type || 'login') !== 'login' || !pageHost) return false;
  const d = pageHost.toLowerCase().replace(/^www\./, '');
  return allUrls(e).some((u) => {
    if (u.toLowerCase().startsWith('androidapp://')) return false;
    const h = hostOf(u);
    return !!h && (d === h || d.endsWith('.' + h));
  });
}

export const isLive = (e) => !e.del && !(e.trash > 0);
export const FOLDER_TYPE = 'folder';
export const folderKey = (f) => 'f:' + f.id;
