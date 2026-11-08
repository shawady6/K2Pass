import { newSecret, pairText, discover, pullVault, isPrivateIp, PORT, localSubnets, wideSubnets, DEFAULT_SUBNETS } from './lib/sync.js';
import { parseVaultFile } from './lib/vault.js';
import { hydrate } from './lib/icons.js';
hydrate();

const $ = (id) => document.getElementById(id);
const say = (t, err, ok) => { $('msg').textContent = t; $('status').className = 'status' + (err ? ' bad' : ok ? ' ok' : ''); };
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
  say(`اتربط بنجاح (${pair.ip}). افتح أيقونة الإضافة واكتب الماستر باسورد.`, false, true);
}

async function loop(mySecret) {
  // Stage 1: this computer's own LAN first (WebRTC), then the usual home subnets. Stage 2 (slow): every 192.168.x.
  const mine = await localSubnets();
  const quick = [...new Set([...mine, ...DEFAULT_SUBNETS])];
  let round = 0;
  while (!done && secret === mySecret) {
    ctl = new AbortController();
    const wide = round > 0;
    say(wide
      ? 'بفحص كل الشبكات المحتملة (ممكن ياخد كام دقيقة)… سيب شاشة المزامنة مفتوحة على الموبايل.'
      : mine.length ? `بدوّر على الموبايل في شبكتك (${mine[0]}.x)… امسح الكود لو لسه.` : 'بدوّر على الموبايل في الشبكة… امسح الكود لو لسه.');
    const hit = await discover(mySecret, { signal: ctl.signal, subnets: wide ? wideSubnets(quick) : quick, concurrency: wide ? 150 : 100, probeMs: wide ? 500 : 900 });
    if (hit && secret === mySecret) return adopt(hit);
    round = round === 0 ? 1 : 0;       // quick pass, wide pass, quick pass…
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
