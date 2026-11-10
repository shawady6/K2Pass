// Vault health — port of Health.kt (weak / reused / old), offline.
const COMMON = ['password', '123456', 'qwerty', 'admin', 'letmein', 'welcome', 'iloveyou', 'abc123', '111111', '000000', '1q2w3e', 'monkey', 'dragon', 'login', 'passw0rd', 'p@ssw0rd'];
const YEAR = 365 * 24 * 3600 * 1000;
const classes = (p) => [/\p{Ll}/u, /\p{Lu}/u, /\p{Nd}/u, /[^\p{L}\p{Nd}]/u].filter((r) => r.test(p)).length;
export function isWeak(p) {
  if ([...p].length < 10) return true;
  if (classes(p) < 3) return true;
  if (new Set([...p]).size <= 3) return true;
  const l = p.toLowerCase();
  return COMMON.some((c) => l.includes(c));
}
/** 0 weak, 1 fair, 2 good, 3 strong */
export function strength(p) {
  if (isWeak(p)) return [...p].length < 8 ? 0 : 1;
  return [...p].length >= 16 && classes(p) === 4 ? 3 : 2;
}
export function analyze(entries, now = Date.now()) {
  const l = entries.filter((e) => e.pass);
  const groups = new Map();
  for (const e of l) groups.set(e.pass, [...(groups.get(e.pass) || []), e]);
  return { total: l.length, weak: l.filter((e) => isWeak(e.pass)), reused: [...groups.values()].filter((g) => g.length > 1), old: l.filter((e) => now - e.ts > YEAR) };
}
