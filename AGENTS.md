# Mercurygram — Development Guide

Mercurygram is a FOSS Android Telegram client (package `it.belloworld.mercurygram`), built by rebasing two patch sets on top of upstream Telegram:

- **[TF]** patches — de-googling (from Telegram-FOSS project, forward-ported manually)
- **[MG]** patches — Mercurygram features

Upstream: https://github.com/DrKLO/Telegram.git (remote `upstream`)

---

## Rebase Workflow

Mercurygram is maintained as a **rebase on top of upstream/master**, not a merge fork. This means:

- **Keep the commit count low.** Every commit must survive future rebases onto new upstream versions. Fewer, well-scoped commits = fewer conflicts.
- **When fixing a bug or adjusting an existing feature, amend the related commit** (`git commit --amend` or rebase -i fixup) rather than adding a new "fix" commit.
- **Never squash [TF] and [MG] commits together.** They serve different purposes and may need to be separated in future rebases.

### Commit naming convention

```
[TF] short description of FOSS patch
[MG] short description of Mercurygram feature
```

### Rebasing to a new upstream version

1. `git fetch upstream`
2. `git rebase upstream/master` on the working branch
3. Resolve conflicts — upstream changes heavily modify the same large files (ChatActivity, ProfileActivity, etc.)
4. Re-verify that all features still work

---

## Code Isolation Principle

**Isolate MG/TF changes from upstream code as much as possible.** This reduces rebase conflicts.

### Preferred patterns

- **New file over modifying a large upstream file.** E.g., `MessageDetailsActivity.java` is a new file; only a tiny hook is added to `ChatActivity.java`.
- **Add a constant or static method in a small helper class** rather than inlining logic into a 40,000-line upstream file.
- **MG-specific SharedConfig fields** use the `mg_` prefix in SharedPreferences and are declared in a dedicated block near line 238 of `SharedConfig.java`.
- **New UI screens** go in `it.belloworld.mercurygram.ui` package.
- **Helper classes** (e.g. `MonetHelper`) go in `tw.nekomimi.nekogram.helpers` (kept for historical reasons).

### Anti-patterns to avoid

- Inline logic changes in the middle of methods in large upstream files.
- Adding new imports to already-heavily-modified files (each import is a potential conflict line).
- Feature flags that touch many files — prefer a single `SharedConfig` boolean read at the call site.

---

## Build Instructions

### Prerequisites

- JDK 17 (`JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk`)
- Android SDK with NDK 21.4.7075529 and build-tools 35.0.0
- `ninja-build`, `meson`, `pip` (for native deps)
- A `gradle.properties` file with `APP_ID`, `APP_HASH`, and signing config (see below)
- An `API_KEYS` file in the project root with `APP_ID` and `APP_HASH`

### Building the APK

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew assembleAfatRelease   # or assembleAfatDebug
```

The `buildNativeLibs` Gradle task automatically initializes submodules and builds native libraries (libvpx, dav1d, ffmpeg, BoringSSL, tde2e) from source on the first run. Subsequent runs skip it entirely (sentinel: `jni/tde2e/build/arm64-v8a/libtde2e.a`). To force a rebuild, delete the sentinel and re-run.

> **Note**: `sourceSets.main.jniLibs.srcDirs = ['./jni/']` in both `TMessagesProj/build.gradle` and `TMessagesProj_App/build.gradle` causes `merge*JniLibFolders` tasks to snapshot all of `jni/` as inputs. In `TMessagesProj`, CMake/native tasks also read generated archives and headers from `jni/*/build` and `ffmpeg/include`. Running those consumers in parallel with `buildNativeLibs` causes intermittent failures (`NoSuchFileException`, missing imported static libraries, or half-written generated headers). This is prevented by `afterEvaluate` blocks that add explicit `dependsOn buildNativeLibs` to every `merge*JniLibFolders` task, and in `TMessagesProj` also to the `configureCMake*`, `buildCMake*`, and `externalNativeBuild*` task families.

### Per-architecture F-Droid flavors

In addition to the fat `afat` flavor (all ABIs), four single-ABI flavors exist for F-Droid distribution:

| Flavor | ABI | abiVersionCode | Gradle task |
|---|---|---|---|
| `afatFdX86` | x86 | 3 | `assembleAfatFdX86Release` |
| `afatFdX86_64` | x86_64 | 4 | `assembleAfatFdX86_64Release` |
| `afatFdArm32` | armeabi-v7a | 7 | `assembleAfatFdArm32Release` |
| `afatFdArm64` | arm64-v8a | 8 | `assembleAfatFdArm64Release` |

Version codes follow the pattern `MG_VERSION_CODE * 10 + abiVersionCode`.
Outputs land in `TMessagesProj_App/build/outputs/apk/<flavorName>/release/<flavorName>.apk`.

### Release script

`release.sh` builds all release variants, re-signs them with the release keystore, and optionally creates a GitHub release:

```bash
./release.sh              # build only
./release.sh 12.5.1.3     # build + create/replace GitHub release tag
```

Secrets (keystore path and password) are read from a `.env` file in the project root — copy `.env.example` and fill in your values. The `.env` file is gitignored.

### gradle.properties keys (not committed)

```
APP_ID=...
APP_HASH=...
RELEASE_KEY_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_STORE_PASSWORD=...
ADDITIONAL_BUILD_NUMBER=...
```

---

## Key Architecture

### MG feature flags (SharedConfig.java, ~line 238)

All MG-specific settings use the `mg_` prefix in SharedPreferences:

| Field | Default | Pref key |
|---|---|---|
| `disableUnifiedPush` | false | `mg_disableUnifiedPush` |
| `unifiedPushGateway` | `"https://p2p.belloworld.it/"` | `mg_unifiedPushGateway2` |
| `unifiedPushEndpointUrl` | `""` | `mg_unifiedPushEndpointUrl` |
| `pushStringSimple` | `""` | `mg_pushStringSimple` |
| `messageDetailsMenu` | false | `mg_messageDetailsMenu` |
| `disableSecureFlags` | false | `mg_disableSecureFlags` |
| `removeAdsAndProxySponsor` | false | `mg_removeAdsAndProxySponsor` |
| `useRearRoundVideos` | false | `mg_useRearRoundVideos` |
| `hideKeyboardOnScroll` | false | `mg_hideKeyboardOnScroll` |
| `hideAllTab` | false | `mg_hideAllTab` |
| `sendLargePhotos` | false | `mg_sendLargePhotos` |

### Debug menu (ProfileActivity.java)

Long-press on version in Profile → debug items array. MG items at indices 39–42:
- Message Details menu toggle
- UnifiedPush disable toggle
- Secure Flags disable toggle
- Remove Ads & Proxy Sponsor toggle

### UnifiedPush

- **Connector**: `org.unifiedpush.android:connector:3.3.2` (Maven Central)
- **Service**: `UnifiedPushReceiver.java` extends `PushService` (declared as `<service>` in manifest)
- **Configuration UI**: `NotificationsSettingsActivity.java`
- **Gateway URL**: `SharedConfig.unifiedPushGateway`
- **ntfy.sh blocking**: The default ntfy.sh server is blacklisted (blocks the Mercurygram gateway IP) and has very low rate limits. When `onNewEndpoint()` fires, the raw endpoint URL is saved to `SharedConfig.unifiedPushEndpointUrl`. If it contains `ntfy.sh`, `NotificationsSettingsActivity.showNtfyDefaultServerDialog()` is triggered (also checked on first app launch). It auto-switches to the first available non-ntfy distributor, or disables UP entirely if ntfy is the only option.
- **Encryption**: aesgcm (RFC 8188 predecessor, "Draft 4" — the format Telegram uses for PUSH_TYPE_WEB / token type 10)
- **Key management**: App generates its own P-256 keypair + auth secret (`SharedConfig.webPushPrivateKey/PublicKey/AuthSecret`), separate from the connector library's built-in `DefaultKeyManager` keys (which are unused)
- **Decryption**: `WebPushDecryptor.java` handles aesgcm Draft 4 decryption. The connector library's auto-decryption (RFC 8291/aes128gcm with its own keys) fails harmlessly and passes raw bytes through
- **Payload format**: After aesgcm decryption, payload is JSON `{"p":"<base64url-mtproto>"}` (same as FCM). The `"p"` field is extracted and passed to `processRemoteMessage()`, which handles MTProto decryption and builds rich notifications (sender, preview, reply actions)

#### Dual token registration

Two token types are registered simultaneously for each account:

- **token_type=10** (Web Push / `PUSH_TYPE_WEB`): encrypted aesgcm payload, used for regular messages. Token is a JSON object `{endpoint, keys: {p256dh, auth}}` pointing to the gateway's `/aesgcm?e=<UP-endpoint>`.
- **token_type=4** (Simple Push / `PUSH_TYPE_SIMPLE`): plain `PUT version=N` wake-up, used for encrypted (secret) chat notifications where Telegram cannot include payload content. Token is a plain URL: `<gateway>/<url-encoded-UP-endpoint>`. Registered via `PushListenerController.sendSimplePushRegistration()` → `MessagesController.registerSimplePush()`. Token stored in `SharedConfig.pushStringSimple` (`mg_pushStringSimple`).

Both registrations are kept in sync: `registerForPush()` always re-registers type-4 alongside type-10 (before the type-10 early-return guard so the `getDifference()` reconnect path also refreshes it). On distributor unregistration, both tokens are sent `unregisterDevice`.

#### Push notification flow

**Regular message:**
1. Telegram → POST `/aesgcm?e=<UP-endpoint>` (type 10) → gateway forwards encrypted payload → stamps correlation cache
2. Telegram → PUT `/<UP-endpoint>` (type 4) → gateway checks cache, sees POST already arrived → suppresses PUT (responds 200)
3. UP distributor delivers encrypted payload → `onMessage()` → `WebPushDecryptor.decrypt()` → `processRemoteMessage()` → rich notification

**Secret chat message:**
1. Telegram → PUT `/<UP-endpoint>` (type 4) → gateway waits 200 ms → no POST in cache → forwards real `version=N` body as synthetic wake-up
2. UP distributor delivers → `onMessage()` → aesgcm decryption fails (not encrypted) → MTProto fallback: `ConnectionsManager.onInternalPushReceived()` → app reconnects and fetches pending messages

**Registration:**
- `onNewEndpoint()` → registers type-10 JSON token + type-4 URL token → Telegram accepts both independently
- `registerForPush()` (called on every `getDifference()`) → re-registers type-4 if `pushStringSimple` is non-empty
- Migration: on app update, if `pushStringSimple` is empty but `unifiedPushEndpointUrl` is set, `registerForPush()` reconstructs the type-4 URL automatically (no user action needed)

**WakeLock**: static, reference-counted (`sWakeLock`), completion-based release with 30 s hard timeout safety net.

### Monet themes (Android 12+)

- `MonetHelper` at `tw.nekomimi.nekogram.helpers`
- Assets: `monet_light.attheme`, `monet_dark.attheme`
- Registered in `LaunchActivity` onCreate/onDestroy

### Push gateway implementations

Self-hosted WebPush gateway servers in `Gateways/Python/` and `Gateways/Rust/`.

The Rust gateway (`Gateways/Rust/`) is the production implementation; the Python gateway (`Gateways/Python/`) is a reference/alternative.

**POST `/aesgcm?e=<url-encoded-endpoint>`**: receives Telegram's WebPush request (with `Encryption` and `Crypto-Key` headers), embeds them into the body as `aesgcm\nEncryption: ...\nCrypto-Key: ...\n<ciphertext>`, forwards to the UP distributor endpoint, and stamps a correlation cache entry on success.

**PUT `/<url-encoded-endpoint>`**: Simple Push handler. Checks the correlation cache for a recent POST to the same endpoint:
- Found within 2 s → suppresses PUT (POST already delivered the notification)
- Not found after 200 ms wait → forwards the real `version=N` body as a synthetic wake-up so the app falls back to MTProto

The correlation window (200 ms wait, 2 s cache age) prevents duplicate wake-ups for regular messages while ensuring secret chat pushes still reach the app.

**SSRF protection** (Rust): `validate_endpoint()` rejects non-http/https schemes and literal private IPs; `SafeResolver` (custom reqwest DNS resolver) filters resolved IPs at connect time — single resolution, no TOCTOU gap. Redirects disabled.

The public gateway at `https://p2p.belloworld.it/` short-circuits `ntfy.sh` endpoints with an immediate 201 at the nginx level (not in the gateway code). This is a deployment-specific workaround: the gateway runs on OCI infrastructure and its IP is repeatedly blocked by `ntfy.sh` due to connection volume. Self-hosted `ntfy` instances are unaffected.

---

## Known Limitations

- **Passkeys**: Android Passkey support is disabled by setting `BuildVars.SUPPORTS_PASSKEYS = false`. Telegram servers verify the APK signature against the official app, which causes verification to fail for unofficial forks.

---

## Important File Paths

| Purpose | Path |
|---|---|
| MG feature flags | `TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java` |
| UnifiedPush service | `TMessagesProj/src/main/java/org/telegram/messenger/UnifiedPushReceiver.java` |
| WebPush decryptor | `TMessagesProj/src/main/java/it/belloworld/mercurygram/WebPushDecryptor.java` |
| Message Details screen | `TMessagesProj/src/main/java/it/belloworld/mercurygram/ui/MessageDetailsActivity.java` |
| Monet helper | `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/MonetHelper.java` |
| Native build scripts | `TMessagesProj/jni/build_*.sh`, `TMessagesProj/jni/patch_*.sh` |
| Fastlane metadata | `metadata/` |
