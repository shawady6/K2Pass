// Generates test/merge_vectors.json from the JS merge. vault-core's VaultMergeTest asserts the SAME file, so a drift between
// the Kotlin and JS implementations fails CI. Run only when the merge rules are changed on purpose.
import { writeFileSync } from 'node:fs';
import { mergeEntries } from '../lib/merge.js';
const e = (id, pw, ts, o = {}) => ({ id, title: 't-' + id, user: 'u', pass: pw, url: '', notes: '', ts, del: false, cat: 'other', type: 'login', fav: false, ...o });
const cases = {
  disjoint: [[e('1', 'a', 10)], [e('2', 'b', 20)]],
  newerWins: [[e('1', 'old', 10)], [e('1', 'new', 20)]],
  tieSameTs: [[e('1', 'aaa', 10)], [e('1', 'bbb', 10)]],
  deleteWins: [[e('1', 'secret', 10)], [e('1', '', 20, { del: true })]],
  deleteTie: [[e('1', 'x', 10)], [e('1', '', 10, { del: true })]],
  extraAndTotp: [[e('1', 'a', 10, { totp: 'JBSWY3DP', extra: { b: '2', a: '1' } })], [e('1', 'a', 11, { totp: 'JBSWY3DP', extra: { a: '1', b: '3' }, fav: true, trash: 5 })]],
  historyMerge: [[e('1', 'cur', 100, { hist: [e('1', 'h1', 50), e('1', 'h2', 40)] })], [e('1', 'cur', 100, { hist: [e('1', 'h2', 40), e('1', 'h3', 60)] })]],
  historyCap: [[e('1', 'cur', 100, { hist: Array.from({ length: 30 }, (_, i) => e('1', 'p' + (i + 1), i + 1)) })], [e('1', 'cur', 100)]],
  folderMove: [[e('1', 'a', 10, { cat: 'f:abc' })], [e('1', 'a', 12, { cat: 'f:xyz' })]],
};
const out = Object.fromEntries(Object.entries(cases).map(([k, [a, b]]) => [k, { a, b, merged: mergeEntries(a, b) }]));
writeFileSync(new URL('./merge_vectors.json', import.meta.url), JSON.stringify(out, null, 1));
console.log('wrote', Object.keys(out).length, 'cases');
