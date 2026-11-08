# Changelog

Derived from the Git history (`git log`). Dates are commit dates. Pre-release debug builds only; no signed releases exist.

## 2026-11-08 — v1.4.1
- Updater retries GitHub 5xx / network errors up to 4 times with back-off (owner hit a transient 504 while downloading 1.4); read timeout 60 s. Integrity failures are never retried.

## 2026-11-08 — v1.4 (phase 2: usability)
- **QR scan for TOTP** ("📷 مسح QR" in the login editor; zxing-android-embedded, fully offline; new `CAMERA` permission, camera not required). Invalid QR → message.
- **Sorting** (name / last modified) in item lists and "بدون فولدر".
- **Clone item** button on the view screen (copies fields; clears favorite, TOTP, voice, history).
- **Password strength bar** under the password field in the editor.
- Empty vault shows an import-from-file button; section rows redesigned (tinted icon tile + count + chevron).
- Not done yet from phase 2: swipe actions, idle auto-lock, splitting `MainActivity.kt`, TalkBack audit (current null content descriptions are decorative icons with a text label next to them).
- Status: CI-build verified only — NEEDS TESTING on device.

## 2026-11-04 — v1.3 (phase-1 safety work)
- **Tests** (`vault-core/src/test`, run in CI before the APK build): RFC 6238 TOTP vectors (SHA-1/256/512), merge commutativity/idempotence/history cap/tombstones/tie-break, model JSON round trip + old-format load. Crypto round-trip tests are NOT possible on the JVM (Argon2 JNI) → need instrumented tests (still TODO).
- Editor: warns before leaving with unsaved changes (back button and "إلغاء").
- Vault: the open folder/section is kept when you open an item and come back.
- Backup reminder card on the vault root (never exported, or ≥14 days; "بعد أسبوع" snoozes).
- Master-password attempts: persistent back-off (3 free tries, then 10 s doubling to 15 min), survives app restarts.
- Search now covers notes, all URLs, folder names and non-secret fields (IP, host, SSID…).
- One generator engine (`Gen`) for the editor and the Generator tab.
- Not done on purpose: moving the signing key out of the public repo (see docs/SECURITY_ARCHITECTURE.md note) — it is already in git history, so removal alone gains nothing without rotating the key (which forces a reinstall).
- Status: CI-build verified only — NEEDS TESTING on device.

## 2026-11-04 — v1.2 (one account, many sites/apps)
- A login can match several URLs/apps: extra field `urls` (one per line, e.g. `androidapp://com.facebook.katana`). `Matcher` checks the main URL plus these. No vault-format change.
- Save prompt: if the credentials just entered (same username + password) already exist under another site/app, K2Pass offers "ربط بالحساب الموجود؟" instead of creating a duplicate.
- Status: CI-build verified only — NEEDS TESTING on device.

## 2026-11-04 — v1.1.1 (one-tap update)
- "تحقق وحدّث": one button checks, downloads, SHA-256-verifies and opens the Android installer (the final confirmation screen is enforced by Android and cannot be skipped). A separate "تثبيت" button appears only if the install-permission screen interrupted the flow.

## 2026-11-04 — v1.1 (read-only view, bulk folder ops, two copy buttons)
- Tapping an item opens a **read-only view** (copy + show/hide secrets, live TOTP, voice playback). Editing only through the "تعديل" button; back from edit returns to the view.
- Inside a folder: **"+ إضافة عناصر"** (multi-select picker, no-folder items first, search, select all) and **"إزالة عناصر"** (multi-select) — no more one-by-one.
- Item cards: separate **copy username** and **copy password** buttons (plus OTP).
- Status: CI-build verified only — NEEDS TESTING on device.

## 2026-11-04 — v1.0 (Bitwarden-style features, K2Pass look)
- New **Generator tab** (replaces the Home tab): Password (length slider, A-Z/a-z/0-9/symbols, avoid ambiguous), Passphrase (EFF Long wordlist bundled offline in `res/raw/wordlist.txt`, 3–10 words, separator, capitalize, number), Username (plus-addressed email, catch-all, random words). Digits blue / symbols pink, regenerate + copy, "create item with result". Options persist in a local prefs file (no secrets). Wordlist: EFF Long (CC BY 3.0).
- **Vault screen** now sectioned: health tiles, الأنواع (all / favorites / login / IT / notes / cards / identities / SSH / trash with counts), الفولدرات with counts, بدون فولدر list. Tapping a row opens that list (back button / system back returns). Search works across everything.
- New item type **SSH key** (host, private key, public key, fingerprint, passphrase).
- Home tab removed; favorites/recents are reachable via the sections.
- Status: CI-build verified only — NEEDS TESTING on device.

## 2026-11-04 — v0.9 (main folders replace filters)
- Removed the type-chip row and the category-chip row. One "📂 الفولدرات" button in the vault opens a dialog: الكل / المفضلة / المهملات + user folders (create, rename, delete, counts).
- Folder = vault entry `type="folder"` (title = name), so it is encrypted, synced, and persists while empty. Items use `category = "f:<folderId>"`. Deleting a folder keeps its items (moved to no folder). Folder entries are hidden from lists, stats, health and CSV export.
- Editor: "الفولدر" picker button (old fixed categories are no longer shown; old values are kept in data but ignored).
- v0.8 chip-based folders (`f:<name>`) are superseded; they will not show up as folders (items appear under "الكل").
- Status: CI-build verified only — NEEDS TESTING on device.

## 2026-11-03 — v0.8 (folders + settings tidy)
- Custom folders: "+ فولدر جديد" chip in the editor; folders appear as chips in the vault filter. Stored as `category = "f:<name>"` (no vault-format change). Limitation: a folder with no items disappears; no rename yet.
- Settings: master-password change is collapsed behind a button.
- Status: CI-build verified only — NEEDS TESTING on device.

## 2026-11-03 — v0.7 (voice notes)
- Secret notes can hold one voice recording (max 2 min, AAC mono 16 kHz/32 kbps, ~0.5 MB/min).
- Stored as Base64 in `Entry.extra["voice"]` — inside the encrypted vault, no vault-format change. History copies drop the audio to avoid bloat.
- New permission `RECORD_AUDIO` (asked on first recording). Temp plaintext files in cacheDir are deleted right after use.
- Status: CI-build verified only — NEEDS TESTING on device.

## 2026-11-03 — v0.6 (in-app update)
- Settings → Updates: "check for update" / download / install, from the public GitHub release `latest` (`Updater.kt`). Only runs when the user taps the button; verifies SHA-256 from the release notes; Android enforces same-signature on install.
- **Decision change (owner-approved 2026-11-03):** the app now declares `INTERNET` (updater only) and `REQUEST_INSTALL_PACKAGES`; cleartext traffic disabled. FileProvider added for the downloaded APK (cache dir).
- CI: stable signing key from GitHub secrets `K2_KEYSTORE_B64` + `K2_KEYSTORE_PASSWORD` (alias `k2pass`). Without them CI still builds but does **not** publish the release. Release notes now carry `versionCode`, `versionName`, `sha256`.
- **One-time migration:** builds before v0.6 were signed with a random per-build debug key, so they cannot be updated in place; the first v0.6 install requires uninstalling the old app (export the encrypted vault first and import it back).
- CI signing: keystore committed encrypted at `signing/k2pass.jks`, password from secret `K2_KEYSTORE_PASSWORD` (the base64 secret is optional).
- Repo `shawady6/K2Pass` is now public (observed 2026-11-03), so the updater needs no token.

## 2026-11-01 — v0.5.1
- Fix: text was black on the dark background (default content colour was lost when the root Surface was replaced by a gradient Box).

## 2026-11-01 — v0.5 (full visual redesign, requested by the owner)
- **New logo (option B):** padlock with a K keyhole, violet→cyan gradient on a dark tile; new adaptive launcher icon and in-app mark. Supersedes the "keep the logo unchanged" rule.
- "Glass dark" design system (dark only): midnight gradient background, translucent bordered `GlassCard`s, brand gradient, rounded shapes, floating bottom bar with gradient Add button, hero card on Home.
- One vector icon set (`K2Icons.kt`) replaces all emoji; per-type icon tiles; IBM Plex Sans Arabic (SIL OFL) bundled as the app font.
- Status/navigation bar icons set for a dark UI. No change to crypto, storage or vault format.
- Not device-tested.

## 2026-11-01 — v0.4.1 (visual polish)
- Fixed square-looking text fields (Material `extraSmall` shape was the 4 dp default): all shapes now rounded (14/14/18/24/28 dp).
- One soft text-field style everywhere (`k2FieldColors`), bordered rounded cards, rounded bottom bar with a pill highlight on the active tab, launcher background colour aligned with the in-app logo box (#10152B). Logo drawable unchanged.

## 2026-11-01 — v0.4
- Navigation: bottom bar Home / Vault / Tools / Settings with a central "+" Add button (type picker dialog). Home shows security summary, favorites, recent items. Tools holds the standalone password generator, TOTP codes, health report, import/export and manual sync (moved out of Settings).
- IT Vault item types: device, server, router/MikroTik, switch, firewall, Active Directory, Wi-Fi (`EntryTypes.kt`). Type-specific data stored in `Entry.extra` (host, mgmt, vendor, model, location, related, domain, ssid, security). **No vault format or schema change** (new `type` strings + `extra` keys only).
- Health report now covers every item that has a password (IT devices, Wi-Fi included). Vault filter "IT".
- Logo, crypto and storage untouched.

## 2026-11-01 — Theme refresh (commit 99c014b, by the project owner)
- `Theme.kt`: new midnight-violet / cyan colour palette. Logo drawable unchanged.

## 2026-11-01 — v0.3 (commit 0cf3377)
- Item types: login, secure note, card, identity (card/identity data in `Entry.extra`).
- Favorites, trash with 30-day purge and restore, per-item history viewer with restore.
- TOTP (`Totp.kt`), live code with countdown, copy after autofill.
- Autofill: "save to K2Pass?" via SaveInfo/`onSaveRequest`, update-password prompt, per-domain/app block list.
- PIN unlock (`PinStore.kt`: Argon2id + Android Keystore wrap, 5 attempts).
- Settings: auto-lock timeout, clipboard-clear time, screenshot blocking, TOTP copy toggle.
- Vault health report (weak / reused / old), import (Bitwarden JSON, 4 CSV flavours), plain CSV export behind master-password confirmation.
- Show/hide eye icon on secret fields. New launcher icon / logo.
- Vault JSON schema extended additively (new optional keys); `FORMAT_VERSION` still 1. `VaultCrypto.deriveKey` made public.

## 2026-10-31 — v0.2 (commit 14457c2)
- K2Pass identity (name, theme, icon), 9 categories, smarter autofill field detection, "add account" page when no match, confirm-password field fill.

## 2026-10-31 — v0.1.1 (commit c0ce75a)
- minSdk lowered 30 → 29 (Android 10); biometric Keystore key parameters handled for API 29.

## 2026-10-31 — CI (commit 0eeba62)
- Workflow publishes the debug APK as the `latest` pre-release.

## 2026-10-31 — v0.1 (commit 1e5fa4b)
- Initial Android app + `vault-core`: master password, Argon2id/AES-GCM envelope, biometric unlock, autofill service, manual merge-sync, GitHub Actions APK build.
