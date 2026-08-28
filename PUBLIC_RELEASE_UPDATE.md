# Battery Client 12.10 public release

- Base: stable Mercurygram `12.10.0.1`, commit `6cdc1b2f3eada34c0c30d6922327c4d13f3ff3c3`.
- Public branch: `codex/mercurygram-12.10-autoupdater`.
- Auto-update source: `Foam0/telegram-battery-client` GitHub Releases.
- Release asset: `BatteryTelegramClient-beta-<tag>-arm64-v8a.apk`.
- Application id: `it.belloworld.mercurygram.beta`.
- Expected signing certificate SHA-256: `A08D7DC323DDF71EF3201944397E0D3CCE7D40847263E11F328B68BBE19229AB`.
- The private configuration backup commit and all of its files are excluded from this branch's history.
- The release workflow builds only the non-debuggable hardened arm64 package and verifies its metadata and signature before publication.
- Notification hotfix `12.10.0.2` restores the native Telegram Firebase provider and Firebase resources from the working 12.9 release while retaining UnifiedPush as fallback.
- `scripts/check-push-contract.sh` and the release workflow now fail if the Firebase provider, sender config, manifest service, or automatic fallback disappears during a future upstream update.
- Public hotfix release: `https://github.com/Foam0/telegram-battery-client/releases/tag/12.10.0.2`.
- APK SHA-256: `02c6ac7c90731cf649a425b8836ab420d1dc2e57df18570a8077ef093a10f002`; size: 66,062,286 bytes.
- Anonymous GitHub API verification selects `12.10.0.2` for installed `12.10.0.1` and reports it as the latest stable release.
