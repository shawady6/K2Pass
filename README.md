<div align="center">

# 🔐 K2Pass

**A personal, offline-first password manager — Android app + browser extension.**

[![Download APK](https://img.shields.io/badge/⬇_Download-Android_APK-6c5ce7?style=for-the-badge)](https://github.com/shawady6/K2Pass/releases/download/latest/K2Pass-debug.apk)
[![Download extension](https://img.shields.io/badge/⬇_Download-Browser_Extension-3b82f6?style=for-the-badge)](https://github.com/shawady6/K2Pass/releases/download/latest/K2Pass-extension.zip)

[All releases & notes](https://github.com/shawady6/K2Pass/releases/tag/latest)

</div>

## ⬇️ Download

| What | Link | Notes |
|---|---|---|
| 📱 **Android app** | [**K2Pass-debug.apk**](https://github.com/shawady6/K2Pass/releases/download/latest/K2Pass-debug.apk) | Android 10+. Allow "install unknown apps" once. Later updates: **Settings → Updates → تحقق وحدّث** (one tap, inside the app). |
| 🖥️ **Windows app** *(new, early)* | [**K2Pass-Windows.exe**](https://github.com/shawady6/K2Pass/releases/download/windows/K2Pass-Windows.exe) | Windows 10/11 (needs WebView2, already on most PCs). Pair with the phone by QR; the phone pushes the vault over Wi-Fi. |
| 🧩 **Browser extension** | [**K2Pass-extension.zip**](https://github.com/shawady6/K2Pass/releases/download/latest/K2Pass-extension.zip) | Chrome / Edge / Brave (Manifest V3). Read-only companion. |

Both files are always the **latest build** (pre-release tag [`latest`](https://github.com/shawady6/K2Pass/releases/tag/latest)).

### Install the extension (Chrome / Edge / Brave)
1. Download and **unzip** `K2Pass-extension.zip` into a permanent folder.
2. Open `chrome://extensions` (or `edge://extensions` / `brave://extensions`) and turn on **Developer mode**.
3. **Load unpacked** → pick the unzipped folder. Check the version under the name.
4. **To update:** unzip over the same folder and press ⟳ on the extension.

### Pair the extension with your phone (LAN sync)
1. Click the extension icon → **ربط الموبايل**: a QR code appears.
2. On the phone (same Wi-Fi): **Tools → مزامنة مع إضافة المتصفح → مسح كود الإضافة** and scan it.
3. The extension finds the phone, pulls the encrypted vault and asks for your master password. The QR contains only a pairing secret, never your passwords.

> Status: **personal use, debug builds, not security-reviewed.** Use dummy data until you have tested it yourself.
> Decisions and status live in [`docs/`](docs/) — start with [`docs/PROJECT_CONTEXT.md`](docs/PROJECT_CONTEXT.md) and [`docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md).

## ✨ What it does

**Vault**
- 12 item types (login, device, server, router, switch, firewall, Active Directory, Wi-Fi, SSH key, secure note, card, identity), custom **folders**, favorites, trash (30 days), per-item history, multiple URLs per login, search across everything, sorting.
- Read-only item view with one-tap copy for user / password, live **TOTP** (with QR scan), **voice notes**, strength meter, clone, bulk move to folders.
- **Generator** tab: password, passphrase and username modes.
- Vault health report (weak / reused / old).

**Security**
- Master password → Argon2id → AES-256-GCM envelope encryption. Biometric and PIN unlock, auto-lock, clipboard auto-clear, screenshot blocking, attempt back-off.
- Android **Autofill** service (domain / app matching, save & update prompts).
- The vault never leaves the device except as an encrypted file; the app uses `INTERNET` only for update checks, and the LAN sync server runs only while its screen is open.

**Import / export / sync**
- Import Bitwarden JSON and CSV (Chrome, Bitwarden, KeePassXC, LastPass). Export an encrypted vault file or plain CSV (after master-password confirmation).
- Manual merge sync between devices, plus phone → browser **LAN sync**.

## 🗺️ Platforms
| Platform | State |
|---|---|
| Android 10+ (minSdk 29, target 35) | ✅ Available |
| Chrome / Edge / Brave extension | ✅ Available (read-only; suggests accounts inside login pages, one-click fill) |
| Windows app | 🚧 Phase A (view, search, copy, TOTP, receive from phone) |

## First use
1. Create the vault with a master password (10+ characters). **There is no recovery.**
2. Settings → enable biometric unlock and/or PIN.
3. Settings → enable K2Pass as the Autofill service.
4. For each login: URL `https://site.com` for websites, or `androidapp://com.package` for apps.

## Manual sync between two devices
Device A **export** → Device B **import & merge** → B **export** → A **import & merge**. Newest edit wins; the losing version is kept in history; deletions propagate (tombstones).

## Roadmap
See [`docs/ROADMAP.md`](docs/ROADMAP.md). Changes: [`docs/CHANGELOG.md`](docs/CHANGELOG.md). Security design and known gaps: [`docs/SECURITY_ARCHITECTURE.md`](docs/SECURITY_ARCHITECTURE.md).

## Build
No local toolchain is required. Push to GitHub; the workflow `.github/workflows/build-apk.yml` runs the extension tests and `gradle :vault-core:testDebugUnitTest :app:assembleDebug`, uploads the artifact `K2Pass-debug-apk`, and on `main` publishes `K2Pass-debug.apk` and `K2Pass-extension.zip` to the pre-release tag **`latest`**.

Local build (needs JDK 17, Android SDK 35, Gradle 8.11.1): `gradle :app:assembleDebug`.

Modules: `app/` (UI, autofill, storage, import/export), `vault-core/` (crypto, models, merge, TOTP, LAN sync server) and `extension/` (browser extension, plain JS).

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
