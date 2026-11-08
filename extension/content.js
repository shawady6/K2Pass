// K2Pass in-page suggestions: when a login field gets focus, show matching accounts (no passwords are sent to the page
// until you click one). Runs only in the top frame; the background script re-checks the page URL itself.
(() => {
  const TEXTY = ['', 'text', 'email', 'tel'];
  const visible = (el) => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && !el.disabled && !el.readOnly; };
  const pwFields = () => [...document.querySelectorAll('input[type=password]')].filter(visible);
  const isLoginField = (el) => {
    if (!(el instanceof HTMLInputElement) || !visible(el)) return false;
    const t = (el.getAttribute('type') || '').toLowerCase();
    if (t === 'password') return true;
    if (!TEXTY.includes(t)) return false;
    const hint = `${el.autocomplete} ${el.name} ${el.id} ${el.placeholder}`.toLowerCase();
    return /username|email|user|login|mail|account|phone|identifier/.test(hint) || pwFields().length > 0;
  };

  let host = null, box = null, items = [], sel = 0, target = null, filling = false;
  const css = `:host{all:initial}.p{position:fixed;z-index:2147483647;direction:rtl;font:13px/1.4 system-ui,'Segoe UI',Tahoma,sans-serif;background:rgba(20,24,56,.98);color:#eceefb;border:1px solid rgba(255,255,255,.14);border-radius:14px;box-shadow:0 12px 36px rgba(0,0,0,.45);padding:5px;min-width:230px;max-width:340px}
.h{font-size:11px;color:#b3a8ff;padding:4px 8px 2px;font-weight:700}
.i{display:flex;align-items:center;gap:9px;padding:7px 8px;border-radius:10px;cursor:pointer}
.i.on,.i:hover{background:rgba(179,168,255,.16)}
.a{width:28px;height:28px;border-radius:9px;background:linear-gradient(145deg,#6c5ce7,#8b7bff);display:flex;align-items:center;justify-content:center;font-weight:700;flex:none;color:#fff}
.t{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.s{font-size:11.5px;color:#98a0c4;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.x{min-width:0}`;

  function hide() { if (host) { host.remove(); host = box = null; } items = []; sel = 0; }
  function place() {
    if (!box || !target) return;
    const r = target.getBoundingClientRect();
    box.style.top = Math.min(r.bottom + 4, innerHeight - box.offsetHeight - 6) + 'px';
    box.style.left = Math.max(6, Math.min(r.left, innerWidth - box.offsetWidth - 6)) + 'px';
    box.style.minWidth = Math.max(230, r.width) + 'px';
  }
  function paint() {
    box.querySelectorAll('.i').forEach((n, i) => n.classList.toggle('on', i === sel));
  }
  function show(list) {
    hide(); items = list; sel = 0;
    host = document.createElement('k2pass-suggest');
    const root = host.attachShadow({ mode: 'closed' });
    const st = document.createElement('style'); st.textContent = css;
    box = document.createElement('div'); box.className = 'p';
    const h = document.createElement('div'); h.className = 'h'; h.textContent = 'K2Pass'; box.append(h);
    list.forEach((e, i) => {
      const row = document.createElement('div'); row.className = 'i';
      const a = document.createElement('div'); a.className = 'a'; a.textContent = (e.title || '?').trim().slice(0, 1).toUpperCase();
      const x = document.createElement('div'); x.className = 'x';
      const t = document.createElement('div'); t.className = 't'; t.textContent = e.title;
      const s = document.createElement('div'); s.className = 's'; s.textContent = e.user;
      x.append(t, s); row.append(a, x);
      row.addEventListener('mousedown', (ev) => { ev.preventDefault(); pick(i); });
      row.addEventListener('mousemove', () => { sel = i; paint(); });
      box.append(row);
    });
    root.append(st, box); document.documentElement.append(host);
    paint(); place();
  }

  const setVal = (el, v) => {
    const d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
    el.focus(); d && d.set ? d.set.call(el, v) : (el.value = v);
    el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true }));
  };
  function fill(cred) {
    const pw = pwFields()[0] || null;
    const texts = [...document.querySelectorAll('input')].filter(visible).filter((i) => TEXTY.includes((i.getAttribute('type') || '').toLowerCase()));
    let userEl = null;
    if (pw) { const before = texts.filter((i) => i.compareDocumentPosition(pw) & Node.DOCUMENT_POSITION_FOLLOWING); userEl = before[before.length - 1] || null; }
    else userEl = target && TEXTY.includes((target.getAttribute('type') || '').toLowerCase()) ? target : texts[0] || null;
    if (userEl && cred.user) setVal(userEl, cred.user);
    if (pw && cred.pass) setVal(pw, cred.pass);
  }
  async function pick(i) {
    const e = items[i]; hide(); if (!e) return;
    try { const r = await chrome.runtime.sendMessage({ type: 'get-cred', id: e.id }); if (r && r.cred) { filling = true; try { fill(r.cred); } finally { setTimeout(() => { filling = false; hide(); }, 300); } } } catch { /* extension reloaded */ }
  }

  async function onFocus(ev) {
    const el = ev.target;
    if (filling || !isLoginField(el)) return;
    target = el;
    try {
      const r = await chrome.runtime.sendMessage({ type: 'suggest' });
      if (document.activeElement === el && r && r.list && r.list.length) show(r.list);
    } catch { /* extension reloaded / context invalidated */ }
  }
  document.addEventListener('focusin', onFocus, true);
  document.addEventListener('focusout', () => setTimeout(() => { if (!host || document.activeElement !== target) hide(); }, 120), true);
  document.addEventListener('keydown', (ev) => {
    if (!box) return;
    if (ev.key === 'Escape') return hide();
    if (ev.key === 'ArrowDown' || ev.key === 'ArrowUp') { ev.preventDefault(); sel = (sel + (ev.key === 'ArrowDown' ? 1 : items.length - 1)) % items.length; paint(); }
    else if (ev.key === 'Enter' && items.length) { ev.preventDefault(); pick(sel); }
  }, true);
  addEventListener('scroll', place, true); addEventListener('resize', place);
})();
