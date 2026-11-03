# K2Pass — Security Architecture

Audit date 2026-11-01, commit `99c014b`. Method: source reading + grep (no cryptographic review by a third party, **no tests, nothing run on a device for v0.3**). A mechanism is listed as implemented only if code uses it; "NEEDS TESTING" means untested at runtime.

## 1. Mechanisms
| Mechanism | Where | Verdict |
|---|---|---|
| Argon2id (argon2kt 1.5.0) 64 MiB, t=3, p=2, 16-byte salt, 32-byte output | `C/VaultCrypto.kt` `deriveKey`, `create` | Implemented. Parameters never benchmarked on the owner's phone. |
| AES-256-GCM, 12-byte random IV per encryption, 128-bit tag | `VaultCrypto.encrypt/decrypt` | Implemented, correct use (fresh IV every save). |
| Envelope encryption: random vault key wrapped by KEK; payload under vault key | `VaultCrypto.create/unlock/changeMasterPassword` | Implemented. Master change re-wraps without re-encrypting history of the key (new salt, same vault key). |
| AAD binding of header `v|m|t|p|salt` | `VaultCrypto.header` | Implemented: tampering with KDF params/salt fails authentication. |
| Android Keystore biometric key | `C/BiometricUnlock.kt` | Implemented (verified on Android 10). AES-256-GCM, auth required, invalidated on new biometric enrollment, `BiometricPrompt` CryptoObject. Android 10 path uses `setUserAuthenticationValidityDurationSeconds(-1)`. |
| PIN unlock | `A/PinStore.kt` | NEEDS TESTING. Argon2id (32 MiB, t=3, p=2) from PIN → AES-GCM wrap of vault key; outer wrap with a non-auth Keystore key. 5 failures delete it; cleared on vault import. |
| Auto-lock, FLAG_SECURE, clipboard clear | `MainActivity.kt`, `AppPrefs.kt`, `Util.kt` | Implemented; configurable in v0.3 (NEEDS TESTING). |
| Biometric/PIN only within 7 days of last master unlock | `Storage.kt` | Implemented. |

## 2. Key management and recovery
- Master password: never stored. Vault key: random, held in `Session` memory while unlocked, zeroed on `lock()`; copies handed to dialogs/autofill are zeroed after use.
- **No recovery**: forgetting the master password loses the vault (by design). The only backup is an exported vault file (which still needs the master). No emergency kit.
- Biometric/PIN blobs are device-bound and are deleted when a different vault file is imported (`VaultStore.importRaw`).
- Changing the master password keeps the vault key, so existing biometric/PIN blobs and old exported files (which contain the old wrapped key) stay valid. An attacker who got an old file + old master can still decrypt data encrypted under that same vault key. **Rotating the vault key is not implemented.**

## 3. Storage and leakage review
- Vault file `vault.json` (encrypted) and `.tmp` during atomic write — ciphertext only. Located in app-private `filesDir`.
- Logs: grep for `Log.`, `println`, `printStackTrace` → **none** in the codebase.
- Backups: `allowBackup=false` + `data_extraction_rules.xml` excluding cloud backup and device transfer.
- Network: `INTERNET` is declared since v0.6 and used only by `Updater.kt` after a manual tap (HTTPS GET to api.github.com / github.com release assets, generic User-Agent, no vault data, cleartext disabled). Download is SHA-256-checked against the release notes (integrity only); authenticity comes from Android's same-signer check against the stable signing key. **Risk:** anyone who can publish to the repo (or steal the signing key / GitHub account) can ship an update — protect the GitHub account with 2FA and the signing secrets. Consequence of having `INTERNET`: a future bug or dependency could exfiltrate data; keep network code isolated in `Updater.kt`.
- Microphone: `RECORD_AUDIO` (v0.7) is used only by `Voice.kt` for note recordings the user starts manually; audio is stored encrypted in the vault (`extra["voice"]`); plaintext temp files in cacheDir are deleted immediately.
- Exports: encrypted vault file (safe); plain CSV (contains every secret in clear text — gated by master-password re-entry and a warning; the user chooses the destination via the system file picker). Imported CSV/JSON files stay on the user's storage in clear text; the app cannot delete them.
- Clipboard: flagged `IS_SENSITIVE` (hides preview on Android 13+ only), cleared after 20 s default (configurable; clearing is a `Handler` timer — lost if the process is killed first).
- Screens: FLAG_SECURE on by default; user can disable it. `AuthActivity` always secure.
- Autofill service: never reads the vault; credentials only after authentication in `AuthActivity`. `PendingSave` keeps a just-typed password in process memory ≤ 2 minutes.

## 4. Threat model (summary)
| Threat | Covered? |
|---|---|
| Lost / stolen locked phone | Yes: encrypted file, Keystore-bound quick unlock, auto-lock |
| Offline attack on stolen vault file | Argon2id; strength = master password quality (min 10 chars, no strength check) |
| Malicious app on same device (non-root) | Private storage, FLAG_SECURE; clipboard readable by foreground apps in the window before clear |
| Phishing / wrong-site autofill | Domain-suffix matching, https only, hidden-field skip; no PSL |
| Rooted device / memory scraping | Not covered |
| Compromised sync channel | Files are encrypted + authenticated; no server exists yet |
| Malicious vault file imported for merge | Must decrypt with a known password; payload parsing via org.json (no schema validation beyond required keys) |
| Supply chain | Few deps; not pinned/audited; GitHub-built debug APK, not reproducible, not signed with a release key |

- Signing key: `signing/k2pass.jks` is committed to the (public) repo, encrypted with a random 28-char password held only in the GitHub secret `K2_KEYSTORE_PASSWORD`. Security of update authenticity therefore rests on that password and on the GitHub account. Safer alternative: move the keystore into the `K2_KEYSTORE_B64` secret and delete the file.

## 5. Security gaps (ordered)
1. **No tests** for crypto, merge, TOTP, PIN, importers. Add RFC 6238 vectors, Argon2/AES round-trips, tamper tests, merge property tests.
2. **PIN design**: minimum 6 digits and an attempt counter kept in SharedPreferences (resettable by anyone who can edit app data). Mitigated by the non-auth Keystore wrap and Argon2, but PIN-only entropy is low; consider requiring biometric as a second factor or lengthening. NEEDS TESTING.
3. **No master-password strength check** (length ≥ 10 only) and no strength meter in the editor.
4. **Vault-key rotation missing** (see §2).
5. **Wrong-password delay is client-side and in memory** (resets on app restart); Argon2 cost is the real limiter.
6. **Debug-build type, unminified, stable self-managed key (v0.6)**; debuggable flag state not reviewed. Need release signing before wider use.
7. **Passwords live in Strings** (Compose state, `Entry`) and cannot be zeroed; heap dumps on rooted devices expose them while unlocked.
8. **Autofill matching without Public Suffix List**; custom-tab/WebView domain reporting depends on the app.
9. **Plain CSV export/import** leave clear-text files; no secure delete.
10. **Clock dependence** of merge and 30-day trash purge.
11. **Format versioning**: `FORMAT_VERSION = 1` only gates the container; payload JSON gained fields in v0.3 without a version bump. Needs a documented schema version before sync with other clients.
12. **No external security review.**

## 6. Can the design support cross-platform sync?
Yes, with work: the container (Argon2id params + wrapped key + AES-GCM payload) is simple to reimplement on Windows/JS, and merge is deterministic. Missing for E2E server sync: a written format spec + test vectors, per-device identity/keys, a server protocol (opaque encrypted blobs + version vectors, not wall-clock), vault-key rotation/revocation on device removal, and a recovery story. See ROADMAP Phase 5.
