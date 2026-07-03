# Battery Telegram Client

This fork keeps the Telegram client core from Mercurygram/Telegram Android and adds a narrow battery-oriented layer: per-account notification controls, diagnostics, and an explicit sing-box based VPN/proxy profile.

## Base

- Telegram base: [Mercurygram](https://github.com/Mercurygram/Mercurygram), branch `Mercurygram`, version tag `12.8.1.2.5`, commit `a00d0392d5d14fa89f3aeff13c039045b2612357`.
- Upstream core: [Telegram Android](https://github.com/DrKLO/Telegram), GPL-2.0 family according to Telegram's app source page.
- Push model: Mercurygram's FOSS-compatible UnifiedPush/WebPush path is retained. The app does not add aggressive polling.
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
./gradlew -PMG_BUILD_TAG=12.8.1.2.5 :TMessagesProj_App:assembleAfatFdArm64Debug
```

The debug APK is written to:

```text
TMessagesProj_App/build/outputs/apk/afatFdArm64/debug/afatFdArm64.apk
```

The Gradle `preBuild` task runs `scripts/check_sample_config.sh`, which builds `sing-box v1.13.14` and validates both `config/sample-vless-config.json` and `config/sample-vless-socks-config.json` with `sing-box check`.

## Battery Changes

- Uses the existing Mercurygram UnifiedPush/WebPush path for push-first delivery.
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

## Ads, Promos, and Tracking

- Mercurygram's de-Googled build removes/stubs Google Play Services, Firebase, Google Maps, SafetyNet/Play Integrity, and similar proprietary integrations from the default app.
- The existing Mercurygram `removeAdsAndProxySponsor` path is enabled by default in this fork, preventing local sponsored-message/proxy-sponsor fetch and display paths from running.
- Premium/business/gift upsell UI is hidden by default for new accounts.
- This fork does not bypass Telegram server-enforced limits, paid features, or account policy. If a limit or ad decision is enforced by Telegram servers, it is documented as out of scope rather than bypassed.

## VPN / Proxy Profile

The embedded profile is intentionally narrow:

- Modes: off, system network/direct, local SOCKS5 proxy, embedded VPN/TUN profile.
- Minimum supported import: `vless://`.
- Engine: sing-box/libbox, not Xray.
- Local SOCKS5 mode starts sing-box on `127.0.0.1` with generated username/password authentication and points Telegram's native proxy setting at that loopback port. It does not create Android VPN permission prompts, a TUN interface, or a system-wide proxy.
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

Profiles and generated configs are stored in app-private storage. User profile files are not committed.

## Security Rules

- Telegram session, database, and auth files stay in app-private storage.
- Do not commit `API_KEYS`, `local.properties`, keystores, tokens, session files, database dumps, logs, or user VPN profiles.
- Do not log auth keys, phone numbers, SMS codes, session file paths, database dumps, or message contents.

## License

Mercurygram and Telegram Android carry GPL-family licensing, while sing-box/libbox is GPL-3.0-or-later. Distribution of this combined APK should be treated as GPL-3.0-or-later compatible and must retain upstream notices. If a distributor treats the selected Telegram base as GPL-2.0-only, do not distribute a single bundled APK with libbox; split the VPN engine into a compatible external component instead.
