# Expo SDK58 + real Gateway mode — 2026-10-28

## Versions and Expo Go

Fresh SDK58 compatibility metadata checked on2026-11-12 selects Expo **58.0.24**,
Constants58.0.16, Crypto58.0.8, DocumentPicker58.0.5, Font58.0.12,
SecureStore58.0.3, Sharing58.0.23, StatusBar58.0.5 and WebBrowser58.0.8.
SafeAreaContext5.11.1 and jest-expo58.0.14 remain unchanged. Expo Doctor20/20 and
all20 candidate workflow steps passed on a clean locked install before publication.
React19.3.0, React Native0.88.1, Reanimated4.7.1 and Worklets0.13.0 remain unchanged.
Use Node24 and `npm ci`; do not upgrade React/RN/modules independently or use
`--legacy-peer-deps`/`--force` to hide mismatches.

Use an **SDK58-compatible Expo Go**, not a client for SDK57. Select the current platform-specific SDK58 client metadata, rather than an older global fallback field; the project's locked SDK major and runtime manifest are the relevant checks. Check https://expo.dev/go for the client appropriate to the device. Compilation/manifests are not an installed-device test.

```powershell
npm ci
npm start        # Expo Go QR; port18081, bounded workers
npm run android # Expo Go Android launch/QR, not an implicit dev-client build
npm run ios     # macOS: iOS Simulator; Windows/Linux: server + QR for iPhone
npm run web     # local Expo Web through the same public Gateway
```

The default port is **18081**, avoiding the existing Jenkins/other8081 listeners. Override with `npm start -- --port PORT` or the `EXPO_DEV_PORT` process environment variable. Expo Go's phone and host must be able to reach the displayed Metro address; use the normal LAN QR on the same network, not `--localhost` for a physical phone. The public API remains HTTPS and never points the phone to the computer's localhost.

## No implicit mock fallback

Without any environment variables, the app uses `https://baotin.rimiprod.me` and real Auth/scan/history/watchlist/notification/report/evidence/moderation APIs. `.env.example` contains only that public configuration; local `.env` is ignored by Git. Server errors and absent data do not switch to sample data. Identity and role always come from Auth; native refresh uses SecureStore, Web refresh uses HttpOnly cookie + CSRF.

The legacy demo UI is **explicit test-only**, selected with `EXPO_PUBLIC_DEMO_MODE=1` and no API URL. It is used by existing synthetic UI regression tests, never by normal run commands or release builds. Release guards reject a demo flag even if an API URL is also supplied.

The History source helper likewise requires explicit demo selection. A missing
live capability/client yields unavailable, not mock history; API errors propagate.
Demo-only screens label local data/drafts without claiming server submission.

Search now exposes the public URL/hostname projection to guests through Gateway. Real-mode loading/errors/empty results never become demo data. Other Search target types, Campaign projection and historical backfill remain explicit backlog. The Analytics dashboard calls its Gateway adapter for ADMIN; Analytics-Service composes live summary, queue and counts from Report/Threat/Reputation, and upstream failures are shown as errors rather than invented metrics. Moderators continue actual work in **Báo cáo → Kiểm duyệt**. SSO/account-recovery UI is connected as documented in ACCOUNT_API_INTEGRATION.md; real provider consent and native FCM delivery remain acceptance items, never emulated with mock identities/device tokens.

## Local Web Auth and networking

The `npm run` development wrappers enable a Web-only same-origin `/api/` proxy in Metro. It forwards to the fixed public HTTPS Gateway, never directly to a service. This preserves localhost HttpOnly/SameSite refresh cookies rather than relying on blocked cross-site third-party cookies. Native Expo Go ignores the Web-only proxy and calls the public HTTPS origin directly.

The operator's external `AUTH_ALLOWED_ORIGINS`/Gateway allowlist includes exact localhost/127.0.0.1 origins for port18081 (and existing8081); no wildcard or arbitrary HTTP LAN origin is allowed. Use localhost for Web development. A custom Web port requires an explicitly reviewed matching origin. Production static Web has no Metro proxy and uses the public HTTPS API origin packaged at build time.

## Secrets and deployment

PostgreSQL/Redis/MinIO root credentials, service credentials, Auth keys and runtime config-trees stay outside source/build context in the existing restricted secret directory. Dependency containers use existing read-only file mounts and `_FILE`; Spring services use separate schema roles and config-trees. Operator/migrator credentials never enter runtime services. Only public API/build flags use `EXPO_PUBLIC_*`; backend addresses, tokens, passwords and raw PII do not.

Reviewed public methods/paths agree with Contracts for Scan/History, Notification/Watchlist and Report/Moderation/Evidence. Model inference, Reputation internal endpoints, database/Redis/Kafka/MinIO administration remain private. Canonical risk/report events retain transactional outbox/inbox behavior; AI does not verify reports. No contract/API change was fetched during the initial per-repo pull for this update.

Jenkins builds exact reviewed per-repo main SHAs with unchanged secret/Sonar/security gates before packaging/promotion. Direct-runtime JARs remain loopback-only. The deploy node's user-approved TEMP/TMP/npm/Gradle cache directories are under `L:/BaoTin-agent-state`, not a global Windows environment change; JVM processor count and build/test concurrency are bounded. Existing volumes, Auth keys and shared tunnel/DNS are preserved. API/feature checks after terminal Jenkins success are separate from Expo compilation.

## Verification

```powershell
npx expo-doctor@1.21.5
npm run typecheck
npm test
npm run test:tooling
$env:EXPO_PUBLIC_API_URL='https://baotin.rimiprod.me'
npm run build:android:bundle # verified Hermes + correct Gateway; not an APK
npm run build:ios:bundle     # verified Hermes + correct Gateway; not an IPA
npm run build:web
```

Standalone Android AAB/APK signing remains external; see ANDROID_BUILD.md. SDK58/RN0.88 uses compile SDK37/build-tools37.0.0 and NDK27.1.12297006; target SDK is36 and minimum24 per the selected RN tooling. Native package builds require those installed tools; Hermes export success is not a signed-native-package claim. iOS IPA builds require Xcode/macOS/signing, whereas Web/Android/iOS JS/Hermes export and Expo Go manifests can be checked here.

Windows dependency placement: keep a verified physical `node_modules` inside the
project for exports. A cross-drive junction can put drive-colon asset paths into
the export and fail at font copy. The2026-11-04 patch set was installed/tested in
an isolated same-project tree before replacing the workspace link; its former
F: target was preserved. Do not edit shared dependency files to hide this issue.

Patch refresh during verification: Expo58.0.21, file-system58.0.13, font58.0.11, sharing58.0.21, system-ui58.0.7 and jest-expo58.0.13. Jest preset0.88.1 satisfies its declared peer. Fresh GitHub CI and EXPO_NO_CACHE=1 Expo Doctor on2026-10-29 now require RN0.88.1, matching the bundled metadata. The reviewed patch lock aligns React Native, its CLI/Gradle/Metro/Babel/virtualized-lists dependencies and Hermes compiler260318099.0.5 without upgrading the Expo SDK or excluding a doctor check. Keep fresh doctor in the pre-push verification; a previously cached20/20 result does not establish compatibility with new metadata. The accepted FullStack37/RN0.88.0 APK remains historical until a new source-bound native build and immutable promotion pass.
