#!/bin/sh
# Copies the shared extension code (vault crypto, TOTP, matching, QR, fonts, styles) into ui/. Run before `tauri build`/dev.
set -e
cd "$(dirname "$0")"
rm -rf ui/lib ui/fonts
mkdir -p ui/lib ui/fonts ui/icons
for f in vault.js totp.js match.js merge.js types.js health.js icons.js argon2.umd.min.js qrcode.js; do cp ../extension/lib/$f ui/lib/; done
cp ../extension/fonts/*.ttf ui/fonts/
cp ../extension/icons/48.png ../extension/icons/128.png ui/icons/
cp ../extension/popup.css ui/base.css
node pack-ext.mjs
