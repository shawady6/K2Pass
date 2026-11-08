// Injected into the page with chrome.scripting.executeScript({func: fillInPage, args}) — must be self-contained.
export function fillInPage(user, pass) {
  const visible = (el) => {
    const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' && !el.disabled && !el.readOnly;
  };
  const setVal = (el, v) => {
    const proto = Object.getPrototypeOf(el);
    const d = Object.getOwnPropertyDescriptor(proto, 'value');
    el.focus();
    d && d.set ? d.set.call(el, v) : (el.value = v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };
  const pw = [...document.querySelectorAll('input[type=password]')].find(visible);
  const texts = [...document.querySelectorAll('input')].filter(visible)
    .filter((i) => ['', 'text', 'email', 'tel'].includes((i.getAttribute('type') || '').toLowerCase()));
  let userEl = null;
  if (pw) {
    const before = texts.filter((i) => i.compareDocumentPosition(pw) & Node.DOCUMENT_POSITION_FOLLOWING);
    userEl = before[before.length - 1] || null;
  } else {
    userEl = texts[0] || null; // username-only step of a multi-page login
  }
  let filled = 0;
  if (userEl && user) { setVal(userEl, user); filled++; }
  if (pw && pass) { setVal(pw, pass); filled++; }
  return filled;
}
