# Mamori — Compatibility

Status: Phase 5. Every claim below is either read from the source, checked against the
Android platform documentation, or explicitly marked **unverified**. Nothing here is a
promise that has not been tested.

## 1. Build-level facts

| Property | Value | Consequence |
| --- | --- | --- |
| `applicationId` | `com.reed.fcmguard` | upgrades in place over upstream FCMGuard |
| `minSdk` | 21 (Android 5.0) | legacy locale/theme branches must keep working |
| `targetSdk` | 22 (Android 5.1) | load-bearing for the vendor `Settings.System` write; see §3 |
| `compileSdk` | 35 | compile-time only |
| Permissions | `WRITE_SETTINGS`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` | no root, shell, Shizuku, ADB, overlay or device-admin |
| Components | `MainActivity` (exported), `GuardService` (not exported), `BootReceiver` (exported) | unchanged from upstream |
| Locales | 10 inherited + `vi` | English fallback for missing keys in the nine inherited locales |

`APPLICATION_ID` and `targetSdk` must not change without replacing the write mechanism;
`docs/IMPLEMENTATION_PLAN.md` §1 lists them as invariants and
`tools/check_layout_profiles.py` guards them.

## 2. Android version support

| Android | State | Notes |
| --- | --- | --- |
| 5.0 – 5.1 (21–22) | Expected to work | matches `targetSdk`; no runtime permission model |
| 6.0 – 12L (23–32) | Expected to work | `WRITE_SETTINGS` is granted through the system settings screen; legacy locale path until API 32 |
| 13 (33) | Expected to work | app language via `LocaleManager`; notification permission exists and is checked |
| 14 (34) and newer | **Installation blocked by the platform** | see §3.1 |

### 2.1 The `targetSdk 22` installation restriction

Android 14 introduced a minimum target API for installation: APKs whose `targetSdkVersion`
is below 23 are rejected by the package installer on Android 14 and newer. Because Mamori
intentionally keeps `targetSdk 22`, a **new** installation on Android 14+ (which includes
HyperOS releases built on Android 14, 15 and 16) will fail unless the restriction is
bypassed.

- **Bypass, user-side only:** `adb install --bypass-low-target-sdk-block <apk>`. Mamori does
  not and cannot perform this itself.
- **Already installed:** whether an in-place upgrade is also blocked is **unverified**.
  Treat upgrade-on-Android-14+ as a release-blocking question and test it before shipping.
- **Why not raise `targetSdk`:** the vendor whitelist write relies on the legacy code path.
  Raising it without a replacement write mechanism silently removes the core feature.
  This is the project's central trade-off, inherited from upstream.

If this restriction makes the product unusable on current HyperOS devices, the correct fix
is a new verified write mechanism at `targetSdk ≥ 23` — a product decision, not a build tweak.

## 3. HyperOS / MIUI specifics

| Area | Behaviour | Status |
| --- | --- | --- |
| Whitelist key | `MILLET_NO_RESTRICT_APP` in `Settings.System`, comma-separated package list | works where the key exists; configurable in Advanced |
| Empty key | reading returns `null`/empty; Mamori refuses to invent packages without confirmation | implemented |
| Autostart AppOps | vendor ops `10008` / `10053` read via reflection | best-effort; `UNKNOWN` when the ROM blocks it |
| Autostart settings | `miui.intent.action.OP_AUTO_START` and `com.miui.securitycenter` editor intents, with fallbacks | implemented; unverified on HyperOS 3 |
| FCM diagnostics | Google Play services `GcmDiagnostics`-style activities | best-effort |
| Battery saver | HyperOS may stop the service; a 30-minute non-waking fallback and an on-resume restart exist | implemented |
| Non-Xiaomi ROM | the key is absent and the vendor AppOps return `UNKNOWN` | no writes, honest status |

Mamori never treats `UNKNOWN` as `DISABLED`, and never writes a key it did not read.

## 4. Localization

- Vietnamese is complete (`values-vi`, 254 keys) and guarded by
  `tools/check_layout_profiles.py`.
- Other locales: the ten inherited translations keep working; keys added in Phases 3–4 fall
  back to English until translated. That is visible English, not a broken layout.
- On Android 13+, `LocaleManager` only accepts locales declared in `locales_config.xml`;
  `vi` is declared and the picker list matches the config exactly.

### 4.1 System language and per-app language

| Operation | API / mechanism | Status |
| --- | --- | --- |
| Read system locales | `LocaleManager.getSystemLocales()` (33+), `LocaleList.getDefault()` below | implemented |
| Write system locales | `Settings.System` key `system_locales` with `WRITE_SETTINGS`, verified against `getSystemLocales()` | implemented for API 33+; **unverified on a device** |
| Restore original system locales | SHA-256 snapshot captured before the first write, written back the same way | implemented for API 33+; **unverified on a device** |
| Mamori's own app locale | `LocaleManager.setApplicationLocales` (33+), legacy configuration below | implemented |
| Read another app's locale | `LocaleManager.getApplicationLocales(packageName)` — API 35, `READ_APP_SPECIFIC_LOCALES` (signature) | attempted, usually denied; reported as unknown |
| Write another app's locale | no public API; no settings store the framework honours | **not implemented, by platform restriction** |

Two limits are inherent to a non-privileged app and are shown in the UI rather than worked
around:

1. **There is no public setter for the system locale.** Mamori writes the platform's own
   store because that is the only real mechanism available to it; a hidden
   `setSystemLocales` call would require the system signature and is not attempted. Some
   builds apply a stored system locale only at boot — those are reported as "saved, applies
   after a restart", never as applied.
2. **Another app's language cannot be set.** Android exposes only the per-app own-locale
   API, and `LocaleManager.setApplicationLocales(String, LocaleList)` is a system API. The
   screen shows that app's real (readable or unknown) state and opens its settings instead.

## 5. Verified vs unverified

| Claim | Evidence |
| --- | --- |
| Builds debug and release APKs | `:app:assembleDebug`, `:app:assembleRelease` |
| APK identity, permissions, components | `aapt2 dump badging` / `xmltree` |
| Vietnamese resources packaged | `aapt2 dump resources`, instrumentation test (compiled) |
| Write pipeline semantics | 103 JVM tests with a fake gateway |
| Locale normalization, catalog, snapshot integrity, capability policy, language-manager ViewModel | JVM tests (`LocaleCodeTest`, `LocaleCatalogTest`, `SystemLocaleSnapshotTest`, `LocaleCapabilityPolicyTest`, `LanguageManagerViewModelTest`) |
| System/per-app language writes on a device | **unverified** — no device in this environment |
| UI behaviour, insets, dynamic color | **unverified** — no device in this environment |
| Install/upgrade on Android 14+ | **unverified** — see §2.1 |
| Restore correctness on a real device | **unverified** — requires a real system setting |
