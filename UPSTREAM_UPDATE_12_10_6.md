# Battery Client 12.10.6 update

The client now uses stable Mercurygram 12.10.6.1 (786811f8e907c6e1b4f8309813588670ee7c1ec6), based on Telegram 12.10.6. The Battery Client changes are carried forward from the published 12.10.0.4 hotfix (3eb8ace4b).

## Notification compatibility

The native Firebase providers, message listener, SDK version (25.1.2), and all three Firebase resource overlays are unchanged from 12.10.0.4. The application id, release certificate, preferences keys and token migration marker remain unchanged. There is no forced token rotation for this update.

The new upstream UnifiedPush retry/watchdog paths are guarded while native Firebase is active. Late UnifiedPush endpoint/unregistration callbacks cannot replace or clear the primary Firebase registration. When UnifiedPush is primary, its upstream retry and endpoint recovery still work. The watchdog does not add periodic wake-ups while native Firebase is active or no UnifiedPush distributor is available.

The build verifies the actual Firebase resources and manifest services in the packaged APK before publishing. Each expected string must match its preserved hardened source-set value; just finding the project name somewhere in the APK is insufficient.

## Preserved Battery Client features

All 38 files originally added by Battery Client are retained. The port keeps per-account notification controls, the 32-account Java/native limit, VLESS and encrypted profile storage, app-only proxy lifecycle and fail-closed startup, screenshot support, diagnostics, media metadata/phone URI actions, installer support, and the signed updater for Foam0/telegram-battery-client. VLESS callers now use upstream's ProxySettings representation while retaining SOCKS username/password.

## Validation and limits

- Source push contract and sensitive-log checks.
- Byte comparison of native Firebase providers, listener and resource overlays against 12.10.0.4.
- Firebase APK verifier run against the previous signed APK.
- Java compilation and full signed release build are required before publishing.
- No connected phone was available for an in-place upgrade/push-delivery test. Source and APK checks cannot establish end-to-end delivery with a locked screen; that remains a device validation step.
