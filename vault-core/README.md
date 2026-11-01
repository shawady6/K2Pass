# vault-core

Crypto, data model, merge and TOTP for K2Pass (Kotlin / Android library). **No automated tests yet and no external review — do not rely on it for real secrets until Phase 1–2 of `docs/ROADMAP.md` are done.**

| File | Purpose |
|---|---|
| `VaultCrypto.kt` | Master password → Argon2id → KEK; wraps a random vault key; AES-256-GCM payload; header is AAD. |
| `VaultModels.kt` | `Entry` / `VaultData` + JSON (types login/note/card/identity, favorites, trash, history). |
| `VaultMerge.kt` | Manual-sync merge: newest wins, loser kept in history, tombstones, deterministic. |
| `Totp.kt` | RFC 6238 TOTP (SHA1/256/512, otpauth://). |
| `BiometricUnlock.kt` | Keystore key bound to strong biometrics (API 29+). |

Design, status and gaps: `../docs/SECURITY_ARCHITECTURE.md`, `../docs/FEATURE_MATRIX.md`.
