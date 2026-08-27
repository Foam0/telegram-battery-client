# Mercurygram 12.10 stable update

- Goal: move the private battery-client changes from Mercurygram 12.9.0.1 to the stable 12.10.0.1 release.
- Base: upstream release `12.10.0.1`, commit `6cdc1b2f3eada34c0c30d6922327c4d13f3ff3c3`.
- Working branch: `codex/mercurygram-12.10-update`.
- Rollback branch: `codex/mercurygram-12.9-update`.
- Worktree created with `git worktree add ../task_mercurygram_12_10 codex/mercurygram-12.10-update`.
- Ported 14 battery-client commits onto the stable base and resolved API, settings, UnifiedPush, screenshot, and native-build conflicts.
- Deliberately omitted the two legacy experimental Firebase-provider commits because Mercurygram 12.10 ships an embedded FCM distributor through UnifiedPush without the Firebase SDK.
- Initialized the 12.10 native submodules at their pinned commits.
- Made the upstream native build scripts portable to macOS Bash 3.2 and BSD `sed`/`install`.
- Built `afatFdArm64Hardened` successfully with JDK 17, Android SDK 35, and NDK 27.2.12479018.
- APK: `TMessagesProj_App/build/outputs/apk/afatFdArm64/hardened/afatFdArm64.apk` (63 MiB).
- APK metadata: package `it.belloworld.mercurygram.beta`, version name `12.10.0.1`, version code `7031018`, min SDK 24, target SDK 35.
- APK SHA-256: `9249ae6040d4d2722c4dbfd6e57bbb681758fb11413f39133e79ca59ed66531b`.
- APK signature verification passed with v2 and v3 schemes; signer certificate SHA-256 is `a08d7dc323ddf71ef3201944397e0d3cce7d40847263e11f328b68bbe19229ab`.
- `scripts/check-sensitive-logs.sh` and `git diff --check` passed.
- Completed: documentation and portability fixes were committed and the branch was pushed to `origin/codex/mercurygram-12.10-update`.

## Auto-updater follow-up

- Restored the Battery Client release channel from the previous public build: `Foam0/telegram-battery-client`.
- Restored the Battery Client signing certificate SHA-256 (`A08D7D…29AB`) and `BatteryTelegramClient-beta-<tag>-<abi>.apk` asset convention.
- Stable releases containing a matching hardened `.beta` asset are eligible even though the installed package name ends in `.beta`.
- The public source branch must omit commit `73e8fb9ea`, which contains the encrypted private configuration backup and its packaging scripts.
