# Changelog

Derived from the Git history (`git log`). Dates are commit dates. Pre-release debug builds only; no signed releases exist.

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
