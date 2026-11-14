# AUTH Spike Assessment — D21–D25

**Snapshot:** local `wt-auth` checkout, 2026-11-14
**Owner:** Đinh Công Huy (AUTH)
**Status:** architecture assessment only; no P2 implementation, cryptographic library, or dependency was added.
**UI/device evidence:** no E2EE or multi-account device flow was available to inspect.
**Settings scope:** theme and language are persisted locally per device in DataStore. The plan's D21 phrase “settings đồng bộ” has no corresponding settings-sync API in the current contract; cross-device synchronization is therefore not claimed or implemented here.

## Decision summary

| Spike | Recommended D25 decision — AUTH agent assessment; group gate/approval pending | Evidence-based reason |
|---|---|---|
| End-to-end encryption (E2EE) | **NO-GO for implementation or an E2EE claim** | No group-approved protocol/library was identified in the reviewed repository inputs; device identity and key lifecycle, ciphertext message contract, and device proof are also absent from the reviewed checkout. The existing AES-GCM implementation protects AUTH credentials stored on one device; it does not encrypt chat content end to end. |
| Multiple accounts | **NO-GO for release implementation; architecture design only** | Backend sessions can represent multiple sessions, but Android currently has one global encrypted credential slot and one token provider. Chat cache/session isolation, account switching, and notification routing are not implemented. Room is not an Android dependency in the current module. |

The plan makes both features P2 and says to stop P2 work if the D20 core demo gate is not met. The current checkout does not establish that the P0 chat/media/voice flow passes end to end: README describes CHAT as a skeleton, the backend WebSocket currently keeps deduplication in memory and echoes messages to the sender, and the Android chat ViewModel marks messages failed when no real transport is active. Therefore this assessment does not authorize integration work that could displace P0/P1 stabilization. The instructor rubric scores chat, groups, user discovery, account management, languages, multimedia, rotation, quality, and demonstration; it does not list E2EE or multi-account as a scored requirement.

## E2EE assessment

### What exists in this checkout

- `KeystoreAuthSessionStore` encrypts the locally persisted access token, refresh token, and session metadata using Android Keystore AES-GCM. Its fixed preference entry and key alias store one AUTH credential set. This is local credential-at-rest protection, not message encryption between users.
- Backend password handling uses BCrypt; JWT authenticates a session. Neither establishes chat message confidentiality from the backend.
- The CHAT `MessageSendPayload` accepts readable `content`, and the current WebSocket skeleton sends a `message.created` containing that content back to the sender. No E2EE envelope, public-key model, device-key API, or ciphertext-only persistence contract is implemented.
- The Android dependency catalog contains no E2EE protocol library. No E2EE implementation or E2EE-specific test/device evidence was found in the reviewed files.

### Why the decision is NO-GO

The plan requires a reviewed protocol/library, key management and key verification, explicit effects on search and multi-device operation, and proof that the server cannot decrypt messages. The current inputs do not settle:

1. The exact protocol and maintained Android-compatible implementation, its license, supported Android versions, and update/security process.
2. Whether D25 scope is direct messages only or also group messages, voice, media attachments, and calls.
3. Per-user and per-device identity keys; device enrollment/removal; peer-key verification and change warnings; rotation, revocation, and recovery after reinstall or lost devices.
4. How offline delivery, multi-device delivery, attachment keys, notification previews, search, message edits/deletes, and backups work without exposing plaintext to the server.
5. How to verify on real devices that server records and logs contain ciphertext only and that valid recipients can decrypt after key changes and reconnects.

The AES-GCM transform in the `KeystoreAuthSessionStore` implementation must not be reused or described as an E2EE protocol. No home-grown cryptography or new crypto dependency should be introduced as part of this spike.

### Safe follow-up gate

Reconsider E2EE only after the group separately approves the protocol/library and scope, confirms the P0/P1 release path is stable, and reviews a written threat model. Before enabling even an experimental feature flag, require:

- A licensed, maintained protocol implementation reviewed for Android compatibility and dependency risk.
- A documented key lifecycle covering identity, peer verification, devices, revocation, rotation, recovery, and backups.
- A ciphertext-only API/storage/logging design covering message text and every in-scope attachment or notification surface.
- Tests for encrypt/decrypt failures, modified ciphertext, wrong keys, key replacement, offline/reconnect, and device revocation, plus a two-device demonstration.
- A default-off experimental flag and an explicit decision that missing keys fail closed; the ordinary chat path remains available.

Until this gate passes, report E2EE as **NOT IMPLEMENTED / NO-GO**, not “encrypted chat.”

## Multi-account architecture assessment

### Current boundaries

- Server-side `sessions` has UUID primary keys and a `user_id` index; it does not impose a single session per user. Its data model can represent several login sessions. This alone does not provide multiple accounts in the Android app.
- Android `KeystoreAuthSessionStore` uses one preferences file, one `encrypted_credentials` entry, and one fixed Keystore alias. `AuthSessionManager` coordinates refresh and logout for that single store; `AuthTokenProvider` has no account identifier.
- `AuthFeature` constructs one transport/API/session manager and passes the same provider to AUTH-owned repositories. `MainActivity` creates one `AuthFeature`; chat ViewModels are not constructed with an account context or the AUTH token provider.
- `AuthAppSettingsStore` uses one app-wide, device-local DataStore for non-sensitive preferences. The current D21–D23 working tree persists both the theme and language preferences there, and `MainActivity` applies those choices globally through the theme/locale composition. These settings are not account-scoped and do not form an account registry. Phone discovery remains server/account scoped; it is not a global device preference.
- No Room dependency is declared in the Android module’s build file or version catalog. The current chat ViewModel keeps temporary in-memory UI state; there is no implemented account-scoped local chat database/cache to switch between.
- Device-token/FCM support is not implemented in the inspected code. Its account association and notification routing would need a separate MEDIA/backend decision.

### Proposed isolation model (design only)

1. **Account registry:** maintain a small registry of signed-in account IDs and the selected account ID. Never put access/refresh tokens in this registry. Decide explicitly whether theme and language are global app preferences or account-scoped; keep phone discovery and other privacy settings server/account scoped.
2. **Credential slots:** change AUTH storage behind interfaces to address credentials by stable account ID. Keep each account’s tokens and expiry metadata encrypted with Android Keystore-backed storage; isolate key aliases and clear only the selected account’s slot on logout or unrecoverable corruption. Do not silently fall back to plaintext preferences.
3. **Immutable request context:** create an account-scoped session manager/token provider for each account. A request and its 401 refresh retry must stay bound to the same account even if the UI switches accounts. Do not implement a provider that looks up a mutable “currently active” token halfway through a request.
4. **Repository, cache, and queue scope:** require an account ID in local repository/DAO operations and outgoing message queues. Before persistence exists, decide the Room schema; options to review are a separate database file per account or an account ID on every row, primary/unique key, and DAO predicate. A shared table without mandatory account scoping is not acceptable. Decide local database-at-rest protection separately; encrypted tokens do not encrypt cached messages or attachments.
5. **Switch and logout lifecycle:** cancel/finish account-bound requests and sockets, replace account-specific ViewModels, clear transient UI state, drafts, selected conversation, and pending work from the visible session, then attach the chosen account’s provider and cache. Logout revokes only that account’s server session and clears its local keys/cache according to user-facing retention policy; other account sessions stay valid.
6. **Push and background work:** define how one device token can represent multiple signed-in accounts, how notifications select the correct account, and how background jobs carry an account ID. The current token/FCM contract is not sufficient evidence for this behavior; coordinate the design with MEDIA before implementation.

### Proposed account switcher UI flow — proposal only / not group-approved / VISUAL_UNVERIFIED

Text-only future concept; the Stitch reference was not accessible for visual verification. No Accounts entry or multi-account storage exists in the current app. This proposal does not change the NO-GO decision.

1. After group approval and account-scoped storage exist, add an **Accounts** entry to profile/settings to list signed-in accounts.
2. Show the active account and inactive account identities. **Add account** should open the existing login/register flow and persist the additional credentials in an isolated encrypted slot without replacing the active account.
3. If account-bound requests, sends, uploads, or queues exist, bind them to the originating account. Define a safe wait/cancel policy for switching; never replay pending work under the newly selected account. The current app does not implement account-scoped queues.
4. On a future switch, close any account-bound socket, replace account-bound ViewModels, clear transient visible state, and restore only the selected account's session and isolated UI state. Current CHAT wiring has no account-scoped switch lifecycle.
5. Offer **Log out this account** only after per-account revocation and credential/cache isolation are implemented; keep other signed-in account sessions intact.

### Threat model and acceptance tests

The primary multi-account threat is accidental cross-account exposure or action: wrong bearer token on a retry/socket, one user’s cache/draft/queue shown under another profile, logout revoking the wrong session, or a notification opening the wrong account. The design assumes the app process and Android user profile are not compromised; a rooted or malware-controlled device remains outside this P2 isolation guarantee.

Before any release claim, test at least two accounts with distinct sessions, profiles, and conversations:

- Sign in to A and B; switch repeatedly while HTTP requests and WebSocket reconnect/401 refresh are pending; verify each request and retry uses its original account.
- Verify profile, contacts, privacy settings, conversations, drafts, cached messages, attachments, and queued sends never appear under the other account.
- Logout A; verify only A’s server session is revoked and its local credentials are cleared, while B remains authenticated and can refresh.
- Restart/restore the app; test missing or invalidated Keystore material, offline mode, expired/revoked tokens, and account removal.
- Verify notification/deep-link and background-job routing for each account after the MEDIA token association is agreed.

### Effort and dependencies

This is a **high-effort, cross-module P2 feature**, not a D24-only switcher screen. A first useful implementation requires AUTH credential registry/storage/session-manager changes, shared navigation/network construction, CHAT account-scoped socket and data/cache work, a Room decision and persistence implementation, and MEDIA coordination for push/device-token routing. Logout, refresh, retries, offline queues, and process recreation all need account-isolation tests. The local code does not support a reliable calendar estimate; effort should be decomposed and estimated by the three owners after the storage and notification decisions are made.

This is a **partial D24 architecture/effort assessment**: it covers storage, token/cache isolation, cross-module effort, and a text-only switcher proposal. The group-approved UI flow and visual design remain open (`VISUAL_UNVERIFIED`). Proceed to implementation only after a group-approved design and an explicit P2 go decision. Otherwise retain one active account and focus on AUTH/P0/P1 stability.

## D21–D25 AUTH handoff status

- **D21:** existing block/privacy behavior remains in place. Theme and language are device-local preferences, not cross-device synchronized settings; no settings-sync contract was found, so that part is not claimed complete.
- **D22–D23:** app-wide VI/EN selection and the existing SYSTEM/LIGHT/DARK theme choice are persisted and applied through Compose resources. English and Vietnamese string resources have matching key sets. Rotation, small-screen/font-scale layout, and live-device locale refresh were not verified on a device.
- **D23/D25 P2 gate:** E2EE is **NO-GO / NOT IMPLEMENTED** because protocol, key lifecycle, cross-module contract, and P0/P1 stability evidence are insufficient. No cryptographic experiment or library was added.
- **D24:** multi-account remains **design-only / partial**. The text-only switcher flow is a proposal; no account registry, second credential slot, cache isolation, or switcher screen exists. Group approval and visual design are pending.
- **D25 Gate E:** this AUTH handoff records the P2 assessment only. It does not establish the group's end-to-end one-to-one call demo gate, which is owned by MEDIA and requires its own evidence.

**Verification for this handoff:** `.\gradlew.bat testDebugUnitTest assembleDebug --rerun-tasks` passed in `android-app/`; 42 unit tests passed and the debug APK assembled. Backend sources are unchanged in this task; the existing `.\gradlew.bat test --rerun-tasks` result at the tested HEAD had 11 passing Ktor tests. No device/UI test, visual Stitch comparison, PostgreSQL migration run, or end-to-end call demo was performed for this handoff.

## Evidence reviewed

- `AGENTS.md` §§2, 4–6, 11–12: P2 risk, approved stack, feature gates, cryptographic safety, and truthful testing requirements.
- `docs/requirements/rubric_vn.pdf`: scored criteria; no E2EE or multiple-account criterion.
- `ke_hoach_40_ngay_android_chat_kotlin.md` §§1, 3, 8: P2 definitions, D21–D25 responsibilities and D20/D25 gate rules, and risk guidance.
- `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/auth/data/AuthSessionStore.kt`
- `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/auth/session/AuthSessionManager.kt`
- `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/auth/session/AuthTokenProvider.kt`
- `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/auth/AuthFeature.kt`
- `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/settings/data/AuthAppSettingsStore.kt`
- D21–D23 settings implementation reviewed for this handoff: `android-app/app/src/main/java/vn/edu/hcmute/androidchat/MainActivity.kt`, `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/settings/data/AuthAppSettingsStore.kt`, `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/settings/model/AuthAppSettings.kt`, `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/settings/ui/ProfileSettingsScreen.kt`, `android-app/app/src/main/java/vn/edu/hcmute/androidchat/ui/theme/Theme.kt`, `android-app/app/src/main/res/values/strings.xml`, `android-app/app/src/main/res/values-vi/strings.xml`, and related settings tests.
- D21 contacts empty/retry-state implementation reviewed for this handoff: `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/contacts/ui/ContactsSearchScreen.kt`, `android-app/app/src/main/java/vn/edu/hcmute/androidchat/feature/contacts/viewmodel/ContactsViewModel.kt`, and related contacts tests.
- `android-app/app/build.gradle.kts`, `android-app/gradle/libs.versions.toml`
- `backend/migrations/V002__auth_users_sessions.sql`
- `backend/src/main/kotlin/vn/edu/hcmute/androidchat/backend/chat/ChatModels.kt`, `ChatWebSocketRoutes.kt`
- `README.md` project status section.

**Spike-assessment verification:** read-only source/document inspection only. No cryptographic code, dependency, protocol selection, device test, or PostgreSQL test was performed for the spike. The separate Android settings/contacts changes in this D21–D25 handoff were built and unit-tested as recorded above.
