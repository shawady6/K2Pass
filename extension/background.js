// Auto-lock: the unlocked vault key lives only in chrome.storage.session and is wiped when the alarm fires.
chrome.alarms.onAlarm.addListener((a) => {
  if (a.name !== 'k2-lock') return;
  chrome.storage.session.remove(['k', 'until']);
});
chrome.runtime.onMessage.addListener((m) => {
  if (m && m.type === 'schedule-lock') chrome.alarms.create('k2-lock', { when: m.when });
  if (m && m.type === 'lock-now') { chrome.alarms.clear('k2-lock'); chrome.storage.session.remove(['k', 'until']); }
});
