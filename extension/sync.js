import { parsePair, pullVault } from './lib/sync.js';
import { parseVaultFile } from './lib/vault.js';

const $ = (id) => document.getElementById(id);
const say = (t, err) => { $('msg').textContent = t; $('msg').className = err ? 'err' : 'hint'; };
let stream = null, timer = null;

async function finish(text) {
  const pair = parsePair(text);
  if (!pair) return say('الكود ده مش كود ربط K2Pass صالح (لازم يكون على شبكة محلية).', true);
  say('بوصّل بالموبايل…');
  try {
    const vault = await pullVault(pair, 6000);
    parseVaultFile(vault);
    await chrome.storage.local.set({ pair, vault });
    await chrome.storage.session.remove(['k', 'until']);      // vault key belongs to the old file; ask for the master password again
    say('اتربط واتسحبت الخزنة ✔. افتح أيقونة الإضافة واكتب الماستر باسورد.');
    stopCamera();
  } catch (e) {
    say(e.message === 'denied' ? 'الموبايل رفض الكود (اتلغى الربط؟). شغّل المزامنة وامسح كود جديد.'
      : 'مقدرتش أوصل للموبايل. اتأكد إن المزامنة شغّالة في التطبيق وإنكم على نفس الواي فاي.', true);
  }
}

function stopCamera() {
  clearInterval(timer); timer = null;
  if (stream) stream.getTracks().forEach((t) => t.stop()); stream = null;
  $('video').classList.add('hidden');
}

$('start').onclick = async () => {
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
  } catch { return say('مقدرتش أفتح الكاميرا. اسمح بالكاميرا أو الصق الرابط.', true); }
  const v = $('video'); v.srcObject = stream; v.classList.remove('hidden'); await v.play();
  const c = document.createElement('canvas'); const g = c.getContext('2d', { willReadFrequently: true });
  say('وجّه الكاميرا على الكود…');
  timer = setInterval(() => {
    if (!v.videoWidth) return;
    c.width = v.videoWidth; c.height = v.videoHeight; g.drawImage(v, 0, 0);
    const img = g.getImageData(0, 0, c.width, c.height);
    const q = jsQR(img.data, img.width, img.height);
    if (q && q.data.startsWith('k2sync://')) { clearInterval(timer); finish(q.data); }
  }, 250);
};
$('use-manual').onclick = () => finish($('manual').value);
window.addEventListener('pagehide', stopCamera);
