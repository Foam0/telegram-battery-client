# Battery Telegram Client

This fork keeps the Telegram client core from Mercurygram/Telegram Android and adds a narrow battery-oriented layer: per-account notification controls, diagnostics, and an explicit sing-box based VPN/proxy profile.

## Base

- Telegram base: [Mercurygram](https://github.com/Mercurygram/Mercurygram), branch `Mercurygram`, version tag `12.8.1.2.5`, commit `a00d0392d5d14fa89f3aeff13c039045b2612357`.
- Upstream core: [Telegram Android](https://github.com/DrKLO/Telegram), GPL-2.0 family according to Telegram's app source page.
- Push model: Mercurygram's FOSS-compatible UnifiedPush/WebPush path is the
  default. Firebase Messaging is available only behind an explicit experimental
  toggle and falls back to UnifiedPush/WebPush on failure. The app does not add
  aggressive polling.
- VPN/proxy engine: [sing-box](https://github.com/SagerNet/sing-box) / `libbox.aar`, GPL-3.0-or-later.

The implementation does not reimplement MTProto. It leaves authentication, storage, updates, media, and UI flows in the existing Telegram Android codebase.

## Pinned Toolchain

- JDK: 17.
- Gradle wrapper: 8.13.
- Android Gradle Plugin: 8.13.0.
- Kotlin Gradle plugin: 2.1.20.
- Android SDK: API 35, build tools 35.0.0.
- Android min/target SDK: 24/35.
- Android NDK: `27.2.12479018`.
- CMake: `3.22.1`.
- Firebase Messaging: `22.0.0`.
- Native ABI shipped by this build: `arm64-v8a`.
- TDLib/native submodules:
  - `TMessagesProj/jni/td`: `0ae923c493bceb75433de2682ba8ae29cc7bf88d`
  - `TMessagesProj/jni/boringssl`: `56383dabf472100181226cd14249f04c69a0c10b`
  - `TMessagesProj/jni/dav1d`: `32cf02af50f32af108a3b281c452788dccdac648`
  - `TMessagesProj/jni/ffmpeg`: `71fb6132637a2a430375c24afc381fff8b854fe7`
  - `TMessagesProj/jni/libvpx`: `1024874c5919305883187e2953de8fcb4c3d7fa6`
  - `TMessagesProj/jni/whisper`: `f24588a272ae8e23280d9c220536437164e6ed28`
- sing-box config checker: `v1.13.14`.
- Bundled `libbox.aar`: Java surface was inspected with `javap` before the Android glue was written.

## Build

Create an ignored `API_KEYS` file before building. Real Telegram login requires a real Telegram API id and hash. The placeholder values are enough to compile, but not enough to log in.

```properties
APP_ID = 0
APP_HASH = 00000000000000000000000000000000
```

Use JDK 17 and the Android SDK/NDK versions above:

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew -PMG_BUILD_TAG=12.9.0.1 :TMessagesProj_App:assembleAfatFdArm64Debug
```

The debug APK is written to:

```text
TMessagesProj_App/build/outputs/apk/afatFdArm64/debug/afatFdArm64.apk
```

The Gradle `preBuild` task runs `scripts/check_sample_config.sh`, which builds `sing-box v1.13.14` and validates both `config/sample-vless-config.json` and `config/sample-vless-socks-config.json` with `sing-box check`.

## Firebase / Native Push

Native Telegram push delivery is server-side: the client obtains an FCM token
and registers it with Telegram, then Telegram's backend sends notifications
through the Firebase sender it controls. For that reason the checked-in
`battery_firebase.xml` uses Telegram's inherited `tmessages2` sender
(`760348033671`).

Do not replace this file with a personal Firebase project for normal Telegram
push testing. A personal project can produce an FCM token for this APK, but
Telegram's backend will not have that project's sending credentials. Use a
personal Firebase project only together with a custom server-side push bridge.

For this fork, Firebase Cloud Messaging is selected automatically when no
UnifiedPush distributor is installed, or when the Firebase push option is
enabled. UnifiedPush remains a fallback if FCM token generation fails and a
distributor is available. If Firebase is hard-blocked and no UnifiedPush
distributor is installed, the app enables the built-in keep-alive/background
connection fallback so message notifications can still arrive without a separate
push app.

## Battery Changes

- Uses push-first delivery. Firebase Cloud Messaging is the native path when no
  UnifiedPush distributor is installed or the Firebase option is enabled. If
  Firebase fails, the app backs off for 24 hours and uses UnifiedPush/WebPush
  when available; without UnifiedPush it enables the built-in keep-alive and
  background connection fallback.
- Does not add a permanent foreground service for idle operation.
- Starts embedded VPN or local SOCKS proxy mode only after explicit user action and fully stops the selected service on disconnect.
- Keeps reconnect/backoff behavior in the existing Telegram networking layer.
- Avoids wake locks beyond existing Telegram flows.
- Raises the local account ceiling from 8 to 32. This removes the practical Mercurygram local cap for normal use, but it is still a fixed technical ceiling because upstream Telegram Android stores many account singletons in static arrays.
- Adds per-account notification rules:
  - enabled/disabled
  - sound enabled/disabled
  - vibration enabled/disabled
- Stops notification creation early for accounts with notifications disabled.
- Adds load and diagnostics screens in Settings -> Mercurygram:
  - own process CPU time
  - battery level/status
  - foreground app from UsageStats when permission is granted
  - active account/client count
  - connection state
  - last update time
  - push/fallback mode
  - per-account database size

The main expected battery win is from push-first behavior, avoiding extra background loops, and closing VPN resources cleanly. `gc_percent`, `GOMAXPROCS`, and narrow DNS sniffing are smaller tuning effects.

### Push Limitations

Direct Telegram FCM is controlled by Telegram's Firebase project and can still be
blocked for unofficial package/signature combinations. If token generation fails,
the app logs the FCM exception and falls back to UnifiedPush when possible. If a
token is generated but Telegram messages do not arrive while the process is
stopped, Telegram's backend is likely refusing or ignoring this fork's native
FCM registration.

The Firebase code does not log push tokens or payload contents. Analytics
collection and Firebase Messaging auto-init are disabled in the manifest; token
requests happen when FCM is selected as the active push provider, and hard
failures switch to the UnifiedPush fallback.

## Ads, Promos, and Tracking

- Mercurygram's de-Googled base removes/stubs most Google Play Services, Google
  Maps, SafetyNet/Play Integrity, and similar proprietary integrations. This
  battery-client build intentionally adds Firebase Messaging only as an
  explicit opt-in push experiment and disables Firebase Analytics collection.
- The existing Mercurygram `removeAdsAndProxySponsor` path is enabled by default in this fork, preventing local sponsored-message/proxy-sponsor fetch and display paths from running.
- Premium/business/gift upsell UI is hidden by default for new accounts.
- This fork does not bypass Telegram server-enforced limits, paid features, or account policy. If a limit or ad decision is enforced by Telegram servers, it is documented as out of scope rather than bypassed.

## VPN / Proxy Profile

The embedded profile is intentionally narrow:

- Modes: off, system network/direct, local SOCKS5 proxy, embedded VPN/TUN profile.
- Minimum supported import: `vless://`.
- Engine: sing-box/libbox, not Xray.
- Local SOCKS5 mode starts sing-box on `127.0.0.1` with generated username/password authentication and points Telegram's native proxy setting at that loopback port. It does not create Android VPN permission prompts, a TUN interface, or a system-wide proxy.
- Local SOCKS5 mode is fail-closed: before per-account native networking starts,
  and whenever the in-process VLESS core is paused in the background, Telegram
  is pinned to the non-listening loopback endpoint `127.0.0.1:1`. It switches to
  the live authenticated loopback port only after sing-box is ready, so it never
  silently falls through to the phone's direct IP while this mode is selected.
- Android system-wide proxy changes are not implemented because ordinary apps cannot change global proxy settings without privileged/device-owner rights.
- TUN inbound uses `stack: "system"`.
- Mux is not enabled.
- Runtime battery settings:
  - `GOMAXPROCS=2`
  - `experimental.debug.gc_percent=200`
  - sniff only `udp/53`
- DNS uses route actions `sniff` and `hijack-dns`, no legacy DNS outbound.
- `route.default_domain_resolver` is configured.
- The default network monitor requests only non-VPN networks through `NET_CAPABILITY_NOT_VPN`.
- `PlatformInterface.getInterfaces()` strips IPv6 zone suffixes such as `%wlan0`.
- Default-interface cache keys include interface index; index `0` is never cached.
- On disconnect, the libbox service and detached Android TUN file descriptors are closed so stale `tun0` instances are not left behind.

The VLESS profile list is stored in the atomic, Android Keystore-encrypted
app-private file:

```text
/data/user/0/it.belloworld.mercurygram.beta/no_backup/battery_vpn/vless_profiles.enc
```

Existing encrypted SharedPreferences profiles migrate into that file during app
startup. A normal signed APK replacement preserves the file. Android removes it
only when the user uninstalls the app or clears its app data. Generated runtime
configs remain in app-private storage, and user profile files are never committed
or embedded in release APKs.

## Security Rules

- Telegram session, database, and auth files stay in app-private storage.
- Do not commit `API_KEYS`, `local.properties`, keystores, tokens, session files, database dumps, logs, or user VPN profiles.

## Public repository security

- VLESS profiles are supplied by the user at runtime. No working profile, UUID,
  server address, Reality key, or short ID is bundled in the source or APK.
- The JSON files under `config/sample-vless-*.json` use the non-routable
  `192.0.2.0/24` documentation range and deliberately unusable credentials.
- Do not commit `API_KEYS`, `local.properties`, keystores, encrypted secret
  backups, tokens, session files, database dumps, logs, or user VPN profiles.
- `scripts/check-public-source.sh` enforces these rules locally and in GitHub
  Actions.
- Do not log auth keys, phone numbers, SMS codes, session file paths, database
  dumps, or message contents.

## GitHub Actions build

The `Build signed release APK` workflow builds the optimized, non-debuggable
arm64 release APK on pushes to `main` and on manual runs. The signing job uses
the protected `release` environment and cannot start until a required reviewer
approves it. Pull requests run the separate security checks only and never
receive signing credentials.

`APP_ID`, `APP_HASH`, `RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` are stored as environment
secrets. They are exposed only to the single shell step that builds the APK;
temporary `API_KEYS` and keystore files are deleted before artifact upload.
External Actions are pinned to immutable commit SHAs. The resulting artifact is
named `battery-client-arm64-release-<version>` and is retained for 30 days.
VLESS profiles are never embedded as defaults.

## License

Mercurygram and Telegram Android carry GPL-family licensing, while sing-box/libbox is GPL-3.0-or-later. Distribution of this combined APK should be treated as GPL-3.0-or-later compatible and must retain upstream notices. If a distributor treats the selected Telegram base as GPL-2.0-only, do not distribute a single bundled APK with libbox; split the VPN engine into a compatible external component instead.
