# K2Pass

A personal, **offline-first** password manager and digital vault. Android app today (Kotlin + Jetpack Compose); Windows app and browser extension are planned.
The vault never touches the network; the app uses `INTERNET` only for the manual **Settings → Updates** button. Sync between devices is manual (encrypted file export/import with a deterministic merge).

> Status: **v0.5 debug build, personal use, not security-reviewed, no automated tests.** Use dummy data until you have tested it yourself.
> The single source of truth for decisions and status is in [`docs/`](docs/) — start with [`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md) and [`docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md).

## What it does today (v0.5)
- Navigation: Home / Vault / Tools / Settings + central Add button.
- 11 item types: login, device, server, router/MikroTik, switch, firewall, Active Directory, Wi-Fi, secure note, card, identity. Favorites, 9 fixed categories, search, type filters, trash (30 days), per-item history (20 versions).
- Master password (Argon2id + AES-256-GCM envelope encryption), biometric unlock (Android Keystore), PIN unlock, auto-lock, clipboard auto-clear, screenshot blocking.
- Android Autofill service: login/sign-up form detection, matching by domain / app package, "save to K2Pass?" and "update password?" prompts, per-site block list, TOTP code copied after fill.
- TOTP codes (RFC 6238) with countdown. Password generator. Vault health report (weak / reused / old).
- Import: Bitwarden JSON (unencrypted), CSV from Chrome / Bitwarden / KeePassXC / LastPass. Export: encrypted vault file; plain CSV (after master-password confirmation).
- Manual sync: import & merge another device's encrypted vault file.

## Not built yet (see roadmap)
Windows app, browser extension, custom folders, real links between credentials, compromised-password check, encrypted cloud / self-hosted sync, passkeys, attachments, automated tests, release signing.

## Platforms
| Platform | State |
|---|---|
| Android 10+ (minSdk 29, target 35) | Implemented (v0.5, needs device testing) |
| Windows | Planned |
| Browser extension | Planned |

## Build
No local toolchain is required. Push to GitHub; the workflow `.github/workflows/build-apk.yml` runs `gradle :app:assembleDebug`, uploads the artifact `K2Pass-debug-apk`, and on `main` publishes `K2Pass-debug.apk` to the pre-release tag **`latest`**.

Local build (needs JDK 17, Android SDK 35, Gradle 8.11.1): `gradle :app:assembleDebug`.

Modules: `app/` (UI, autofill, storage, import/export) and `vault-core/` (crypto, models, merge, TOTP, biometric wrapper).

## First use
1. Create the vault with a master password (10+ characters). **There is no recovery.**
2. Settings → enable biometric unlock and/or PIN.
3. Settings → enable K2Pass as the Autofill service.
4. For each login: URL `https://site.com` for websites, or `androidapp://com.package` for apps.

## Manual sync between two devices
Device A **export** → Device B **import & merge** → B **export** → A **import & merge**. Newest edit wins; the losing version is kept in history; deletions propagate (tombstones).

## Roadmap
See [`docs/ROADMAP.md`](docs/ROADMAP.md). Changes: [`docs/CHANGELOG.md`](docs/CHANGELOG.md). Security design and known gaps: [`docs/SECURITY_ARCHITECTURE.md`](docs/SECURITY_ARCHITECTURE.md).

## Signing & in-app updates (maintainers)
Updates installed from inside the app require every build to be signed with the **same key**. The PKCS12 keystore is committed as `signing/k2pass.jks` (alias `k2pass`); it is protected by a random 28-character password that lives only in the GitHub Actions secret `K2_KEYSTORE_PASSWORD`. Optionally the keystore can instead be supplied as the secret `K2_KEYSTORE_B64` (takes precedence). Without the password secret CI builds but skips publishing the `latest` release. Back up the keystore **and** the password: if either is lost, users must reinstall once.
