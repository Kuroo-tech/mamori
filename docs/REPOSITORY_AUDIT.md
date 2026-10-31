# Repository Audit — Mamori (fork of FCMGuard-HyperOS)

| Field | Value |
| --- | --- |
| Audit date | 2026-10-31 |
| Upstream | `ReedGAOOO/FCMGuard-HyperOS` |
| Fork remote | `https://github.com/Kuroo-tech/mamori.git` |
| Audited commit | `b6eb75c3213da24369efbe7e02f13e96dc303a69` ("docs: add WhatsApp battery optimization guidance") |
| Baseline version | `versionCode 30`, `versionName 1.5.9` |
| Application ID | `com.reed.fcmguard` |
| Language | Java only (no Kotlin, no Kotlin Gradle plugin) |
| UI toolkit | Android View system + custom `ViewGroup`s; no Compose, no AndroidX |
| Status | **Baseline builds successfully** (see §11) |

---

## 0. Critical finding: the specification's premise does not match the repository

This must be resolved before any architectural work, because it determines what Mamori *is*.

**The specification describes a Vietnamese system-localization tool ("Việt hóa"): a localization engine that modifies the resources of system and third-party applications, with translation datasets, resource fingerprints, per-app translation coverage, and rollback of modified APK resources.**

**The upstream project is not that.** FCMGuard-HyperOS is a single-purpose, deliberately low-privilege *FCM push-notification watchdog* for China-ROM HyperOS 3. It:

- writes exactly one `Settings.System` key (`MILLET_NO_RESTRICT_APP`) to keep `com.google.android.gms` in Xiaomi's "no restrict" background list,
- optionally sends two documented FCM/MCS heartbeat broadcasts,
- reads (never writes) two vendor AppOps values to display Autostart status,
- opens HyperOS settings screens via deep links.

It contains **no localization engine, no resource-modification mechanism, no backup system, no rollback system, no privileged shell, no root path, no Shizuku path, and no ADB path.** There is no code that reads, decodes, or rewrites APK resources.

Consequences for the specification as written:

1. **Spec §6 (Localization Engine), §7 (Backup/Rollback of translated resources), and §11 (translating *other* apps) have no upstream counterpart.** They would be *newly built*, not modernized. A real Việt hóa engine needs to decode and rebuild APK resources of system apps, re-sign or bypass signatures, and install the result — which requires root, Shizuku, or a Magisk-style module. That is a different privilege class from "Modify system settings", and introducing it is explicitly gated by spec §8 ("Do not introduce a new backend without verifying that it is necessary and compatible").
2. **Most of the specification's UI screens (System Localization, App Localization, Operation History, Backup management) have no data source in this codebase.** Building them with placeholder data would violate spec §16.2 (no fake statuses, no non-functional buttons, no fake backup/rollback).
3. **Sections that *do* map cleanly and are genuinely valuable here:** Material 3 Expressive migration, Dynamic Color, Kotlin conversion, layered architecture, execution abstraction, operation state machine, backup-before-write and verified rollback of the whitelist value, operation history backed by real records, complete Vietnamese localization of Mamori's own UI, and accessibility/responsive work.

The remainder of this audit documents the repository exactly as it exists, and §13 records the two viable scopes so the decision is explicit rather than assumed.

---

## 1. Method and environment

- Full source read: all 12 Java classes (1,993 lines), both layouts, all resource files, Gradle files, manifest, CI workflow, `tools/check_layout_profiles.py`, `README.md`.
- Baseline build executed locally (§11) with a project-local toolchain, because the host has no Android SDK on `PATH` and no Gradle installation.
- Static analysis only. **No device was connected during this audit** (`adb devices` → empty), so runtime behavior is derived from source reading, not observed on hardware.

Everything in §10 and §14 that could not be verified is marked **UNVERIFIED**.

---

## 2. Current architecture

### 2.1 Build and module layout

Single Gradle module `:app`, Groovy DSL (`build.gradle`), AGP `8.6.1`, no Gradle wrapper committed, no version catalog, no Kotlin plugin, no third-party dependencies at all.

| Setting | Value | Note |
| --- | --- | --- |
| `namespace` / `applicationId` | `com.reed.fcmguard` | |
| `compileSdk` | 35 | modern toolchain |
| `minSdk` | 21 | |
| `targetSdk` | **22** | deliberate; see §9.1 — this is load-bearing |
| Java source/target | 17 | |
| `android.useAndroidX` | **false** | must become `true` for Compose/AndroidX |
| `android.nonTransitiveRClass` | true | |
| `minifyEnabled` | false (release) | no R8 config |

There is **no dependency block at all** — the app is pure framework API plus reflection.

### 2.2 Source inventory

| File | Lines | Role |
| --- | --- | --- |
| `MainActivity.java` | 826 | Entire UI: status rendering, all switches, language picker, appearance picker, FCM app list rendering, insets, edge-to-edge |
| `SettingsGuard.java` | 174 | The domain core: read/repair/normalize the whitelist setting, prefs, `Result` type |
| `GuardService.java` | 187 | Foreground/background service, `ContentObserver`, 30-min in-process fallback, notification |
| `StickyDashboardLayout.java` | 198 | Custom `FrameLayout`: scroll-linked collapse of the status card |
| `CollapsingStatusCard.java` | 164 | Custom `LinearLayout`: custom fill/stroke draw, dynamic outline, child draw order, clipping mask |
| `FcmAppScanner.java` | 101 | `PackageManager` scan for FCM/GCM components |
| `AutostartStatusReader.java` | 77 | Reflective read-only vendor AppOps probe (10008 / 10053) |
| `LocaleHelper.java` | 106 | Per-app locale (API 33+ `LocaleManager`, legacy `Configuration` override) |
| `ThemeHelper.java` | 54 | System/Light/Dark `Configuration` override |
| `HyperOsSettings.java` | 59 | MIUI/HyperOS deep links with fallbacks |
| `FcmReconnect.java` | 35 | Best-effort heartbeat broadcasts |
| `BootReceiver.java` | 22 | Restarts protection after boot/update |

Resources: 2 layouts (one root + one `<include>`), 12 shape drawables, 8 color files (`values`, `values-night`), 3 dimen profiles (`values`, `values-w420dp`), 10 complete locales × 2 string files, 1 icon vector.

### 2.3 Layering as it exists

There is no architectural layering. The de-facto structure is:

```
MainActivity ──► static helpers (SettingsGuard, FcmReconnect, HyperOsSettings,
                 FcmAppScanner, AutostartStatusReader, LocaleHelper, ThemeHelper)
GuardService ──► SettingsGuard, FcmReconnect
BootReceiver ──► SettingsGuard, GuardService
```

Observation, not a defect list:

- **`SettingsGuard` is effectively the repository + domain layer** (it owns persistence, normalization, and the privileged write), but it is a static utility class with no interface, so it cannot be substituted in tests.
- **`MainActivity` is the ViewModel, the view, and the controller simultaneously** and mutates state by direct `findViewById` access; there is no observable state, no `StateFlow`, no immutable UI model.
- **There is no logging at all.** No `Log.*` calls, no diagnostics surface. Failures are silent by construction (see §10.4).
- The custom view pair (`StickyDashboardLayout` + `CollapsingStatusCard`) implements a bespoke scroll-driven collapse with a quintic smootherstep and a clipping mask. It is carefully written and documented, and it is the most expensive part of any Compose migration (see §13.3).

---

## 3. Feature inventory

Behavior as implemented. "Verification" states how the claim was established.

| # | Feature | Implementation | Verified by |
| --- | --- | --- | --- |
| F1 | Automatic protection toggle | Writes `enabled` pref; starts/stops `GuardService`; refuses to enable without `WRITE_SETTINGS` and immediately reverts the switch | Source read (`MainActivity.java:294-330`) |
| F2 | Whitelist repair ("Repair now") | `SettingsGuard.repair()`: reads current value, appends required item if missing, single `putString`, records `last_good_*` | Source read |
| F3 | Event-driven repair | `GuardService` `ContentObserver` on the configured key URI, 400 ms debounce | Source read (`GuardService.java:78-89`) |
| F4 | 30-minute fallback repair | `Handler.postDelayed` **in-process** loop; no `AlarmManager`, no `WakeLock`; dies with the process | Source read (`GuardService.java:29-35`) |
| F5 | Foreground mode | `IMPORTANCE_LOW`, silent channel `fcm_guard_persistent_v2`, `setOngoing(true)` | Source read (`GuardService.java:105-130`) |
| F6 | Quiet background mode | Same service without `startForeground`; notification cancelled | Source read (`GuardService.java:113-120`) |
| F7 | FCM reconnect kick | Broadcast `GTALK_HEARTBEAT` + `MCS_HEARTBEAT` to `com.google.android.gms` and `com.google.android.gsf`, only after a real repair or explicit user action | Source read (`FcmReconnect.java`) |
| F8 | Status dashboard | Four status lines (permission / enabled / item present / background mode) + whitelist value with colored chips | Source read |
| F9 | FCM app scan | `queryIntentServices(MESSAGING_EVENT)` + `queryBroadcastReceivers(c2dm.RECEIVE)`; filters disabled apps and apps without a launcher intent; excludes GMS/Vending/self | Source read (`FcmAppScanner.java:44-95`) |
| F10 | Autostart status | Reflective `checkOpNoThrow` for vendor ops 10008/10053 → ENABLED / PARTIAL / DISABLED / UNKNOWN; all-UNKNOWN hides the list | Source read (`AutostartStatusReader.java`) |
| F11 | Open Autostart manager | `miui.intent.action.OP_AUTO_START` → explicit `AutoStartManagementActivity` → generic application settings | Source read |
| F12 | Open FCM diagnostics | `GcmDiagnostics` → `GTalkServiceDiagnostics` → heuristic scan of GMS activities | Source read |
| F13 | Advanced config | Editable key name and required item, defaulting to `MILLET_NO_RESTRICT_APP` / `com.google.android.gms` | Source read |
| F14 | Appearance | System/Light/Dark via `Configuration` override + `recreate()` | Source read |
| F15 | Language | API 33+: system per-app locale screen. Legacy: `AlertDialog` + `Configuration` override. 10 locales. Animated multilingual "Language" button label | Source read |
| F16 | Notification permission recovery | Detects channel/permission blockage, follows up after returning to the foreground, deep-links to app notification settings | Source read (`MainActivity.java:74-90`, `GuardService.canShowPersistentNotification`) |
| F17 | Boot/update survival | `BootReceiver` restarts the service when a boot or self-update broadcast arrives and protection is enabled | Source read |
| F18 | Edge-to-edge + cutout insets | `setSystemUiVisibility` (deprecated), manual inset application in the activity and in `StickyDashboardLayout` | Source read |

Not present anywhere: backup, restore, rollback, operation history, logs, scheduled backup, export/import, root, Shizuku, shell, ADB, translation of other apps.

---

## 4. System-level operations and execution flow

There are exactly **four** system-touching mechanisms. None of them is a shell.

### 4.1 The privileged write (the core of the app)

```
MainActivity/GuardService
  └─► SettingsGuard.repair(context)                    [synchronized]
        ├─ Settings.System.canWrite(context)?           → false ⇒ Result(success=false)
        ├─ Settings.System.getString(key)               → current raw value
        ├─ hasRequiredItem(current)?                    → true ⇒ no write, changed=false
        ├─ parse(current) into LinkedHashSet            (order-preserving, deduplicating)
        │    └─ if empty: seed from prefs["last_good_<key>"]
        │         └─ if still empty: seed {com.tencent.mm, com.android.vending}   ← §10.2
        ├─ add required item, join with ","
        ├─ Settings.System.putString(key, repaired)     → boolean
        └─ on success: persist prefs["last_good_<key>"]
```

Callers: the protection switch, "Repair now", the service's `ContentObserver` (400 ms debounce), and the 30-minute fallback. `FcmReconnect.kick()` fires only when `changed == true`.

Why `targetSdk 22` makes this possible: writing a vendor-private `Settings.System` key that is not a `Settings.System` constant requires the legacy code path gated by target SDK. This is the documented upstream design and the reason the project refuses root/Shizuku. **Any change to `targetSdk` is a functional regression, not a modernization.**

### 4.2 Observation

`ContentResolver.registerContentObserver(Settings.System.getUriFor(configuredKey), false, observer)` with `notifyForDescendants=false`. Re-registered on every `onStartCommand`. Failure to register is swallowed (`GuardService.java:75`).

### 4.3 Read-only vendor probe

`AppOpsManager.checkOpNoThrow(int,int,String)` resolved by reflection against ops `10008` and `10053`. Strictly read-only; `UNKNOWN` is a first-class value and is never coerced to `DISABLED`.

### 4.4 Intent-based interactions

| Target | Action | Direction |
| --- | --- | --- |
| GMS / GSF | `GTALK_HEARTBEAT`, `MCS_HEARTBEAT` | send (best-effort, `setPackage`) |
| `com.miui.securitycenter` | `OP_AUTO_START`, `AutoStartManagementActivity`, `PermissionsEditorActivity` | explicit start, with `resolveActivity` guard |
| GMS | `GcmDiagnostics`, `GTalkServiceDiagnostics` | explicit start |
| System settings | `ACTION_MANAGE_WRITE_SETTINGS`, `ACTION_APP_NOTIFICATION_SETTINGS`, `ACTION_APP_LOCALE_SETTINGS`, `ACTION_APPLICATION_DETAILS_SETTINGS` | start |

---

## 5. Permissions and privilege requirements

```xml
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- `WRITE_SETTINGS` is user-grantable (`ACTION_MANAGE_WRITE_SETTINGS`); checked with `Settings.System.canWrite()` before every write.
- **No** `QUERY_ALL_PACKAGES`; package visibility is narrowed by `<queries>` to the FCM/GCM intents, `com.google.android.gms`, and `com.miui.securitycenter`. This means `FcmAppScanner` can only see apps that declare those intents — intentional, and it should not be widened.
- **No root, no Shizuku, no ADB, no shell, no Accessibility, no VPN, no overlay, no device-admin, no account access.** This is a stated product commitment in the README ("Finance-app friendly"), not an accident.
- Manifest components: `MainActivity` (`exported=true`, launcher) and `BootReceiver` (`exported=true`, system broadcasts) are exported; `GuardService` is `exported=false`.

---

## 6. Existing translation implementation

There is **no localization engine** in the product sense. What exists is ordinary Android resource localization of the app's own UI:

- 10 locales, each with the same 75 strings (52 `strings.xml` + 23 `feature_strings.xml`): `en` (default), `zh-CN`, `zh-TW`, `fr`, `ja`, `ko`, `es`, `pt`, `de`, `ru`. Verified complete by string count per directory.
- `xml/locales_config.xml` declares the same 10 locales (API 33+ per-app language).
- Locale switching: `LocaleManager.setApplicationLocales` on API 33+; `Configuration` override + `attachBaseContext` below that. One-time migration from the pre-1.4.4 custom preference exists (`LocaleHelper.migrateLegacyPreference`).
- **Vietnamese does not exist.** No `values-vi`, not in `locales_config.xml`, and `LocaleHelper.normalize()` has no `vi` branch — it would return `"system"`, silently ignoring a Vietnamese selection.
- **Hardcoded, non-resource strings in Java** violate the spec's own §11 rule:
  - `MainActivity.LANGUAGE_BUTTON_LABELS` — 10 language names as a Java array, including the English `"Language"` for index 0.
  - `mixed-language constants`: `LANGUAGE_CODES` (acceptable, they are tags, not UI text).
  - Reset characters and separators (`"✓ "`, `·`, `：`) are inline.
- Translation quality: the `feature_strings.xml` files and `strings.xml` files are complete, but the `app_name` and `default_key`/`default_required_item` entries are intentionally untranslated constants. `values-de` etc. use XML numeric entities, which is fine.

---

## 7. Existing backup and rollback capability

**None that satisfies the specification.** Precisely:

- `prefs["last_good_<key>"]` stores the last normalized whitelist string. Its **only** purpose is to seed a repair when the live value has been wiped empty. It is not exposed in the UI, is not versioned, is not integrity-checked, and is overwritten on every successful write.
- There is no UI action that restores a previous value, and no code path that writes an old value back.
- Therefore spec §7 (backup integrity, system-wide/per-app rollback, transactional operation model, interrupted-operation recovery) is **entirely unimplemented**, and the upstream project does not model operations as state machines at all.

This is the highest-value genuine gap in the upstream app: it modifies a system setting on which push delivery depends, with no restorable snapshot and no verification read-back.

---

## 8. Compatibility constraints

| Constraint | Detail | Risk if ignored |
| --- | --- | --- |
| `targetSdk = 22` | Required for the vendor `Settings.System` write path | Raising it silently breaks the core feature |
| `minSdk = 21` | Legacy `Configuration`-based locale/theme override paths | Compose requires minSdk 21 — compatible, but the legacy branches must be preserved |
| HyperOS-only behavior | `MILLET_NO_RESTRICT_APP`, vendor AppOps `10008`/`10053`, `miui.intent.action.OP_AUTO_START`, Security Center activity names | Non-Xiaomi devices degrade to UNKNOWN/no-ops |
| HyperOS version variance | Unknown which HyperOS 3 builds expose these values | **UNVERIFIED** — no device tested |
| Narrow package visibility | `<queries>`-limited scan | Widening it changes the privacy posture and Play review surface |
| No Gradle wrapper | CI uses `gradle-version: '8.7'` | Local builds require a Gradle install |
| Debug signing | `signingConfigs.debug` points to `ci-signing/fcmguard-debug.keystore`, generated by CI, **not committed and not gitignored** | A fresh clone cannot build `debug` without creating the keystore |
| `android.useAndroidX=false` | Blocks all AndroidX/Compose | Must flip; requires verifying no support-library assumptions exist (there are none) |

---

## 9. Technical debt and risks

Ordered by severity. Line references are to the audited commit.

### 9.1 Correctness / data-safety

1. **No write verification.** `SettingsGuard.repair()` trusts `Settings.System.putString`'s boolean and returns `success=true` without reading the value back (`SettingsGuard.java:108-112`). A silently-rejected or partially-applied write is reported as a repair, and `FcmReconnect.kick()` fires on that unverified belief. This directly violates the specification's "never report success without verifying" principle.
2. **Guessed fallback whitelist.** When both the live value and `last_good` are empty, the code invents `com.tencent.mm` and `com.android.vending` (`SettingsGuard.java:98-101`). Preserving an empty value, or asking the user, would be honest; fabricating a package list is not. Behavior is undocumented in the README.
3. **No rollback and no pre-write snapshot of the *actual* original** (see §7). Combined with (1), a bad write cannot be undone from the app.
4. **`last_good` is overwritten even by fallback-repair writes**, so a fabricated list can become the recovery baseline.

### 9.2 Concurrency / lifecycle

5. **`repair()` is `synchronized` on the class, but callers run on the main thread** (activity actions and the service's main-looper `Handler`). `Settings.System.getString/putString` and `SharedPreferences` commits run on the UI thread. Small cost today, but it blocks the main thread during an operation that must never be blocking.
6. **Main-thread `PackageManager` scan**: `FcmAppScanner.scan()` runs inside the button click handler, then `AutostartStatusReader.check()` performs two reflective binder calls per app, all on the main thread (`MainActivity.scanFcmApps`, `renderFcmAppStatuses`). With many FCM apps this is a visible freeze. Spec §13 explicitly forbids this pattern.
7. **Fallback is in-process only.** If HyperOS kills the service, the 30-minute insurance silently disappears. `START_STICKY` mitigates but does not guarantee restart; no `AlarmManager` fallback exists by design. **UNVERIFIED** how often HyperOS kills it.
8. **`ContentObserver` re-registered on every `onStartCommand`** without a guard against duplicate registration beyond unregistering the previous instance; reentrancy of `repair()` via debounce + fallback + user action is serialized only by `synchronized`.

### 9.3 Reliability / diagnostics

9. **Systematic error swallowing.** 11 `catch (Throwable ignored)` sites, zero logging, zero diagnostic surface. Registration failures, service start failures, and broadcast failures are indistinguishable from success. This is the single biggest obstacle to the specification's "operation history" and "error details" requirements — there is nothing to report.
10. **`Result` conflates `success` and `changed`**, and `repair()` returns `success=true, changed=false` for "already protected" — callers must interpret the pair, and `GuardService.repair()` does so inconsistently (`if (result.changed) ... else if (!result.success ...)`).
11. **Notification path**: `setSmallIcon(android.R.drawable.stat_notify_sync_noanim)` is a framework resource; it renders acceptably but is not app-branded. `ensureNotificationChannel` is called repeatedly and relies on `getNotificationChannel == null` to detect first creation.

### 9.4 Structure / maintainability

12. **826-line `MainActivity`** doing view, state, locale, theme, insets, scanning, and privileged-call orchestration.
13. **No interfaces anywhere** → nothing is substitutable; the privileged path cannot be faked, so no test can exercise it.
14. **No test source sets at all** (`app/src` contains only `main`). `tools/check_layout_profiles.py` is the only automated check, and it is static XML geometry.
15. **Deprecated APIs**: `setSystemUiVisibility`, `getSystemWindowInset*`, `Window.setStatusBarColor` pre-R paths — all tolerated because of `minSdk 21`, but they are the first thing to replace with `WindowInsetsControllerCompat` — which requires AndroidX.
16. **Hardcoded UI strings in Java** (`LANGUAGE_BUTTON_LABELS`) and inline glyphs/separators.
17. **Version drift**: git history mentions "v1.5.10 as reference release" and "Restore v1.5.9 as stable release"; `build.gradle` says `1.5.9`, `versionCode 30`.
18. **Build reproducibility**: no wrapper, no version catalog, no dependency lock, keystore generated in CI and cached by key name only.

### 9.5 Security posture (what is *right*, and must not regress)

- No arbitrary command execution surface exists today. Any proposed shell backend would be **new attack surface** on devices that users specifically chose this app for because it avoids exactly that.
- `setPackage()`-scoped broadcasts and `resolveActivity`-guarded intents are used consistently.
- Narrow `<queries>` instead of `QUERY_ALL_PACKAGES`.
- `allowBackup=false`.
- Reflection is confined to a read-only `checkOpNoThrow` probe in one class.

---

## 10. Baseline build record

Environment on the audit host: no SDK on `PATH`, no Gradle, only JDK 27 (AGP 8.6.1 requires 17–21). A **project-local, gitignored** toolchain was created for verification:

| Item | Value |
| --- | --- |
| JDK | Temurin 17 (`.toolchain/jdk17`) |
| Gradle | 8.7 (`.toolchain/gradle`) |
| Android SDK | platform 35, build-tools 35.0.0, platform-tools (`.toolchain/sdk`) |
| Keystore | `ci-signing/fcmguard-debug.keystore`, generated exactly as the CI workflow does |
| `local.properties` | `sdk.dir` → local SDK (gitignored) |

Commands and result:

```
python3 tools/check_layout_profiles.py          → PASS (320/360/393/411/430/480 dp)
gradle :app:assembleDebug                       → BUILD SUCCESSFUL in 1m 29s (32 tasks)
```

Baseline artifact:

```
app/build/outputs/apk/debug/app-debug.apk
size    113,589 bytes
sha256  ee2aa3e9ba4b97b346fd01e6b5e4bcb309b7cdee321d33c6d5825ca4a9b76bf7
package com.reed.fcmguard  versionCode 30  versionName 1.5.9
targetSdkVersion 22   compileSdk 35
locales de, es, fr, ja, ko, pt, ru, zh-CN, zh-TW (+ default)
```

Only warnings: deprecated API usage in `javac` (`setSystemUiVisibility` family) — expected, not new.

**Regression baseline**: any future build must produce the same application ID, the same four permissions, the same three manifest components with identical `exported` values, `targetSdk 22`, and a passing layout-profile check. APK byte equality is *not* a valid criterion (the debug signature and build timestamps vary); behavioral equivalence is.

Build issues to document, not to hide: a fresh clone without `ci-signing/fcmguard-debug.keystore` fails at `:app:validateSigningDebug`, and a fresh clone without `local.properties`/`ANDROID_HOME` fails at configuration.

---

## 11. Components that must remain unchanged in behavior

These carry the product's value and its safety guarantees:

1. `targetSdk = 22` and the `WRITE_SETTINGS`-based write path to the vendor key.
2. The exact repair algorithm: preserve existing packages, order-preserving, append-only, single write, no write when already present.
3. The default configuration: key `MILLET_NO_RESTRICT_APP`, required item `com.google.android.gms`.
4. `FcmReconnect` semantics: only after a real change or an explicit user action; scoped broadcasts.
5. Read-only Autostart probing with `UNKNOWN` as a real state, never coerced.
6. Narrow package visibility (`<queries>`).
7. Absence of root/Shizuku/ADB/shell.
8. Event-driven monitoring with the non-waking, in-process 30-minute fallback and no `AlarmManager`/`WakeLock`.
9. Foreground/quiet mode distinction, `IMPORTANCE_LOW` silent channel, `START_STICKY`.
10. Boot/self-update restart behavior.
11. Existing 10 locales must not regress while Vietnamese is added.
12. The scroll-linked status card *behavior* (hero docking, lagging whitelist panel, rounded bottom edge) — a Compose port must reproduce it or the change must be explicitly justified.

---

## 12. Unknown / unverified

Marked explicitly rather than assumed:

1. Whether current HyperOS 3 builds still honor `MILLET_NO_RESTRICT_APP` writes from a `targetSdk 22` app. **UNVERIFIED** (no device).
2. Which HyperOS builds expose vendor AppOps `10008`/`10053` to third-party reflection. **UNVERIFIED**.
3. Actual service survival rate under HyperOS/`PowerKeeper` with quiet mode. **UNVERIFIED**.
4. Whether `GcmDiagnostics` exists on the GMS version on target devices. **UNVERIFIED**.
5. Behavior on Android 13+ where `POST_NOTIFICATIONS` is a runtime permission while `targetSdk` is 22 — the code checks `checkSelfPermission` and degrades to quiet mode, but the prompt-timing behavior is **UNVERIFIED** on device.
6. Whether any downstream fork depends on the current `SettingsGuard`/`SharedPreferences` key names (`guard_state`, `enabled`, `settings_key`, `required_item`, `ui_language`, `appearance_mode`, `last_good_*`). Treated as **migration-relevant**: keys must be preserved or migrated.
7. Whether the upstream project accepts the fork's divergence — a licensing/policy question (MIT permits it; attribution is required and already present).

---

## 13. Recommended migration strategy

### 13.1 Sequencing principle

Do not touch the write path until it can be tested and verified. Every phase below ends with the app fully functional, and the privileged path keeps its exact semantics until Phase 4 replaces the *implementation* (not the behavior) behind an interface, with verification added.

### 13.2 Phase plan (behavior-preserving)

**Phase 1 — Foundation (no behavior change).**
Flip `android.useAndroidX=true`; add Kotlin + Compose + Material 3 + DataStore + coroutines with pinned versions compatible with AGP 8.6.1 / Kotlin 2.x; add a Gradle wrapper and a version catalog; keep Groovy or migrate to `.kts` in one reviewed step; add a `SettingsGuard`-backed repository interface and a fake for tests. Everything still runs on the existing View UI. *Exit: identical APK behavior, `assembleDebug` green, first unit tests exist.*

**Phase 2 — Execution abstraction + verified operations (the real safety win).**
Introduce `PrivilegeManager`, `SystemSettingsGateway` (read/write with read-back verification), an explicit operation state machine (`Validating → Snapshotting → Writing → Verifying → Completed/Failed/RolledBack`) persisted in DataStore, and a whitelist snapshot store with SHA-256 integrity + restore. This is the honest counterpart of spec §7: the app *does* modify something, so it must be able to restore it. *Exit: every write is verified by read-back; restore of the prior value is a shipped, tested feature; unit + fake-backend tests cover it.*

**Phase 3 — Compose UI (design system first, then screens).**
Material 3 Expressive theme with dynamic color + fallback, typography/shape scale, then migrate: home dashboard → tools → FCM assistant list (LazyColumn, off-main-thread scan) → settings/appearance/language → about. Theme and locale move onto `AppCompat`/`ComponentActivity` + `LocaleManager` while preserving the pre-33 path. The sticky status card is ported last, as an explicit Compose scroll effect or an `AndroidView` wrapper. *Exit: all screens in Compose, M3 Expressive, dynamic color, both themes, all sizes, accessibility pass.*

**Phase 4 — Vietnamese UI + localization of the app itself + history/errors.**
Add `values-vi` (complete, diacritics, consistent terminology from spec §11), add `vi` to `locales_config.xml`, `LocaleHelper.normalize`, and the language picker; move `LANGUAGE_BUTTON_LABELS` into resources; add a real operation-history screen fed by the state machine, with human-readable errors and a diagnostics/log surface. *Exit: vi-VN complete, no hardcoded strings, history backed by persisted records.*

**Phase 5 — Hardening.**
Interrupted-operation detection on startup (pending records → recovery UI), storage/permission-revocation handling, instrumentation tests, R8 config, signed release, docs (`ARCHITECTURE.md`, `TESTING.md`, `COMPATIBILITY.md`), attribution and license notices, regression test on a real HyperOS device.

### 13.3 Why the sticky status card is the schedule risk

`StickyDashboardLayout` + `CollapsingStatusCard` encode geometry-dependent behavior: the outer card's translation is tied directly to `scrollY`; the whitelist panel follows a quintic smootherstep that starts at 38% of the collapse distance; the card's *visible bottom* is decoupled from its measured height so the `ScrollView` never jumps; a custom outline keeps the shadow on the moving edge; `drawChild` clips the panel below the details plate to hide an anti-aliasing seam. Reproducing this faithfully in Compose is a multi-day, device-verified task. It should be scheduled after the information architecture is settled, not during it.

### 13.4 What the specification asks for that cannot be built here without a scope decision

- **System-wide Việt hóa** and **per-app Việt hóa** (§6) require rewriting the resources of *other* applications' APKs. That needs root/Shizuku/ADB or a Magisk module plus an APK resource decoder/rebuilder, signature handling, and per-app version/compatibility tracking. None of that exists, and none of it can be implemented with `WRITE_SETTINGS`.
- **Translation coverage / datasets / resource fingerprints** (§6.2–6.4) depend on that engine existing.
- **Per-application rollback** (§7.3) depends on per-application modification existing.

Building these is a new product with a new privilege class, and it is **incompatible with this project's explicit no-root/no-Shizuku design**. Fabricating screens for them would violate the specification's own anti-shortcut rules.

Two coherent scopes therefore exist, and the choice is the user's:

**Scope A — FCMGuard modernization (recommended).** Build Mamori as a Kotlin/Compose/M3-Expressive, fully Vietnamese-localized, safely-verified reboot of the FCM watchdog: architecture, backed-up and verifiable system-setting changes, operation history, modern UI. Deliverable per Phases 1–5 above. The localization-engine sections are re-scoped to "localization of Mamori itself" + genuine Vietnamese as primary locale.

**Scope B — Add a Việt hóa subsystem alongside the watchdog.** Requires choosing a privileged backend (root / Shizuku), researching HyperOS resource packaging, and accepting that it cannot be validated without a test device. This would be a new engine built from scratch, with the FCM functionality preserved untouched; the specification's §8 rule ("do not introduce a new backend without verifying it is necessary") is satisfied only if no other route exists, and it does not: `WRITE_SETTINGS` cannot modify APK resources.

**Scope C — Hybrid, sequenced.** Ship Scope A through Phase 4, then treat the Việt hóa engine as a separate, documented, opt-in module with a verified backend and its own risk model. This preserves everything the specification values (safety, no fake statuses, recoverability) without freezing the UI/architecture work behind an unresearched engine.

Recommendation: **A now, C later.** A is complete, shippable, testable, and it delivers everything in the specification that the codebase can actually support — including full Vietnamese and a real backup/rollback story for the one thing Mamori genuinely modifies.
