# K2Pass — Project Context (source of truth)

Last verified against commit `99c014b` (2026-11-01). If this file and the code disagree, the code wins — then fix this file.
Status words (IMPLEMENTED / NEEDS TESTING / PLANNED …) are defined in [FEATURE_MATRIX.md](FEATURE_MATRIX.md). "Compiles in CI" is **not** "works on a device".

## 1. Owner and constraints
- Owner: Ibrahim (IT help desk, Egypt). Communicates in Egyptian Arabic; wants concise, directly usable output. App UI is Arabic, forced RTL.
- Personal project; strongly prefers **free** infrastructure (including future sync) and open-source components.
- Does not trust existing password managers → wants his own, complete "like Bitwarden" in the long run.
- Phone: Android 10 (hence minSdk 29). No local build machine is used: all builds go through GitHub Actions (repo `shawady6/K2Pass`, private).

## 2. Product vision
One vault for all credentials: websites, email, social, banking, Wi-Fi, PCs/laptops, Windows/Linux servers, Active Directory, routers/MikroTik, switches, firewalls (Sophos), other IT credentials, plus secure notes, cards, identities. Android + Windows, with end-to-end-encrypted sync where the server can never read plaintext. Modules: Personal Vault, IT Vault, Security Center, Smart Autofill, Import/Export, Cross-platform, Secure Sync.

## 3. Decisions made (do not silently reverse)
| Decision | Detail |
|---|---|
| Offline-first | No INTERNET permission in the Android app. Any future network feature must be opt-in, explicit and documented here first. |
| Crypto | Master password → Argon2id (64 MiB, t=3, p=2) → KEK; random 32-byte vault key wrapped by KEK (AES-256-GCM); payload encrypted with vault key. Header (`v|m|t|p|salt`) is AAD. Changing master re-wraps the key only. |
| Quick unlock | Biometric = Android Keystore AES key (auth required, invalidated on new enrollment) wrapping the vault key. PIN = Argon2id(32 MiB) + Keystore wrap. Both only within 7 days of last master-password unlock. |
| Sync (now) | Manual: export encrypted vault file → import & merge on the other device. Per-entry UUID; newest `updatedAt` wins; deterministic tie-break; loser kept in history (max 20); deletions are tombstones; merge is commutative and idempotent. |
| Sync (later) | Wi-Fi/QR local sync and/or optional self-hosted E2E server. Server must only ever hold ciphertext. Not started. |
| Autofill | Android AutofillService → always authenticate in `AuthActivity` → return datasets. Never fills on `http`. Matches domain/subdomain or `androidapp://package`. |
| Identity | Name **K2Pass**. Logo = silver shield, blue K, teal V, vault wheel on a navy square (`ic_launcher_foreground.xml`). **Do not change the logo unless the owner asks.** Palette was refreshed by the owner on 2026-11-01 (midnight violet + cyan). |
| Eye icon | Show/hide icon exists but the owner dislikes its shape; keep until he proposes a design. |
| Build | AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, Compose BOM 2024.12.01, argon2kt 1.5.0, androidx.biometric 1.1.0. |
| UI direction | Proposed: bottom navigation Home / Vault / Tools / Settings and a central "Add" action with all entry types. **Not implemented**; v0.3 is a single list screen + settings. |

## 4. Architecture (actual)
```
vault-core/  (Android library, package com.shawady.vault.core)
  VaultCrypto.kt    Argon2id, AES-GCM, VaultFile JSON (v,m,t,p,salt,wk,data), change master
  VaultModels.kt    Entry (id,title,user,pass,url,notes,totp,ts,del,cat,type,fav,trash,extra,hist), VaultData
  VaultMerge.kt     manual-sync merge
  Totp.kt           RFC 6238 (SHA1/256/512, digits, period, otpauth://)
  BiometricUnlock.kt Keystore + BiometricPrompt CryptoObject
app/         (package com.shawady.vault.app)
  MainActivity.kt   screens (Setup/Unlock/Vault/Edit/Prefs/Health), list, settings, sync, import/export, auto-lock
  AuthActivity.kt   autofill authentication, add-account page, save / update-password prompt
  VaultAutofillService.kt, StructureParser.kt, PendingSave.kt   autofill
  Storage.kt        VaultStore (vault.json, bio.json, atomic writes)   Session.kt  in-memory unlocked state
  PinStore.kt, AppPrefs.kt, Importer.kt, Health.kt, Editor.kt, Ui.kt, Theme.kt, Categories.kt, EyeIcons.kt, Util.kt
```
Storage: no database. One encrypted file `vault.json` (+ `bio.json`, `pin.json` blobs) in app-private `filesDir`; non-secret settings in SharedPreferences (`k2_settings`, `vault_prefs`).

## 5. Current state (summary)
- v0.1, v0.2 were installed and tested by the owner on Android 10 (v0.2: "very good").
- v0.3 builds in CI and is published as the `latest` pre-release; **v0.3 features have not been run on a device by anyone**. The owner changed the theme afterwards (also built successfully).
- No automated tests exist. No release signing; only debug APKs.

## 6. Known limitations
- Passwords pass through immutable JVM Strings / Compose state; they cannot be zeroed.
- Domain matching has no Public Suffix List (`baseDomain` heuristic).
- Merge relies on device wall-clocks (`updatedAt`); a badly skewed clock can make an older edit win.
- Downgrading the app after v0.3 edits would drop the new fields on next save (additive schema, no version gate).
- Passkeys and autofill inline suggestions are untestable on Android 10.
- No account/master-password recovery by design.
- Clipboard "sensitive" flag only hides content on Android 13+.

## 7. Corrections to earlier claims
See [FEATURE_MATRIX.md §Corrections](FEATURE_MATRIX.md#corrections-to-earlier-assumptions).

## 8. Working agreement (applies to every contributor, human or AI)
Security over speed; never log or persist decrypted data; never weaken crypto for sync; no fake buttons; no silent schema/format changes (add a migration note here + in CHANGELOG); test before claiming "works"; update this file and FEATURE_MATRIX in the same commit as the change.
