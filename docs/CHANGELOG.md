# Changelog

Derived from the Git history (`git log`). Dates are commit dates. Pre-release debug builds only; no signed releases exist.

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
