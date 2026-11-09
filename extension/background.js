import { parseVaultFile, readEntries, b64d } from './lib/vault.js';
import { suggestionsFor, credentialFor } from './lib/suggest.js';
import { desktopInfo, desktopVault, isNewer } from './lib/desktop.js';

// Windows-app link: once a minute (and at start) ask the local K2Pass app for its vault and for the extension version it ships.
// New vault -> stored (the popup asks for the master password again if the key no longer fits). Newer extension files -> reload.
async function desktopTick() {
  const info = await desktopInfo();
  if (!info) return;
  if (isNewer(info.ext, chrome.runtime.getManifest().version)) { chrome.runtime.reload(); return; }
  if (!info.hasVault) return;
  const text = await desktopVault();
  if (!text) return;
  const { vault } = await chrome.storage.local.get('vault');
  if (text !== vault) { try { parseVaultFile(text); await chrome.storage.local.set({ vault: text }); } catch { /* ignore garbage */ } }
}
chrome.alarms.create('k2-desktop', { periodInMinutes: 1 });
chrome.runtime.onStartup.addListener(desktopTick);
chrome.runtime.onInstalled.addListener(desktopTick);
desktopTick();

// Auto-lock: the unlocked vault key lives only in chrome.storage.session and is wiped when the alarm fires.
chrome.alarms.onAlarm.addListener((a) => {
  if (a.name === 'k2-desktop') return void desktopTick();
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
