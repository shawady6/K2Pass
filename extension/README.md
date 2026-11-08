# K2Pass — browser extension (Chrome / Edge, v0.2)

Offline companion for the K2Pass Android app. **Read-only in this version**: it opens the encrypted vault file exported from the phone,
lets you search, copy usernames/passwords/TOTP codes and fill login forms. No network access, no servers.

## Install (developer mode)
1. Download `K2Pass-extension.zip` from the latest release and extract it to a folder you will keep.
2. Chrome/Edge → `chrome://extensions` (Edge: `edge://extensions`) → turn on **Developer mode** → **Load unpacked** → pick the folder.
3. Pin the K2Pass icon. Click it → choose the vault file exported from the app (Settings → export encrypted vault) → type the master password.

To update: replace the folder contents with the new zip and press the reload icon on the extension card.

## Sync with the phone (v0.2)
1. Phone: Tools → **مزامنة مع إضافة المتصفح** → **تشغيل المزامنة** (same Wi-Fi as the computer). A QR appears.
2. Extension menu ⋮ → **ربط الموبايل** → scan the QR with the computer's webcam (or paste the `k2sync://…` link shown under the QR).
3. From then on, every time you open the extension (and with the 🔄 button) it pulls the latest vault from the phone if the phone's sync screen is on. If the phone is off/unreachable the extension just keeps using its stored copy.
- What travels: only the vault file as stored on the phone — still encrypted by the master password. The pairing secret (in the QR) only decides who may download it; each request is authenticated with a single-use nonce + HMAC-SHA256, the server refuses everything else, and the extension refuses to talk to any non-private IP.
- The phone serves only while the sync card is open (screen kept awake). "إلغاء ربط كل الأجهزة" rotates the secret.
- Direction is phone → computer only. Edits made on the computer are not possible yet (read-only).
- Limits: plain HTTP on your LAN (content is encrypted anyway); guest/isolated Wi-Fi networks that block device-to-device traffic will not work — use the phone hotspot instead.

## How it works
- Vault format v1 (see `vault-core/.../VaultCrypto.kt`): Argon2id (hash-wasm, WebAssembly) → KEK → unwraps the vault key → AES-256-GCM (WebCrypto); header is the AAD.
- The file is stored **still encrypted** in `chrome.storage.local`. After unlocking, only the vault key is kept in `chrome.storage.session` (memory, never written to disk) and is wiped after 5 minutes or on lock.
- Fill (⚡) uses `activeTab` + `scripting`: it only touches the tab you clicked in, only when you press the button. No content script runs on pages by default.
- Matching: main URL + extra URLs (`extra.urls`), domain or subdomain. Android-app links are ignored on the web.
- Clipboard: copied secrets are overwritten after 20 s (best effort — only while the popup stays open; Chrome closes it on focus loss).

## Limits (v0.1)
- Read-only: no add/edit.
- Developer-mode extensions show a warning at browser start; Chrome Web Store publishing is a later step.
- Not tested on Firefox/Safari.

## Tests
`node --test extension/test/extension.test.mjs` (Node 18+): unlocks a fixture written by an independent Python reference implementation (`test/make_fixture.py`), wrong password, AAD tampering, RFC 6238 vectors, URL matching, sync protocol against a mock phone, and an HMAC vector shared with the Kotlin server test.
