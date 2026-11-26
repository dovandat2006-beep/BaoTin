# Android release build (Expo / React Native)

## Verified APK download on Web

The Web header shows **Tải APK** only when `/downloads/android.json` contains a
valid same-origin, content-addressed APK manifest. The confirmation sheet shows
version, size and SHA256. No old SDK57 APK, AAB or Hermes-only export is substituted.

After the final source is locally tested, build the actual signed APK on an
independent source copy using the existing external acceptance keystore. Run the
package verifier and Android `apksigner verify`; record the package/version/signing
certificate, APK hash/size and canonical source SHA256 from `node ci/source-snapshot.cjs` before CNG/build in the verification report. Publication requires matching `sourceSnapshotSha256`,
`packageName=vn.baotin.app`, `versionName`, `versionCode`, `apkSigningVerified=true`
and `signingCertificateSha256` in addition to the package verifier PASS fields.

```powershell
node scripts/verify-android-release.cjs --apk C:/external/app-release.apk --sdk C:/external/android-sdk --origin https://baotin.rimiprod.me --source-snapshot SOURCE_SNAPSHOT_SHA256 --output C:/external/verified-apk.json
node scripts/publish-android-download.cjs --apk C:/external/app-release.apk --verification C:/external/verified-apk.json --destination C:/external/published-android --version 0.1.0 --version-code 1 --source-snapshot SOURCE_SNAPSHOT_SHA256
```

Copy that verified public directory into the existing Jenkins controller's
`/var/jenkins_home/android-releases/SOURCE_SNAPSHOT_SHA256` artifact store (no signing
file/key). Pass `ANDROID_DOWNLOAD_ID=SOURCE_SNAPSHOT_SHA256` to FullStack/Frontend.
`ci/build-web.sh` rejects APK source/hash mismatches against the selected Android
publication ID and stages only the APK
and public manifest into the static web archive. The immutable web artifact then
deploys normally through Jenkins; no runtime mutation, DNS change, new port or
special download service is needed. Keep APK binaries out of Git.

Web and Android are independently versioned releases. The default Jenkins pin is
the previously verified Android publication
`2db533d82f01bfe8ef75b40f76034cb13bc29d88d7f5b5a11d938955f271c211`
(APK SHA256 `2c305f5a7a20cbaad57551e027b34d91d9d90ab7d407f27d971ed0fc5fe20779`).
New web commits do not relabel that APK or claim that it contains newer web code.
Choose a newly built and verified publication ID when releasing a newer Android
app. Explicitly clearing the ID omits the download; a missing/corrupt selected
publication fails the build rather than silently removing the button.

## Three distinct outputs

- `npm run build:android:bundle`: Android **Hermes bytecode and assets**, not an installable application. A valid `EXPO_PUBLIC_API_URL` is mandatory. `verify:android:js` is the explicit JS-only diagnostic variant, not native release proof.
- `npm run build:android:aab`: signed release Android App Bundle for a distribution tool/store; an AAB is not directly installed like an APK.
- `npm run build:android:apk`: signed standalone release APK with bundled JS/assets. It does not depend on Expo Go or a running Metro server.
- `npm run build:android:release`: builds both AAB and APK through the generated Android Gradle wrapper.

## Local prerequisites

Use the locked dependencies (`npm ci`), Node24 and JDK21. SDK58/RN0.88.1 selects compile SDK37/build-tools37.0.0, target36, minimum24 and NDK27.1.12297006; install the matching SDK/CMake components requested by the generated native wrapper. The earlier SDK57 acceptance APK remains a historical artifact, not proof of a new SDK58 package. Set `JAVA_HOME`, `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and optionally an external `GRADLE_USER_HOME`. Reserve space for native dependencies/build outputs; this first build downloads substantially more than a Metro export.

The scripts use Expo CNG prebuild and the generated `android/gradlew` (`gradlew.bat` on Windows); generated native directories and binaries are ignored by Git. Expo prebuild can update the development `android`/`ios` npm commands. No `run:android`, installation, emulator or device task is invoked by release builds. Metro, Gradle and native compilation concurrency is bounded for shared hosts.

## Gateway and signing

```powershell
$env:EXPO_PUBLIC_API_URL = 'https://baotin.rimiprod.me'
$env:BAOTIN_ANDROID_SIGNING_PROPERTIES = 'C:/private/baotin/signing.properties'
npm run build:android:release
```

The signing properties file and referenced keystore must remain external, never committed. Its required fields are `storeFile` (absolute keystore path; use forward slashes on Windows), `storePassword`, `keyAlias`, `keyPassword`. Supply their actual values through your private file, not command arguments. The Gradle initialization script replaces Expo's template debug signing on the release variant; an absent signing file fails instead of silently using the debug key. No secret belongs in `EXPO_PUBLIC_*`.

Only exact HTTPS Gateway origins are accepted; no `/api` suffix, credentials, query or fragment. An absent origin fails before prebuild, preventing a standalone Android binary with demo Auth or relative API URLs. Dotenv loading is disabled in these explicit release commands, so the selected environment is authoritative.

The user-approved **acceptance signing key is separate from Google Play production signing**. Retain that key to install later acceptance updates over the same package. A differently signed existing `vn.baotin.app` cannot be upgraded by this test APK; Android will report a signature mismatch. Do not overwrite a production keystore or remove an existing installation automatically.

Default output paths:

```text
android/app/build/outputs/bundle/release/app-release.aab
android/app/build/outputs/apk/release/app-release.apk
```

## Build acceptance and manual installation acceptance

Run `npm run typecheck`, `npm test`, `node --test scripts/android-build.test.cjs`, Expo Doctor, dependency audit and the real native wrapper build. Native Auth tests cover Android endpoints, real role-derived profile behavior, MFA response shape, SecureStore refresh rotation/restart, revoked refresh handling, temporary network loss and logout cleanup; these are adapter tests, not device execution.

Check the resulting APK's package/version/minSdk, INTERNET permission, non-debuggable release status, signed certificate, embedded Gateway origin/Hermes bundle/fonts, 16KB shared-library packaging/alignment and AAB structure. The app intentionally blocks overlay and obsolete shared-storage permissions; DocumentPicker uses the system picker and evidence sharing uses app-private temporary files. `expo-system-ui` supports the configured automatic native appearance.

The user will perform installation/device testing separately. No build result alone proves native login/navigation/keyboard/back behavior, file picker/share UI, lifecycle recovery or device notifications. Test those on the installed APK, including registration/password/MFA, reopening a session, refresh/logout, URL/text scan/history, watchlist/in-app notifications and owned reports/evidence. Use synthetic test data and distinguish expected API errors from crashes.

Google/Facebook login/linking and account-recovery UI are connected to Gateway;
positive provider consent still needs the exact provider-console callback setup.
FCM registration accepts a real provisioned token, but producing an APK does not
provision platform push or prove device delivery. Search/Campaign/Analytics backend
scaffolds remain unavailable/error states without invented results. All client
business requests go through Gateway; no internal credentials or addresses are packaged.

## Reverting source changes

Keep a source bundle/original commit and build on an independent copy. The task's verification record includes a source-only rollback script tested in a new directory. Source rollback does not delete device data, rotate signing keys, modify backend data or undo a deployed backend release. Keep the signed APK/AAB and their SHA256 manifest associated with the exact tested source revision.

## Search Android increment — 2026-11-11

New candidate app version is 0.1.1 / versionCode2, with public URL Search through
Gateway. Build from an independent source copy using the existing external signing
key; publish only after the package/signature/source-snapshot verifier passes. Pass
the verified new source ID explicitly to Jenkins FullStack/Frontend. Earlier APK
publication hashes above remain historical rollback references, not this build.

## Verified Search / SDK patch candidate — 2026-11-12

Expo58.0.24 and the nine-package patch alignment passed the full20-step workflow
on a fresh dependency installation: Doctor20/20, TS, Jest121, tooling48, Python
verifier, Android/iOS Hermes, demo/report/live Search/admin/account browser suites.
GitHub Frontend CI43071238029 is SUCCESS at86ec161. New signed Android0.1.1/code2
is package/signature/Hermes/16KB-alignment verified. Deployment is a separate
Jenkins promotion; this source entry does not predict its result.
