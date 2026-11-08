import { parseVaultFile, readEntries, b64d } from './lib/vault.js';
import { suggestionsFor, credentialFor } from './lib/suggest.js';

// Auto-lock: the unlocked vault key lives only in chrome.storage.session and is wiped when the alarm fires.
chrome.alarms.onAlarm.addListener((a) => {
  if (a.name !== 'k2-lock') return;
  chrome.storage.session.remove(['k', 'until']);
});

// Decrypted entries for the page-suggestion feature; null while locked.
async function unlockedEntries() {
  const s = await chrome.storage.session.get(['k', 'until']);
  if (!s.k || !(s.until > Date.now())) return null;
  const { vault } = await chrome.storage.local.get('vault');
  if (!vault) return null;
  try { return await readEntries(parseVaultFile(vault), b64d(s.k)); } catch { return null; }
}

chrome.runtime.onMessage.addListener((m, sender, reply) => {
  if (m && m.type === 'schedule-lock') chrome.alarms.create('k2-lock', { when: m.when });
  if (m && m.type === 'lock-now') { chrome.alarms.clear('k2-lock'); chrome.storage.session.remove(['k', 'until']); }
  // Page → background. The URL always comes from the browser (sender.tab), never from the page's own message.
  if (m && (m.type === 'suggest' || m.type === 'get-cred') && sender.tab && sender.frameId === 0 && sender.url) {
    (async () => {
      const entries = await unlockedEntries();
      if (!entries) return reply({ locked: true, list: [] });
      const url = sender.tab.url || sender.url;
      if (m.type === 'suggest') return reply({ list: suggestionsFor(entries, url) });
      reply({ cred: credentialFor(entries, String(m.id), url) });
    })().catch(() => reply({ list: [] }));
    return true;
  }
});
