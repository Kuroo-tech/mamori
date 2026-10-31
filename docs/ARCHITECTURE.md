# Mamori — Architecture

Status: Phase 2 (verified execution, snapshot/restore). This documents what exists **now**, not the target state. Where the target differs, it says so explicitly.

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
│   ├── Operations.kt              ← Android entry point: serialized executor, callbacks
│   └── OutcomeText.kt             ← one place that words every outcome
├── SettingsGuard.kt          ← configuration + read-only access (writes moved out)
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
UI (Activity/Service/Views)  ──►  Operations  ──►  RepairEngine  ──►  SystemSettingsGateway
         │                        (executor)        │   │   │              └──► Settings.System
         │                                         │   │   └──► SnapshotStore (files)
         │                                         │   └──────► OperationJournal (prefs)
         │                                         └──────────► PackageList (pure)
         └──► read-only helpers (FcmAppScanner, AutostartStatusReader, HyperOsSettings)
```

- No UI component constructs a privileged write. Every write goes `Operations → RepairEngine → gateway`.
- `RepairEngine` depends only on interfaces (`SystemSettingsGateway`, `SnapshotStore`, `OperationJournal`), so the whole pipeline is testable on the JVM with a fake gateway and a temporary directory.
- Pure classes (`PackageList`, `LocaleTag`) import nothing from `android.*`.
- Privileged work runs on one daemon thread; callbacks are posted to the main looper. The UI thread never touches a content provider.

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
| `last_good_<key>` | Last known-good whitelist value |
| `native_locale_migrated` | One-time locale migration flag |
| `operation_active` | In-flight operation record; its presence is the recovery signal |
| `operation_history` | Newest-first operation records, capped at 50 |

Snapshots are files under `filesDir/snapshots/` rather than preferences, because each one carries a digest and a value that must be readable independently of the app's configuration. DataStore would replace preferences only where it adds value; there is no reason to churn the keys above.

## 6. Failure model

Outcomes are sealed types, not flags: `RepairOutcome` (`Applied`, `AlreadyCorrect`, `PermissionMissing`, `ConfirmationRequired`, `SnapshotFailed`, `ReadFailed`, `WriteRejected`, `WriteFailed`, `VerificationFailed`, `RecoveryRequired`) and the parallel `RestoreOutcome`. A caller cannot mistake "nothing to do" for "repaired", and cannot mistake "accepted but unconfirmed" for success.

`OutcomeText` is the single place that turns outcomes into user-facing strings, so the notification and the dashboard cannot drift apart.

Error handling stays explicit at the boundary: platform exceptions become a named outcome with the exception class in the journal `detail`. The remaining `catch (Throwable)` sites are in read-only helpers (`FcmAppScanner`, `AutostartStatusReader`, `HyperOsSettings`, `LocaleHelper`) where the failure is survivable by construction. No logging exists yet; the ring-buffer logger lands in Phase 4 together with the diagnostics screen.

## 7. UI (current, transitional)

The View-system UI is unchanged: a fixed hero, a scroll-linked status card whose visible bottom edge is decoupled from its measured height, and cards for protection, tools, the FCM assistant, advanced configuration, and appearance. `MainActivity` still owns all of it.

Phase 2 added three dialogs to the existing View-system UI: empty-whitelist confirmation, restore confirmation, and interrupted-operation recovery (plus a `Restore previous value` action). Phase 3 introduces Compose and Material 3 Expressive; until the custom collapse animation is reproduced faithfully (see `REPOSITORY_AUDIT.md` §13.3), those two custom views stay in place — correctness of the scroll behavior outranks toolkit purity.

New strings for these dialogs exist only in `values/` (English). Vietnamese and the other locales get them in Phase 4, where `values-vi` is created; until then they fall back to English rather than showing an untranslated placeholder.

## 8. Build

- Groovy Gradle DSL retained deliberately: `.github/workflows/build-apk.yml` derives the release tag from `versionName "x.y.z"` in `app/build.gradle` with `sed`. Moving to `.kts` requires changing that workflow in the same commit.
- `gradle/libs.versions.toml` centralizes plugin/library versions; AGP pinned to 8.6.1, Kotlin 2.0.21.
- Local verification toolchain (JDK 17, Gradle, SDK 35) lives in gitignored `.toolchain/`.
- Debug signing reads a CI-generated keystore at `ci-signing/fcmguard-debug.keystore` (gitignored, created by the workflow).
- Verification for every change: `python3 tools/check_layout_profiles.py`, `:app:testDebugUnitTest`, `:app:assembleDebug`, then confirm the APK still reports `com.reed.fcmguard`, `targetSdk 22`, the four permissions, and the three components.
