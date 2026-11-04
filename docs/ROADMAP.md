# K2Pass — Roadmap

Priorities: security and correctness first, features second. Each phase ends with acceptance criteria; update `FEATURE_MATRIX.md` and `CHANGELOG.md` in the same commit.

## Backlog from the v1.2 review (owner: "test for a week, then fix everything")
Known bug (owner-reported, 2026-11-04): opening an item from inside a folder and then leaving it returns to the vault root (sections), not to the folder list the user came from. Fix: keep `filter`/scroll state outside `VaultScreen` (hoist to the activity) so View/Edit → back restores the folder.
Review items (priority order): 1) unsaved-changes warning when leaving the editor; 2) unit tests (crypto, merge, TOTP, importer) in CI; 3) move signing key out of the public repo into the B64 secret; 4) search across notes / IT fields / folder names; 5) unify the editor's old generator with the Generator tab engine (`Gen`); 6) split `MainActivity.kt` (1256 lines); 7) master-password attempt limiting; 8) accessibility (missing content descriptions, TalkBack, font scale); 9) clean orphan categories / v0.8 folders; 10) RTL/LTR handling for URLs and passwords; 11) UX: sorting, swipe actions, clone item, backup reminder, idle auto-lock, password-strength meter, empty-state import shortcut; 12) features: TOTP QR scan (camera), URL match options, re-prompt master for sensitive items, attachments, breach report (needs network decision), auto-sync + Windows (architecture decision first).

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

## Phase 3 — Product model & UI (partly done in v0.4: IT types, navigation; remaining: custom folders, real linked credentials, proper icons, accessibility)
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
