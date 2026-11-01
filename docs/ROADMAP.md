# K2Pass — Roadmap

Priorities: security and correctness first, features second. Each phase ends with acceptance criteria; update `FEATURE_MATRIX.md` and `CHANGELOG.md` in the same commit.

## Phase 0 — Verify what exists (NEXT)
- Install the `latest` APK, test v0.3 end to end on the Android 10 phone with dummy data.
- Fix defects found; re-classify features in FEATURE_MATRIX from NEEDS TESTING.
- Decide the fate of the earlier autofill branches ("Only offer autofill for detected password forms"): confirm main behaves correctly in phone-number fields.
- **Acceptance:** checklist below passes; no crash in create / unlock / bio / PIN / autofill fill / save prompt / import / export / merge / trash / TOTP.

## Phase 1 — Safety net
- Unit tests in `vault-core` (JVM): TOTP RFC 6238 vectors, crypto round-trip + tamper, merge commutative/idempotent, model JSON compatibility (v0.2 file loads).
- Importer tests with sample Chrome/Bitwarden/KeePassXC/LastPass files. CI runs tests before building.
- Write a vault format spec + test vectors in `docs/`; add a payload schema version with migration.
- **Acceptance:** `gradle test` green in CI; spec reviewed.

## Phase 2 — Security hardening
- Master-password strength check; vault-key rotation; PIN policy review; release signing + minification; lint + dependency audit; Argon2 benchmark on the phone.
- **Acceptance:** items in SECURITY_ARCHITECTURE §5 (1,3,4,6,12-prep) closed or consciously accepted in writing.

## Phase 3 — Product model & UI
- IT Vault types (device, server, router, MikroTik, switch, firewall, AD, Wi-Fi) with typed fields and related-credential links; custom folders.
- Navigation: Home / Vault / Tools / Settings with central Add; Tools = generator, health, TOTP, import/export.
- Accessibility pass (TalkBack, font scale, contrast). Keep the logo unchanged.
- **Acceptance:** every type creatable, editable, searchable, mergeable; no placeholder buttons.

## Phase 4 — Windows
- Choose stack (decision record in docs). Desktop app reading/writing the same vault format; Windows Hello unlock; manual sync via file first.
- Browser extension + native messaging for autofill.
- **Acceptance:** same vault round-trips Android ↔ Windows with conflicting edits merged correctly.

## Phase 5 — Secure sync (design first)
- Options: (a) Wi-Fi/QR direct device sync, (b) self-hosted server storing only ciphertext (free: own PC/VPS/Raspberry), (c) user-owned storage (WebDAV/Syncthing folder).
- Requirements: end-to-end encryption, device enrolment/revocation, version vectors instead of wall-clock, conflict UI, backup/recovery. Android's "no INTERNET" decision must be revised explicitly (separate permission/flavour).
- **Acceptance:** threat-model update + protocol doc approved before implementation.

## Later
Compromised-password check (opt-in k-anonymity), passkeys (Android 14+), attachments, SSH keys, CLI, Bitwarden-format export.

## Phase 0 device checklist
1. Fresh install → create vault → lock → unlock (master). 2. Enable biometric → relock → unlock by finger. 3. Enable PIN → unlock by PIN; 5 wrong PINs removes it. 4. Add login/note/card/identity; edit; favorite; trash → restore → erase. 5. TOTP with a known secret vs another authenticator. 6. Autofill on a login page and a sign-up page; save prompt on new login; update-password prompt. 7. Import a small Chrome CSV and a Bitwarden JSON. 8. Export encrypted → import & merge on a second install. 9. Health report. 10. Change auto-lock / clipboard time / screenshot setting.
