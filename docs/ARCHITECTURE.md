# Mamori — Architecture

Status: Phase 1 (Kotlin foundation). This documents what exists **now**, not the target state. Where the target differs, it says so explicitly.

## 1. Product shape

Mamori is a no-root HyperOS utility. Its one privileged action is writing the comma-separated package list in a vendor `Settings.System` key so Google Play services stay out of HyperOS's background freezer. Everything else supports that action: observation, verification, repair, reporting, and (from Phase 2) snapshot/restore.

Hard constraints that shape every decision:

| Constraint | Why |
| --- | --- |
| `targetSdk = 22` | Writing a vendor-private `Settings.System` key with user-granted `WRITE_SETTINGS` requires the legacy target code path. Raising it silently removes the core feature. |
| `minSdk = 21` | Legacy locale/theme branches must keep working. |
| No root / Shizuku / ADB / shell | Product commitment and the reason users trust this app on banking-heavy devices. |
| Narrow `<queries>` | No `QUERY_ALL_PACKAGES`; the scanner only sees FCM/GCM declarants. |
| `applicationId = com.reed.fcmguard` | Upgrades must work in place. |

## 2. Current structure

```
app/src/main/java/com/reed/fcmguard/
├── SettingsGuard.kt          ← domain core: read / repair / persist (Kotlin)
├── PackageList.kt            ← pure whitelist rules: parse / join / contains / buildRepaired
├── LocaleTag.kt              ← pure locale-tag normalization
├── LocaleHelper.kt           ← per-app locale wiring (LocaleManager / legacy Configuration)
├── ThemeHelper.kt            ← System/Light/Dark Configuration override
├── FcmReconnect.kt           ← package-scoped heartbeat broadcasts
├── FcmAppScanner.kt          ← FCM/GCM declarant discovery
├── AutostartStatusReader.kt  ← read-only vendor AppOps probe (10008 / 10053)
├── HyperOsSettings.kt        ← HyperOS / MIUI deep links with fallbacks
├── GuardService.kt           ← foreground/quiet service, ContentObserver, in-process fallback
├── BootReceiver.kt           ← restart protection after boot / self-update
├── MainActivity.java         ← still Java: entire View-system UI (Phase 3 rewrite target)
├── StickyDashboardLayout.java   ← still Java: scroll-linked dashboard geometry
└── CollapsingStatusCard.java    ← still Java: custom surface, outline, clip mask
```

`app/src/test/java/com/reed/fcmguard/` holds JVM unit tests for the pure logic (`PackageListTest`, `LocaleTagTest`).

## 3. Dependency rule

```
UI (Activity/Service/Views)  ──►  SettingsGuard  ──►  PackageList (pure)
         │                          │
         │                          └──► Settings.System (the only privileged write)
         └──► read-only helpers (FcmAppScanner, AutostartStatusReader, HyperOsSettings)
```

- UI components never construct a privileged write themselves; every write goes through `SettingsGuard.repair`.
- Pure classes (`PackageList`, `LocaleTag`) import nothing from `android.*`, so the rules they encode are testable on the JVM.
- Only `SettingsGuard` touches `Settings.System` for writing; `GuardService` reads and observes it.

This is the seam the next phase widens: `repair()` becomes an explicit operation with a snapshot and a read-back verification, behind an interface that a fake can implement.

## 4. The repair operation today

```
repair(context)                                   @Synchronized
  ├─ Settings.System.canWrite?  → no  ⇒ Result(success=false, message=permission_missing)
  ├─ current = read()
  ├─ already contains required? → yes ⇒ rememberIfUseful(); Result(success=true, changed=false)
  ├─ buildRepaired(current, lastGood, required, seeds)
  │     preserve → lastGood fallback → seeds → append required
  ├─ putString(key, joined)
  └─ ok ⇒ persist last_good_<key>; Result(success=true, changed=true)
     !ok ⇒ Result(write_rejected)
     throw ⇒ Result(write_failed, exceptionSimpleName)
```

Known gaps, scheduled rather than hidden:

1. **No read-back verification.** The boolean from `putString` is trusted. Phase 2 makes `success` conditional on a confirming `getString`.
2. **Seeds on an empty whitelist.** `LEGACY_EMPTY_SEEDS` (`com.tencent.mm`, `com.android.vending`) preserves upstream behavior; it invents packages and is replaced by an explicit user confirmation in Phase 2. Callers now pass seeds explicitly so the change is a one-line call-site edit.
3. **No snapshot/restore.** `last_good_<key>` seeds repairs; it cannot be restored through the UI. Phase 2 adds a snapshot store with integrity checks and a restore action.
4. **Main-thread I/O.** Reads/writes and the app scan still run on the main thread; Phase 2 moves privileged work off it behind a serialized executor.

## 5. State and persistence

SharedPreferences (`guard_state`), keys preserved verbatim so existing installs keep their configuration:

| Key | Meaning |
| --- | --- |
| `enabled` | Automatic protection |
| `persistent_notification` | Foreground vs quiet mode |
| `settings_key` | Target `Settings.System` key (default `MILLET_NO_RESTRICT_APP`) |
| `required_item` | Package that must be present (default `com.google.android.gms`) |
| `ui_language` | Legacy (< API 33) app language |
| `appearance_mode` | `system` / `light` / `dark` |
| `last_good_<key>` | Last known-good whitelist value |
| `native_locale_migrated` | One-time locale migration flag |

DataStore replaces this only where it adds value (operation records, snapshots); preference keys are not churned for its own sake.

## 6. Failure model

Today: `SettingsGuard.Result(success, changed, value, message)` — a four-field struct where `success && !changed` means "already correct" and `success && changed` means "repaired". Callers must interpret the pair; `GuardService` does so by checking `changed` first.

Phase 2 replaces this with a sealed result type (`Applied`, `AlreadyCorrect`, `PermissionMissing`, `WriteRejected`, `WriteFailed(cause)`, `VerificationFailed`) plus persisted operation records, so the UI and the history screen never have to infer meaning from flags.

Error swallowing: 12 `catch (Throwable...)` sites remain, each with a comment stating why the failure is survivable. No logging exists yet; the ring-buffer logger lands in Phase 4 together with the diagnostics surface.

## 7. UI (current, transitional)

The View-system UI is unchanged: a fixed hero, a scroll-linked status card whose visible bottom edge is decoupled from its measured height, and cards for protection, tools, the FCM assistant, advanced configuration, and appearance. `MainActivity` still owns all of it.

Phase 2 introduces Compose and Material 3 Expressive. Until the custom collapse animation is reproduced faithfully (see `REPOSITORY_AUDIT.md` §13.3), those two custom views stay in place — correctness of the scroll behavior outranks toolkit purity.

## 8. Build

- Groovy Gradle DSL retained deliberately: `.github/workflows/build-apk.yml` derives the release tag from `versionName "x.y.z"` in `app/build.gradle` with `sed`. Moving to `.kts` requires changing that workflow in the same commit.
- `gradle/libs.versions.toml` centralizes plugin/library versions; AGP pinned to 8.6.1, Kotlin 2.0.21.
- Local verification toolchain (JDK 17, Gradle, SDK 35) lives in gitignored `.toolchain/`.
- Debug signing reads a CI-generated keystore at `ci-signing/fcmguard-debug.keystore` (gitignored, created by the workflow).
- Verification for every change: `python3 tools/check_layout_profiles.py`, `:app:testDebugUnitTest`, `:app:assembleDebug`, then confirm the APK still reports `com.reed.fcmguard`, `targetSdk 22`, the four permissions, and the three components.
