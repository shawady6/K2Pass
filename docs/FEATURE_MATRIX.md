# K2Pass — Feature Matrix

Verified by reading source at commit `99c014b` and CI results. **There are no automated tests and no device test of v0.3.**
Rule applied: code present and compiling, but never exercised on a device = **NEEDS TESTING**, not IMPLEMENTED. Only things the owner actually used on his phone (v0.1/v0.2) count as IMPLEMENTED.

Status: IMPLEMENTED · PARTIAL · NEEDS TESTING · PLANNED · BLOCKED. (IN PROGRESS: none right now.)
Paths: `A/` = `app/src/main/java/com/shawady/vault/app/`, `C/` = `vault-core/src/main/kotlin/com/shawady/vault/core/`.

## A. Personal Vault
| Feature | Status | Files | Evidence / missing | Next |
|---|---|---|---|---|
| Logins | IMPLEMENTED | A/Editor.kt, MainActivity.kt | Used on device in v0.1/v0.2 | — |
| Secure notes | NEEDS TESTING | Editor.kt | `type="note"`, 8-line notes field | Device test |
| Payment cards | NEEDS TESTING | Editor.kt | holder/number/exp/cvv in `extra`; number & cvv masked | Device test; no card-brand/expiry validation |
| Identities | NEEDS TESTING | Editor.kt | name/email/phone/address/idnum | Device test |
| Categories | IMPLEMENTED | Categories.kt | 9 fixed categories; tested v0.2 | Custom folders (PLANNED) |
| Folders (user-defined) | PLANNED | — | Not present | Design with sync-safe IDs |
| Favorites | NEEDS TESTING | MainActivity.kt, Editor.kt | star toggle + filter | Device test |
| Search / filter | PARTIAL | MainActivity.kt | text search (title/user/url), type + category filters; no search in notes/extra | Extend search |
| Edit / delete | IMPLEMENTED (edit) / NEEDS TESTING (delete→trash) | Editor.kt | delete now moves to trash | Device test |
| Trash & recovery | NEEDS TESTING | MainActivity.kt (`trashEntry`, `purgeTrash`, `TrashDialog`) | 30-day purge uses device clock | Device test, incl. sync of trashed items |
| Password history | NEEDS TESTING | Editor.kt (`HistoryDialog`), C/VaultMerge.kt | max 20; restore; merge keeps loser | Unit-test merge |

## B. IT Vault
| Feature | Status | Notes |
|---|---|---|
| Device / Server / Router (MikroTik) / Switch / Firewall / Active Directory types | NEEDS TESTING (v0.4, builds in CI only) | `A/EntryTypes.kt`, `A/Editor.kt`. Fields: host/IP, management URL, vendor, model, location, related, domain (AD), username, password, notes. Stored in `Entry.extra` — no format change. Missing: IT-specific password rotation dates, SSH key / port fields, autofill of web admin pages (those are usually `http`, which autofill refuses). |
| Wi-Fi passwords | NEEDS TESTING (v0.4) | Type `wifi` (SSID, security, password). Missing: QR code share. |
| Related credentials / linking | PARTIAL | free-text "related" field only; no real links between entries |

## C. Security Center
| Feature | Status | Files | Evidence / missing |
|---|---|---|---|
| Password generator | IMPLEMENTED (editor) / NEEDS TESTING (standalone in Tools, v0.4) | A/Util.kt, Editor.kt | length 8–48, symbols toggle, SecureRandom; no passphrase mode |
| Strength assessment | PARTIAL | A/Health.kt | heuristic only (length, classes, short common list); no live meter in editor; not zxcvbn |
| Health report (weak/reused/old) | NEEDS TESTING | Health.kt | local-only |
| Compromised-password check | PLANNED | — | Requires network (HIBP k-anonymity) → conflicts with "no INTERNET"; decision needed (optional, explicit, separate build flavour?) |
| TOTP | NEEDS TESTING | C/Totp.kt, Editor.kt | No unit tests / RFC vectors run. Time source = device clock |
| Security settings | NEEDS TESTING | AppPrefs.kt, MainActivity.kt | auto-lock, clipboard, FLAG_SECURE, blocklist |
| Security audit log | PLANNED | — | none |

## D. Autofill
| Feature | Status | Files | Evidence / missing |
|---|---|---|---|
| Field detection | IMPLEMENTED (v0.2 heuristics) | StructureParser.kt | tested on device in v0.2; the owner's later branch `fix/autofill-phone-field` (phone-field suggestions) is **not in main** — verify |
| Matching suggestions / multiple accounts | IMPLEMENTED | Util.kt `Matcher`, AuthActivity.kt | multiple datasets returned |
| Add-account page when no match | IMPLEMENTED | AuthActivity.kt | tested v0.2 |
| Save prompt / update password | NEEDS TESTING | VaultAutofillService.kt, AuthActivity.kt, PendingSave.kt | depends on apps/browsers sending save requests; Chrome behaviour varies |
| TOTP copy after fill | NEEDS TESTING | AuthActivity.kt | only when exactly one match |
| Block list | NEEDS TESTING | AppPrefs.kt | |
| Inline suggestions / passkeys | PLANNED / BLOCKED on Android 10 test device | — | needs Android 11+/14+ |
| Windows autofill | PLANNED | — | Options: browser extension + native messaging; Windows Hello |

## E. Import / Export
| Feature | Status | Files | Evidence / missing |
|---|---|---|---|
| Encrypted export (vault file) | IMPLEMENTED | MainActivity.kt, Storage.kt | v0.1 |
| Manual merge import | IMPLEMENTED | VaultMerge.kt, MainActivity.kt | no automated tests of commutativity/idempotence |
| Bitwarden JSON import | NEEDS TESTING | Importer.kt | unencrypted JSON only; encrypted exports rejected with a message |
| CSV import (Chrome/Bitwarden/KeePassXC/LastPass) | NEEDS TESTING | Importer.kt | header-based; dedupe by url+user+password |
| Plain CSV export | NEEDS TESTING | Importer.kt, MainActivity.kt | master confirmation + warning text |
| Encrypted backup/restore | PARTIAL | — | the exported vault file *is* the backup; no scheduled/automatic backup, no restore wizard |
| Bitwarden-format export / KeePass export | PLANNED | — | |

## F. Platforms
| Feature | Status | Notes |
|---|---|---|
| Android app | NEEDS TESTING (v0.3) | v0.2 verified by owner |
| Biometric unlock | IMPLEMENTED | C/BiometricUnlock.kt; verified on Android 10 |
| PIN unlock | NEEDS TESTING | PinStore.kt |
| Android Keystore | IMPLEMENTED (biometric) / NEEDS TESTING (PIN wrap) | |
| Windows app, Windows Hello | PLANNED | Needs a decision on stack (e.g., Kotlin Multiplatform/JVM, Rust, .NET) sharing the vault file format |
| Browser extension | PLANNED | |
| Navigation Home/Vault/Tools/Settings + central Add | NEEDS TESTING (v0.4) | `MainActivity.kt` `Shell`, `BottomBar`, `HomeScreen`, `ToolsScreen`, `AddTypeDialog`; text/emoji icons, no icon library; not looked at on a device |
| Dark mode | IMPLEMENTED | Theme.kt follows system |
| Accessibility audit | PLANNED | content descriptions only on eye icon; no TalkBack / font-scale testing |

## G. Sync
| Feature | Status | Notes |
|---|---|---|
| Manual file sync | IMPLEMENTED | see E |
| Conflict resolution | IMPLEMENTED (logic) / NEEDS TESTING | wall-clock based |
| Wi-Fi / QR sync | PLANNED | |
| Cloud / self-hosted E2E sync, device management, recovery | PLANNED | design required before code (see ROADMAP Phase 5) |

## Quality / delivery
| Item | Status |
|---|---|
| Unit / instrumented tests | PLANNED — none exist |
| CI build | IMPLEMENTED — `.github/workflows/build-apk.yml`, passing on main |
| Lint / dependency audit | PLANNED |
| Release signing, minification | PLANNED — `isMinifyEnabled=false`, debug-signed |
| Documentation | IMPLEMENTED — this `docs/` set (2026-11-01) |

## Corrections to earlier assumptions
1. "TOTP, trash, PIN, import, save-prompt are done" (v0.3 announcement) → they **compile**; none has been run on a device. Re-classified NEEDS TESTING.
2. Old `README.md` said TOTP and "save from autofill" were missing and referenced the artifact `ShawadyVault-debug-apk`; both stale (fixed now).
3. `vault-core/README.md` still described the UI/autofill/lock as missing — stale; and `BiometricUnlock.kt` header comment says "Requires minSdk 30" although the code supports API 29 (comment is wrong).
4. The roadmap said Windows/extension would "reuse the same vault file"; no cross-platform code, format spec or test vectors exist yet — it is an intent, not a property.
5. "Wi-Fi sync with QR" was discussed as a plan; nothing exists.
6. The owner's UI wish list (IT types, bottom navigation, Tools tab, compromised-password check) was never implemented.
7. GitHub shows CI runs for branches `phase-1-full-app` and `fix/autofill-phone-field` (commit "Only offer autofill for detected password forms"); those branches no longer exist and that change is **not** in `main` as far as this audit can tell.
