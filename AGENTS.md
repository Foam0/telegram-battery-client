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

- JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`)
- Android SDK with NDK 23.2.8568313 and build-tools 36.0.0
- `ninja-build`, `meson`, `pip` (for native deps)
- A `gradle.properties` file with `APP_ID`, `APP_HASH`, and signing config (see below)
- An `API_KEYS` file in the project root with `APP_ID` and `APP_HASH`

### Building the APK

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew assembleAfatRelease   # or assembleAfatDebug
```

The `buildNativeLibs` Gradle task automatically initializes submodules and builds native libraries (libvpx, dav1d, ffmpeg, BoringSSL, tde2e) from source on the first run. Subsequent runs skip it entirely (sentinel: `jni/tde2e/build/arm64-v8a/libtde2e.a`). To force a rebuild, delete the sentinel and re-run.

> **Note**: `sourceSets.main.jniLibs.srcDirs = ['./jni/']` in both `TMessagesProj/build.gradle` and `TMessagesProj_App/build.gradle` causes `merge*JniLibFolders` tasks to snapshot all of `jni/` as inputs. Running native builds in parallel at that point causes `NoSuchFileException` on intermediate `.o.tmp` files. This is prevented by two `afterEvaluate` blocks — one in each build.gradle — that add explicit `dependsOn buildNativeLibs` to every `merge*JniLibFolders` task, ensuring the native build fully completes before any merge task begins scanning `jni/`.

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
| `unifiedPushGateway` | `"https://p2p.belloworld.it/"` | `mg_unifiedPushGateway` |
| `messageDetailsMenu` | false | `mg_messageDetailsMenu` |
| `disableSecureFlags` | false | `mg_disableSecureFlags` |
| `useRearRoundVideos` | false | `mg_useRearRoundVideos` |
| `hideKeyboardOnScroll` | false | `mg_hideKeyboardOnScroll` |
| `sendLargePhotos` | false | `mg_sendLargePhotos` |

### Debug menu (ProfileActivity.java)

Long-press on version in Profile → debug items array. MG items at indices 39–41:
- Message Details menu toggle
- UnifiedPush disable toggle
- Secure Flags disable toggle

### UnifiedPush

- Receiver: `UnifiedPushReceiver.java`
- Configuration UI: `NotificationsSettingsActivity.java`
- Gateway URL: `SharedConfig.unifiedPushGateway`

### Monet themes (Android 12+)

- `MonetHelper` at `tw.nekomimi.nekogram.helpers`
- Assets: `monet_light.attheme`, `monet_dark.attheme`
- Registered in `LaunchActivity` onCreate/onDestroy

### Push gateway implementations

See `Gateways/Python/` and `Gateways/Rust/` for self-hosted WebPush gateway servers.

---

## Important File Paths

| Purpose | Path |
|---|---|
| MG feature flags | `TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java` |
| UnifiedPush receiver | `TMessagesProj/src/main/java/org/telegram/messenger/UnifiedPushReceiver.java` |
| Message Details screen | `TMessagesProj/src/main/java/it/belloworld/mercurygram/ui/MessageDetailsActivity.java` |
| Monet helper | `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/MonetHelper.java` |
| Native build scripts | `TMessagesProj/jni/build_*.sh`, `TMessagesProj/jni/patch_*.sh` |
| Fastlane metadata | `metadata/` |
