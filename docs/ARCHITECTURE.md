# Mamori — Architecture

Status: Phase 5 (hardening and release). This documents what exists **now**, not the target state. Where the target differs, it says so explicitly.

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
├── execution/                ← verified privileged writes (no magic, no guessing)
│   ├── SystemSettingsGateway.kt   ← the only reader/writer of the setting
│   ├── RepairEngine.kt            ← snapshot → write → read-back pipeline, outcomes
│   ├── SnapshotStore.kt           ← pre-write copies + SHA-256 integrity
│   ├── OperationJournal.kt        ← persisted state machine, history, interruption
│   ├── Operations.kt              ← Android entry point: serialized executor, callbacks, currentStage
│   └── OutcomeText.kt             ← one place that words every outcome
├── core/
│   ├── log/MamoriLog.kt      ← bounded in-memory ring + rolling file + one-shot warnings
│   └── locale/               ← BCP 47 normalization, locale catalog, capability policy,
│                               original-system-locale snapshot (pure + persistent)
├── data/
│   ├── MamoriRepository.kt   ← the only data seam for the UI: suspend reads, operation
│   │                           results, one DeviceStatus snapshot of live facts
│   ├── LocaleRepository.kt / AndroidLocaleRepository.kt
│   │                           ← verified system/per-app locale reads and writes
├── ui/
│   ├── MamoriApp.kt          ← Scaffold, NavigationBar, NavHost (Home/FCM/History/Settings)
│   ├── theme/                ← Material 3 Expressive theme: dynamic color, static fallback,
│   │                           typography, shapes, semantic status colors, motion scale
│   ├── components/           ← SectionCard, StatusLine, StatusPill, PackageChips, InfoNote
│   ├── home/                 ← HomeViewModel + HomeScreen (status, protection, actions, dialogs)
│   ├── fcm/                  ← FcmViewModel + FcmScreen (scan off the main thread, search, filter)
│   ├── history/              ← HistoryViewModel + HistoryScreen (persisted records)
│   ├── diagnostics/          ← DiagnosticsViewModel + DiagnosticsScreen (read-only facts,
│   │                           ROM info, log viewer, share/clear)
│   ├── language/             ← LanguageManagerViewModel + LanguageManagerScreen (system
│   │                           language, restore, per-app overrides)
│   └── settings/             ← SettingsViewModel + SettingsScreen (appearance, language,
│                               advanced, about)
├── MainActivity.kt           ← ComponentActivity: base context, window/insets, system screens
├── SettingsGuard.kt          ← configuration + read-only access (writes moved out)
├── PackageList.kt            ← pure whitelist rules: parse / join / contains / buildRepaired
├── LocaleTag.kt              ← pure locale-tag normalization
├── LocaleHelper.kt           ← per-app locale wiring (LocaleManager / legacy Configuration)
├── ThemeHelper.kt            ← System/Light/Dark override + dynamic-color preference
├── FcmReconnect.kt           ← package-scoped heartbeat broadcasts
├── FcmAppScanner.kt          ← FCM/GCM declarant discovery
├── AutostartStatusReader.kt  ← read-only vendor AppOps probe (10008 / 10053)
├── HyperOsSettings.kt        ← HyperOS / MIUI deep links with fallbacks
├── GuardService.kt           ← foreground/quiet service, ContentObserver, in-process fallback
└── BootReceiver.kt           ← restart protection after boot / self-update
```

No Java source remains. The three Java UI classes (`MainActivity`, `StickyDashboardLayout`,
`CollapsingStatusCard`) were replaced by the Compose shell rather than converted twice;
their XML layouts and drawables are deleted.

`app/src/test/java/com/reed/fcmguard/` holds 103 JVM unit tests: the execution contract
(`RepairEngineTest`, `SnapshotStoreTest`, `OperationJournalTest`), the pure rules
(`PackageListTest`, `LocaleTagTest`), the locale subsystem (`LocaleCodeTest`,
`LocaleCatalogTest`, `SystemLocaleSnapshotTest`, `LocaleCapabilityPolicyTest`,
`LanguageManagerViewModelTest`), the FCM list filtering (`FcmUiStateTest`) and the log ring
(`LogRingTest`).

## 3. Dependency rule

```
Compose screens ──► ViewModels ──► MamoriRepository ──► Operations ──► RepairEngine
   (no R.string                                                      │   │   │
    lookups in                                                       │   │   └─► SnapshotStore
    ViewModels)                                                      │   └─────► OperationJournal
                                                                     └─────────► SystemSettingsGateway
Activity ──► system screens (WRITE_SETTINGS, notification settings, HyperOS deep links)

FcmViewModel ──► FcmAppScanner + AutostartStatusReader   (Dispatchers.IO)
SettingsViewModel ──► ThemeHelper / LocaleHelper / SettingsGuard  (preferences only)
DiagnosticsViewModel ──► MamoriRepository + MamoriLog  (read-only)

Any component ──► MamoriLog   (ring buffer now, file on a daemon thread)
```

- No composable constructs a privileged write or reads the setting. Screens call ViewModels;
  ViewModels call `MamoriRepository`; only the repository touches `Operations`.
- No ViewModel starts an Activity. Anything needing one (`WRITE_SETTINGS`, notification
  settings, diagnostics, Autostart manager) is a one-shot event the screen hands back to
  `MainActivity`.
- `RepairEngine` depends only on interfaces (`SystemSettingsGateway`, `SnapshotStore`, `OperationJournal`), so the whole pipeline is testable on the JVM with a fake gateway and a temporary directory.
- Pure classes (`PackageList`, `LocaleTag`) import nothing from `android.*`.
- Privileged work runs on one daemon thread; callbacks are posted to the main looper. The UI thread never touches a content provider, the PackageManager scan, or the AppOps probe.
- `Operations.currentStage()` exposes the journaled stage of the operation running in this
  process. The dashboard polls it off the main thread to label progress; it never infers a
  stage that the engine did not write.

## 4. The repair operation today

```
journal.active()?            → pending ⇒ RecoveryRequired (no write, no auto-repair)
canWrite()?                  → no      ⇒ PermissionMissing (no journal entry)
journal.start(op)            → VALIDATING
read current                 → already contains required ⇒ COMPLETED / AlreadyCorrect
whitelist empty, unconfirmed → CANCELLED / ConfirmationRequired (writes nothing)
snapshot previous value      → fail ⇒ FAILED (write abandoned)
write                        → rejected/threw ⇒ FAILED
read back                    → mismatch ⇒ PARTIALLY_COMPLETED / VerificationFailed
                             → match    ⇒ COMPLETED / Applied
```

Full contract, snapshot format, retention and recovery rules: [`BACKUP_AND_ROLLBACK.md`](BACKUP_AND_ROLLBACK.md).

Remaining gaps, scheduled rather than hidden:

1. **No settings deletion.** An absent original cannot be snapshotted, so "restore to absent" is refused instead of faked.
2. **History is recorded but not displayed.** The records exist for the Phase 4 history and diagnostics screens.
3. **FCM app scanning still runs on the main thread** when the user expands the list. It is user-initiated and read-only, but it is the next thing to move.
4. **`last_good_<key>` still exists** as a repair fallback seeded by a read. It can only ever supply packages that were observed in the setting — it no longer invents any.

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
| `dynamic_color` | Material You palette; default `true`, honoured on Android 12+ only |
| `last_good_<key>` | Last known-good whitelist value |
| `native_locale_migrated` | One-time locale migration flag |
| `operation_active` | In-flight operation record; its presence is the recovery signal |
| `operation_history` | Newest-first operation records, capped at 50 |

Snapshots are files under `filesDir/snapshots/` rather than preferences, because each one carries a digest and a value that must be readable independently of the app's configuration. DataStore would replace preferences only where it adds value; there is no reason to churn the keys above.

## 6. Failure model

Outcomes are sealed types, not flags: `RepairOutcome` (`Applied`, `AlreadyCorrect`, `PermissionMissing`, `ConfirmationRequired`, `SnapshotFailed`, `ReadFailed`, `WriteRejected`, `WriteFailed`, `VerificationFailed`, `RecoveryRequired`) and the parallel `RestoreOutcome`. A caller cannot mistake "nothing to do" for "repaired", and cannot mistake "accepted but unconfirmed" for success.

`OutcomeText` is the single place that turns outcomes into user-facing strings, so the notification and the dashboard cannot drift apart.

Error handling stays explicit at the boundary: platform exceptions become a named outcome with the exception class in the journal `detail`. The remaining `catch (Throwable)` sites are in read-only helpers (`FcmAppScanner`, `AutostartStatusReader`, `HyperOsSettings`, `LocaleHelper`) where the failure is survivable by construction.

Phase 4 gives every outcome a user-facing explanation. `OutcomeExplanations` maps each
`RepairOutcome`/`RestoreOutcome` to a headline, an optional recovery suggestion and the
technical detail it captured; `OutcomeText` now delegates to it, so the notification line
and the result dialog cannot drift. Successful verified writes are reported with a
snackbar; anything failed, partial or unverifiable opens the result dialog with the
suggestion and detail, plus a link to the operation history.

Logging: `MamoriLog` keeps the last 300 entries in memory and appends one line per entry
to `filesDir/logs/mamori.log` on a dedicated daemon thread. The file is trimmed to 64 KB
at a line boundary once it passes 128 KB, so it cannot grow without bound. Repeated
systematic failures (vendor AppOps reflection, package queries) use `warnOnce` so a scan
of 80 apps cannot bury the rest of the log. Nothing in the logging path can throw into a
caller, and `attach()` performs no I/O on the thread that calls it.

## 7. UI (Compose, Phase 3)

All application UI is Jetpack Compose with Material 3 Expressive. Four top-level
destinations in a `NavigationBar`:

| Screen | What it shows | What it can do |
| --- | --- | --- |
| Home | Pinned compact status strip, four live status lines, whitelist chips, last three records | Repair, restore, wake FCM, protection and notification switches, all confirmations and the recovery prompt |
| FCM apps | Detected FCM/GCM declarants with Autostart state, search, filter | Scan (off the main thread), open HyperOS Autostart |
| History | Persistent journal records, newest first | Expand a record to inspect id, start time, backup reference and detail |
| Settings | Appearance, language, advanced configuration, about | Theme mode, dynamic color, language, key/required item, permission and diagnostics entry points |

Design decisions and their reasons:

1. **Dynamic color with a real fallback.** Android 12+ uses `dynamicLight/DarkColorScheme`;
   everything else, and the user who turns it off, gets the static palette. Only Material 3
   roles are used, plus a small semantic status set (ok/warn/error/neutral) that is not a
   theme role, so "needs attention" cannot silently become "protected".
2. **The scroll-linked status card is replaced, deliberately.** The View implementation
   (`StickyDashboardLayout` + `CollapsingStatusCard`) decoupled the card's visual bottom edge
   from its measured height and relied on draw-order masking to hide the whitelist panel. A
   faithful Compose port of that geometry is fragile and was not attempted. Instead the
   status summary is a `LazyColumn` sticky header — pinned, always legible, no masking — and
   the full detail card scrolls normally. This is a documented interaction change, not a lost
   feature: the same facts are on screen in the same order.
3. **Motion is information, not decoration.** Stage text and the progress indicator reflect
   the journal state; nothing animates a value it does not have. The only animations are
   Compose's own entrance/exit transitions, which honour the system animator scale, so a
   reduced-motion setting disables them without any app-specific code.
4. **No fabricated states.** `DeviceStatus` is a snapshot of real reads and stays `null` until
   the first read lands (the UI shows a spinner). Autostart `UNKNOWN` stays `UNKNOWN` in the
   list, the filter, and the summary. An operation shows one of the engine's states or the
   generic "Working…", never an invented percentage.
5. **Localized by construction.** No literal `Text("...")` exists in `ui/`;
   `tools/check_layout_profiles.py` fails the build if one appears. Existing strings are
   reused wherever the meaning did not change, so the ten legacy locales keep working.
6. **The rotating language-button labels are gone.** The old hero button cycled ten
   hardcoded language names (including an English "Language" in every locale) as a
   decorative affordance. Language now lives in Settings and always states the current
   selection; the platform picker remains the underlying mechanism.

Strings added in Phase 3 were English-only until Phase 4, which added `values-vi` for the
complete set (see §9). The other nine locales still fall back to English for the keys they
do not define — the same behaviour they had before, and visible rather than half-translated.

### Language manager (system and per-app)

`Settings → Language manager` (`ui/language/`) is a fifth screen reachable from Settings.
It reads and writes locales through `data/LocaleRepository`, whose Android implementation is
`data/AndroidLocaleRepository`:

| Operation | Mechanism | Verification |
| --- | --- | --- |
| Read system locales | `LocaleManager.getSystemLocales()` (API 33+), else `LocaleList.getDefault()` | The source is reported (`LOCALE_MANAGER` / `PLATFORM_DEFAULT`); never inferred |
| Write system locales | `Settings.System` key `system_locales` with the user-granted `WRITE_SETTINGS` access — the store the framework itself reads, since Android exposes no public `setSystemLocales` | Re-reads the store, then polls `getSystemLocales()` for 8 s. Store-only changes are reported as pending restart, never as applied |
| Capture / restore original | `core/locale/SystemLocaleSnapshotStore` over `KeyValueStore` (`locale_state` prefs), SHA-256 over the canonical payload, captured before the first write and never overwritten | Restore verifies the record, then applies and verifies like any other write |
| Mamori's own app locale | `LocaleManager.setApplicationLocales` (API 33+); legacy `LocaleHelper` preference path below that | Read-back must equal the requested tag |
| Another app's locale | Read only, via `LocaleManager.getApplicationLocales(packageName)` on API 35, which is signature-permission gated; writes have no public API | The `SecurityException` becomes a per-app "not readable" state, and the row deep-links to app info instead of pretending |

`core/locale/LocaleCode` normalizes and validates BCP 47 tags (`en-us` → `en-US`,
`zh-hans-cn` → `zh-Hans-CN`), `LocaleCatalog` builds the system picker from the device's own
locale table with common entries first, and `LocaleCapabilityPolicy` decides what the current
Android version and grants allow. All four are pure and unit-tested (`LocaleCodeTest`,
`LocaleCatalogTest`, `SystemLocaleSnapshotTest`, `LocaleCapabilityPolicyTest`), as is the
ViewModel state machine (`LanguageManagerViewModelTest`, fake repository).

The screen never shows a state it could not verify: unreadable apps render "Unknown (not
readable)", a missing permission disables the action and offers the grant, and a write that
the platform did not confirm produces a failure or pending-restart message rather than a
success one.

## 8. Build

- Groovy Gradle DSL retained deliberately: `.github/workflows/build-apk.yml` derives the release tag from `versionName "x.y.z"` in `app/build.gradle` with `sed`. Moving to `.kts` requires changing that workflow in the same commit.
- `gradle/libs.versions.toml` centralizes plugin/library versions; AGP pinned to 8.6.1, Kotlin 2.0.21.
- Local verification toolchain (JDK 17, Gradle, SDK 35) lives in gitignored `.toolchain/`.
- Debug signing reads a CI-generated keystore at `ci-signing/fcmguard-debug.keystore` (gitignored, created by the workflow).
- Verification for every change: `python3 tools/check_layout_profiles.py`, `:app:testDebugUnitTest`, `:app:assembleDebug`, then confirm the APK still reports `com.reed.fcmguard`, `targetSdk 22`, the four permissions, and the three components.
- `tools/check_layout_profiles.py` was rewritten with the Compose UI: it now forbids XML
  dashboard layouts, fixed widths above 96 dp, hardcoded `Text("...")` literals, missing
  navigation destinations, and the return of the legacy View classes. The old XML geometry
  assertions were deleted with the layouts they measured. Phase 4 added English/Vietnamese
  parity, and Phase 5 added build/manifest invariants (`applicationId`, `minSdk`,
  `targetSdk`, versionName format, the four permissions, the three components and their
  `exported` values).
- **Release builds**: `minifyEnabled true`, `shrinkResources true`, R8 with
  `app/proguard-rules.pro` (manifest entry points kept explicitly; the only reflection in
  the app targets the platform `AppOpsManager`, which R8 never renames). The release APK is
  unsigned — signing stays external, as before. `lint` disables only
  `ExpiredTargetSdkVersion`, because `targetSdk 22` is a deliberate product constraint and
  the app is not distributed through Play.
- **Version**: `versionCode 40`, `versionName 2.0.0`. Higher than upstream 30/1.5.9 so an
  existing FCMGuard install upgrades rather than being rejected as a downgrade. The
  `versionName "x.y.z"` form is preserved for the CI `sed`.
- **Instrumentation tests** live in `app/src/androidTest` and compile on every debug
  build. See [`TESTING.md`](TESTING.md) for what is and is not executed, and
  [`COMPATIBILITY.md`](COMPATIBILITY.md) for the `targetSdk 22` installation restriction,
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and
  [`RELEASE_NOTES.md`](RELEASE_NOTES.md) for distribution information.

## 9. Localization (Phase 4)

Vietnamese is a first-class locale, not a translation add-on:

| Piece | State |
| --- | --- |
| `values-vi/strings.xml` + `values-vi/feature_strings.xml` + `values-vi/language_strings.xml` | Complete: all 254 keys, same format specifiers as English |
| `values-vi/arrays.xml` | Language picker list, same length and order as `values/arrays.xml` |
| `locales_config.xml` | Declares `vi`, so `LocaleManager.setApplicationLocales` accepts it on Android 13+ |
| `LocaleTag.normalize` | `vi`, `vi-VN` and mixed case all map to `vi` |
| Language picker | Offers `vi`; a Vietnamese device uses `values-vi` automatically |

Terminology is fixed across the app (spec §11): *Việt hóa* for localization, *Sao lưu* for
backup, *Phục hồi* for restore, *Khôi phục* for rollback, *Lịch sử thao tác* for operation
history, *Tính tương thích* for compatibility, *Mức độ hoàn thiện bản dịch* for translation
coverage. Only terminology that applies to features this app actually has is used; there is
no Việt hóa engine in Mamori, so no string claims there is one.

Guard: `tools/check_layout_profiles.py` fails when an English key is missing from
`values-vi`, when format specifiers differ, when a Vietnamese key has no English source, or
when the picker codes and both `language_entries` arrays disagree in length. That keeps the
next feature from silently shipping English-only.

## 10. Known limitations (Phases 3–4, documented not papered over)

1. **Not verified on a device.** No HyperOS device is attached on this host. The Compose UI
   builds and its pure logic is tested, but scroll behaviour, insets on MIUI, dynamic color
   with a HyperOS palette, the permission flows and the Vietnamese rendering are unverified
   at runtime.
2. **The Vietnamese translation has not been reviewed by a native speaker in this
   workflow.** It is complete, terminologically consistent and resource-parity-checked, but
   wording review is still owed before release.
3. **AndroidX merges library components into the manifest.** `androidx.core` declares
   `com.reed.fcmguard.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature-level,
   app-scoped, used by `ContextCompat.registerReceiver`), and `androidx.startup` adds
   `InitializationProvider` (emoji2, process lifecycle, ProfileInstaller) plus
   `ProfileInstallReceiver`. These are standard library entries with no app-facing surface.
   Mamori's own three components (`MainActivity` exported, `GuardService` not exported,
   `BootReceiver` exported), its `<queries>` block and its four permissions are byte-for-byte
   the same as before Phase 3.
4. **APK size grows.** Compose, Material 3 and the Compose runtime are significantly larger
   than the old View UI. R8 and a keep-rule review remain Phase 5 work.
5. **Language changes restart the Activity.** This preserves the pre-33 configuration path
   and `LocaleManager` consistency; an in-place recomposition would need a second locale
   pipeline.
6. **The language picker no longer deep-links to the system app-language screen on API 33+.**
   It still sets the same `LocaleManager.applicationLocales` value, and every declared locale
   is accepted because the list matches `locales_config.xml` exactly.
7. **Compact-width and accessibility compliance is designed for, not instrumented.**
   Instrumentation tests and a device matrix remain Phase 5.
8. **Android 14+ blocks new installations of an APK targeting API 22.** This is a platform
   policy, not a bug in Mamori, and it is the single largest distribution risk. Upgrades
   over an existing install and the `adb --bypass-low-target-sdk-block` path are
   **unverified**; see `COMPATIBILITY.md` §2.1. Raising `targetSdk` would require replacing
   the write mechanism.
9. **The release APK is R8-shrunk but never launched in this environment.** Shrinking
   succeeded and the packaged resources were inspected, but a runtime smoke test (launch,
   repair, restore) is still required before distribution.
10. **Release signing is external.** The repository builds an unsigned release APK; CI
    builds the debug-signed one. A published build must use the key that existing installs
    trust.
