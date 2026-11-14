# AUTH D36–D40 Validation and Handoff

- **Owner:** Đinh Công Huy (AUTH)
- **Planned window:** D36–D40
- **Branch:** `feature/auth-email`
- **Status:** PARTIAL
- **UI reference:** NOT VERIFIED — no Stitch screen/export was available in this checkout.

This handoff covers only the AUTH contribution. The local Android and backend verification completed, README setup instructions were smoke-checked, and AUTH report/slide artifacts were prepared. The group release gate remains unverified because PostgreSQL, device flows, CHAT/MEDIA integration, the group demo, and final submission package were not exercised here.

## D36–D40 progress

| Day | AUTH work | Evidence and status |
|---|---|---|
| **D36** | Reviewed AUTH scope and local branch history; updated README backend setup, migrations, limitations, and test summary. | `.\gradlew.bat run` started the server on a temporary local port; `/health` returned `UP` and `database: NOT_CONFIGURED` with `DB_URL` absent. This verifies startup only. **PARTIAL:** the README was not exercised with PostgreSQL or on Android, and the group report is not integrated. |
| **D37** | Prepared the editable AUTH deck at `docs/submission/presentation-auth-d36-d40-final-v2.pptx`, covering architecture, discovery/privacy, VI/EN settings, security limits, test evidence, and remaining work. Oral-defense prompts are below. | AUTH deck exists; group slide deck and 8–12 minute demo script are **NOT VERIFIED**. Stitch visuals are **NOT VERIFIED**. **PARTIAL.** |
| **D38** | Regressed available AUTH tests and finalized README limitations. | Android/backend suites pass locally. No Android device run, screenshot/video, or group dry-run checklist is recorded. D38 group dry-run is **NOT VERIFIED**. **PARTIAL.** |
| **D39** | Reviewed dependency metadata, AI disclosure, and local commit authorship. | [Direct dependency license audit](auth-dependency-license-audit-d36-d40.md) lists 18 Android and 17 backend direct libraries plus plugin metadata. It does not cover transitive licenses or establish full compliance. README and AUTH report disclose AI assistance. **PARTIAL.** |
| **D40** | Prepared the AUTH report and presentation for group integration. | Huy's student ID is not present and has not been invented. Group DOCX/deck, final APK evidence, screenshots, ZIP creation/extraction, and final rubric check are **NOT VERIFIED**. **PARTIAL.** |

## Build, test, and smoke evidence

- Android command, run from `android-app/`: `.\gradlew.bat testDebugUnitTest assembleDebug --rerun-tasks` — **PASS**, `BUILD SUCCESSFUL`; 52 app-wide unit tests across 8 XML suites, with 0 failures, 0 errors, and 0 skipped. This is not 52 AUTH-only tests. A debug APK was generated at `android-app/app/build/outputs/apk/debug/app-debug.apk`; it was not installed or demonstrated.
- Backend command, run from `backend/`: `.\gradlew.bat test --rerun-tasks` — **PASS**, 12 tests (3 `ApplicationTest`, 9 `AuthRoutesTest`), with 0 failures, 0 errors, and 0 skipped. Ktor route tests use `InMemoryAuthRepository`; `DB_URL` was absent, so they do not verify PostgreSQL or migrations.
- README startup smoke, recorded manually in this handoff: `.\gradlew.bat run` started Ktor at `127.0.0.1:18178`; `GET /health` returned `UP` and `NOT_CONFIGURED`. The process was stopped after the health response. No separate stdout log was retained and no database-backed route was tested.
- The packaged debug APK references the debug network security configuration. Cleartext is disabled by default there and allowed for `10.0.2.2`, `127.0.0.1`, and `localhost`. No release APK was available for inspection; release policy is supported by source configuration only.
- Test result files: `android-app/app/build/test-results/testDebugUnitTest/` and `backend/build/test-results/test/`. The recorded run was on 2026-11-14 local time; no CI result is recorded.

## AUTH behavior and cross-module boundary

- Registration/login use email and password. Passwords are BCrypt-hashed server-side; access JWTs identify the user/session; opaque refresh credentials are stored as hashes, rotated, and revoked on logout.
- Android stores credentials through its Keystore-backed encrypted store and exposes `AuthTokenProvider` for access-token retrieval and refresh handling. The API contract requires CHAT/MEDIA clients to use that provider. Their WebSocket/HTTP/call integration is not confirmed in this checkout.
- User search omits private email/phone fields. Phone discovery requires verified metadata and opt-in; route tests create verification metadata directly in a fixture. No OTP provider or OTP request/verify route is implemented.
- AUTH block filtering covers search/relationship routes. CHAT still needs to enforce blocks for message and WebSocket delivery.
- VI/EN and theme preferences are stored locally. No server settings-sync endpoint is documented. Avatar upload depends on MEDIA.
- E2EE and multi-account remain NO-GO for this release. A general login brute-force limiter remains open.

## Oral-defense prompts for Huy

1. **Why hash passwords and refresh tokens?** Password hashes reduce exposure if the user table leaks. Opaque refresh values are stored as hashes so a database read does not reveal usable refresh credentials; rotation and revocation give the server control over session lifetime.
2. **How does phone discovery protect privacy?** The lookup requires a verified-phone marker and explicit discovery opt-in, and results avoid exposing email/phone. Current tests set verification metadata directly; provider-backed OTP still needs implementation.
3. **What do the test counts prove?** They prove the local Android JVM suite and Ktor in-memory route/application tests passed. They do not prove PostgreSQL migrations, device behavior, CI, or end-to-end CHAT/MEDIA flows.
4. **How should CHAT/MEDIA authenticate?** They should call the shared `AuthTokenProvider` for bearer access tokens and refresh after 401. That is the contract; integration remains open. CHAT must also enforce block state at delivery.
5. **What is the release status?** AUTH is PARTIAL. OTP/provider selection, login rate limiting, PostgreSQL and device evidence, and group integration remain incomplete or unverified.

## Git, disclosure, and submission limits

- Worktree-local identity: `huydc7 <dinhconghuy101006@gmail.com>`; branch `feature/auth-email`. The seven preceding AUTH commits cited in the report are present in local history with that author. This turn used local Git only and did not inspect remote publication status.
- The intended local commit scope is the README, AUTH report, slide deck, validation handoff, and dependency audit listed below. No application source, secret, key, token, or password is included. The local commit record will be added to `docs/contribution-log.md`; no remote was queried or changed.
- The direct dependency audit reads cached POM metadata only. Transitive dependencies, source `LICENSE`/`NOTICE` files, and full obligations/compatibility require a separate review.
- Before group packaging, supply the group number and all three real MSSVs; merge the AUTH report/slides into the group artifacts; record device and dry-run evidence; create and reopen the ZIP with `.git`, source, APK, and screenshots; exclude `build/`, `node_modules/`, keystore files, and `keystore.properties`. Do not invent the rubric's group ZIP filename while the group number/MSSVs are missing.

## Task report (AGENTS.md §16)

- **Task / Owner / Window:** AUTH D36–D40 — Đinh Công Huy — submission verification and preparation.
- **Status:** PARTIAL.
- **Sources checked:** `AGENTS.md`; `ke_hoach_40_ngay_android_chat_kotlin.md` §4; `docs/requirements/rubric_vn.pdf`; `docs/api-contract.md`; D31–D35 handoff; README; local source/test artifacts. Stitch: NOT VERIFIED.
- **Files created or changed:** `README.md`; `docs/auth-validation-d36-d40.md`; `docs/auth-dependency-license-audit-d36-d40.md`; `docs/submission/auth-module-report-d36-d40.docx`; `docs/submission/presentation-auth-d36-d40-final-v2.pptx`. `docs/contribution-log.md` will be created after the artifact commit so it can record the real hash.
- **Android build/tests:** PASS — `testDebugUnitTest assembleDebug --rerun-tasks`; 52 app-wide tests across 8 suites. No device test.
- **Backend tests:** PASS — `test --rerun-tasks`; 12 tests, all using in-memory repositories. README server startup smoke PASS; PostgreSQL route test NOT RUN.
- **Git:** `feature/auth-email`; worktree author `huydc7 <dinhconghuy101006@gmail.com>`; the artifact commit is pending. After it exists, a follow-up local commit will record its hash in `docs/contribution-log.md`. No remote access or push.
- **Remaining dependencies:** OTP provider/routes and login rate limiting; PostgreSQL/device/CI evidence; CHAT/MEDIA token integration and CHAT block enforcement; group report/deck/demo/ZIP; real group number and student IDs.
