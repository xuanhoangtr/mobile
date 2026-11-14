# AUTH D26–D30 Hardening and Feature-Freeze Handoff

**Owner:** Đinh Công Huy (AUTH)
**Planned window:** D26–D30
**Working branch:** `feature/auth-email`
**Scope:** Android AUTH, profile/session handling, backend AUTH/users, tests, and AUTH documentation.
**Status:** PARTIAL — AUTH hardening and freeze review; no P2 feature implementation.

## Gate decision

- **E2EE:** NO-GO / NOT IMPLEMENTED. The D21–D25 assessment found no approved protocol, device key lifecycle, ciphertext-only message contract, or cross-module verification evidence. No cryptography or dependency was added.
- **Multiple accounts:** NO-GO for this release / DESIGN ONLY. The app still has one encrypted credential slot and one `AuthTokenProvider`; account-scoped CHAT cache/socket state and MEDIA notification routing are absent. Do not add a switcher or imply account isolation in this freeze slice.
- **P0/P1 focus:** Continue to stabilize AUTH and preserve the current email/password, profile, contacts, block, theme, language, and session flows. D30 is the project feature-freeze gate; any later work belongs to the plan's test/fix/documentation period unless the group explicitly reopens scope.

## D26–D30 AUTH status

| Day | Planned AUTH work | Status and evidence |
|---|---|---|
| D26 | If P2 is not safe, fix AUTH/contacts/security issues. | **PARTIAL.** P2 remains closed. Fixed `refreshAfterUnauthorized()` to clear locally revoked/expired sessions without a network refresh; reviewed existing contacts/block behavior. This does not establish cross-module delivery enforcement for blocks. |
| D27 | Rotation checks for auth/profile/settings; logout, refresh, and OTP limits. | **PARTIAL / NOT DEVICE-TESTED.** `MainActivity` obtains `AuthViewModel` with Activity-scoped `by viewModels`; auth form/profile state is exposed through its `StateFlow`, so normal configuration recreation retains the ViewModel. Unit tests cover expired/revoked unauthorized refresh and logout success/failure paths. No device rotation smoke test was recorded. OTP is not implemented: the provider remains unselected and there is no OTP endpoint to rate-limit or test. |
| D28 | Keep E2EE behind an isolated experimental flag only if it passes the security gate; otherwise close the spike. | **NO-GO / NOT IMPLEMENTED.** The prior spike records the missing protocol, key management, cross-module contract, and proof requirements. No experimental flag, crypto implementation, or protocol dependency was added. |
| D29 | If multi-account has not passed its gate, finish AUTH/profile tests. | **PARTIAL.** Keep the single-account design. Regression tests cover expired/rejected refresh, logout server/network failures, ViewModel logout failures, validation, and profile update. Multi-account isolation is not implemented or tested. |
| D30 | Review permissions, credentials, logs; document API and test checklist; freeze scope. | **PARTIAL.** Contract and security boundaries are documented below and Android/backend checks passed on this AUTH worktree. This does not claim a release candidate, deployment, device demo, or full Gate F pass. |

## AUTH security review notes

- Backend registration hashes passwords with BCrypt. Refresh tokens are opaque and stored server-side as hashes; refresh rotates the token and logout revokes the caller's session using the authenticated user/session identity.
- Authenticated requests validate active session state. The Ktor logging configuration is documented in `Application.kt` as method/path only, with Authorization headers and request bodies excluded.
- Android stores access/refresh credentials in the AUTH `KeystoreAuthSessionStore` using Android Keystore AES-GCM. This is local credential-at-rest protection, not E2EE. Theme/language DataStore values are non-sensitive preferences and must not be repurposed for tokens.
- Profile/search and phone-discovery/block APIs derive the caller from validated JWT/session context. Search/friend/block responses omit private phone/email fields as described in `docs/api-contract.md`.
- **Known scope limits:** OTP/provider verification and OTP throttling are not available; the backend also has no general login brute-force rate limiter. No settings-sync endpoint exists. CHAT must enforce block checks in message/WebSocket delivery before end-to-end blocking can be claimed; CHAT and MEDIA still need to integrate AUTH's `AuthTokenProvider` according to the shared contract. Existing README also describes chat as a skeleton. No in-memory-only login limiter was added because it would not provide reliable enforcement across backend instances.
- No credentials, test OTP, token, or secret is included in this report.

## Regression checks to keep at the D30 freeze

Android unit tests should cover:

1. Invalid email/password and registration validation reject before repository calls.
2. Login/register success clears the password field and publishes the authenticated user.
3. Profile changes validate and update the current authenticated profile.
4. Expired access tokens refresh and persist rotated credentials; expired refresh credentials are cleared without a request.
5. An unauthorized refresh clears locally expired or revoked credentials without a network request; a server-rejected refresh clears the local session.
6. Successful logout revokes then clears credentials; server/network logout failures preserve the session for retry.
7. A failed `AuthViewModel.logout()` preserves the authenticated user and exposes an error instead of silently claiming logout succeeded.

Backend Ktor tests should cover invalid credentials, refresh rotation/reuse rejection, logout revocation, unauthenticated/expired/revoked session access, profile validation/ownership, user-search privacy, phone-discovery privacy, and block ownership. OTP and multi-account test cases remain **NOT APPLICABLE / NOT IMPLEMENTED** until their upstream decisions and implementations exist.

## Verification record

- The D21–D25 handoff recorded **42 passing Android unit tests** and a successful debug APK build. That run predates D26–D30 edits, so it is not evidence for the current tree.
- The D21–D25 handoff recorded **11 passing Ktor tests**. Backend sources should be rerun as part of D30/final integration; no new backend test result is claimed here.
- **D26–D30 verification:**
  - `android-app`: `.\gradlew.bat testDebugUnitTest assembleDebug --rerun-tasks` — **BUILD SUCCESSFUL**, 49 unit tests, 0 failures/errors; debug APK assembled at `android-app/app/build/outputs/apk/debug/app-debug.apk` (13,242,465 bytes).
  - `backend`: `.\gradlew.bat test --rerun-tasks` — **BUILD SUCCESSFUL**, 11 tests (3 `ApplicationTest` + 8 `AuthRoutesTest`), 0 failures/errors. PostgreSQL was not configured, so these are Ktor test-application tests, not migration/database integration evidence.
  - Existing compiler warnings remain for `ExperimentalSerializationApi` opt-in in `backend/.../auth/AuthModels.kt`; they did not fail the build.
- **Device rotation / UI / Stitch:** NOT VERIFIED. No device evidence or screenshot comparison is attached.
- **PostgreSQL migrations / deployed backend / two-device auth or call integration:** NOT VERIFIED by this AUTH handoff.

## Freeze and next integration steps

1. Re-run the Android/backend commands after CHAT and MEDIA integration for the group's Gate F; the passing results above cover this AUTH worktree, not the fully integrated release tree.
2. Recheck generated APK/source status, `git diff --check`, and secret/log hygiene before the local AUTH commit.
3. Have CHAT integrate the shared `AuthTokenProvider` for its WebSocket/client and enforce server-side block rules in message delivery. Have MEDIA integrate the same provider for HTTP/call/push flows. These are cross-owner integration tasks and are not claimed complete here.
4. Keep E2EE and multi-account out of the release until the group approves the design and each security/isolation acceptance gate is demonstrated.
5. Treat this file as a handoff/checklist, not proof that D30 Gate F or the app-wide feature freeze passed. Final Gate F requires the integrated group's APK/backend build and the plan's full Done/Partial/Not done inventory.

## References reviewed

- `AGENTS.md` §§0–12 and §16: evidence hierarchy, ownership, freeze/gates, security, and handoff rules.
- `ke_hoach_40_ngay_android_chat_kotlin.md` §§1, 3, 4, and 8: feature ownership, D26–D30 AUTH tasks, Gate F, and security risks.
- `docs/requirements/rubric_vn.pdf`: assessed requirements and academic-integrity/reporting expectations.
- `docs/api-contract.md`: AUTH session/token provider, profile, phone discovery, friends, and block contracts.
- `docs/auth-spikes-d21-d25.md`: prior E2EE/multi-account NO-GO and limitations.
- `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/auth/` and `feature/contacts/` source/tests; `backend/src/main/kotlin/vn/edu/hcmute/androidchat/backend/auth/` and corresponding Ktor tests.

**AI disclosure:** AI assisted drafting/review of this handoff and AUTH test work; the project README already records AI assistance. The AUTH owner must review and be able to explain the final changes.
