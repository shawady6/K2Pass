import { newSecret, pairText, discover, pullVault, isPrivateIp, PORT } from './lib/sync.js';
import { parseVaultFile } from './lib/vault.js';

const $ = (id) => document.getElementById(id);
const say = (t, err) => { $('msg').textContent = t; $('msg').className = err ? 'err' : 'hint'; };
let secret = null, ctl = null, done = false;

function drawQr(text) {
  const q = qrcode(0, 'M'); q.addData(text); q.make();
  $('qr').dataset.pair = text;
  $('qr').innerHTML = q.createSvgTag({ cellSize: 4, margin: 0, scalable: true });
}

async function adopt({ pair, vault }) {
  parseVaultFile(vault);
  await chrome.storage.local.set({ pair, vault });
  await chrome.storage.session.remove(['k', 'until']);   // the old vault key belongs to the old file
  done = true; if (ctl) ctl.abort();
  say(`اتربط ✔ (${pair.ip}). افتح أيقونة الإضافة واكتب الماستر باسورد.`);
}

async function loop(mySecret) {
  // Keep looking until the phone answers (it only starts answering after the QR is scanned).
  while (!done && secret === mySecret) {
    ctl = new AbortController();
    say('بدوّر على الموبايل في الشبكة… (امسح الكود لو لسه)');
    const hit = await discover(mySecret, { signal: ctl.signal });
    if (hit && secret === mySecret) return adopt(hit);
    await new Promise((r) => setTimeout(r, 2500));
  }
}

function fresh() {
  if (ctl) ctl.abort();
  secret = newSecret(); done = false;
  drawQr(pairText(secret));
  loop(secret);
}

$('use-manual').onclick = async () => {
  const m = /^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{2,5}))?$/.exec($('manual').value.trim());
  if (!m || !isPrivateIp(m[1])) return say('اكتب عنوان شبكة محلية زي 192.168.1.20:8765', true);
  const pair = { ip: m[1], port: +(m[2] || PORT), secret };
  try { const vault = await pullVault(pair, 6000); await adopt({ pair, vault }); }
  catch (e) { say(e.message === 'denied' ? 'الموبايل لسه ما مسحش الكود ده.' : 'مقدرتش أوصل للعنوان ده.', true); }
};
$('new-code').onclick = fresh;
window.addEventListener('pagehide', () => { done = true; if (ctl) ctl.abort(); });
fresh();
