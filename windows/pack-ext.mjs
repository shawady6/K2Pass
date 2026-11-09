// Bundles the browser extension (everything except tests/docs) into src-tauri/ext.bundle.json so the Windows app can unpack it.
import { readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
const root = new URL('../extension/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const files = {};
(function walk(d) {
  for (const n of readdirSync(d)) {
    const p = join(d, n), rel = relative(root, p).split('\\').join('/');
    if (statSync(p).isDirectory()) { if (n !== 'test') walk(p); continue; }
    if (rel.endsWith('.md')) continue;
    files[rel] = readFileSync(p).toString('base64');
  }
})(root);
const m = JSON.parse(readFileSync(join(root, 'manifest.json'), 'utf8'));
writeFileSync(new URL('./src-tauri/ext.bundle.json', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'), JSON.stringify({ version: m.version, key: m.key, files }));
console.log('packed', Object.keys(files).length, 'files, extension', m.version);
