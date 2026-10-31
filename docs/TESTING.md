# Mamori — Testing

Status: Phase 5. This documents what is automated, what is deliberately manual, and what
could not be run in the environment where Phases 3–5 were implemented.

## 1. Layers

| Layer | Command | Count | Runs where |
| --- | --- | --- | --- |
| Static UI/localization guards | `python3 tools/check_layout_profiles.py` | 8 checks | Any machine, no SDK needed |
| JVM unit tests | `gradle :app:testDebugUnitTest` | 103 tests | JVM, no device |
| Instrumentation (Compose UI) | `gradle :app:connectedDebugAndroidTest` | 5 tests | Device/emulator |
| Release build (R8 + resource shrinking) | `gradle :app:assembleRelease` | — | JVM + SDK |
| Device regression | manual matrix (§5) | — | Real HyperOS device |

### Static guards (`tools/check_layout_profiles.py`)

1. No XML dashboard layout returns.
2. No fixed width above 96 dp in `ui/` (the 320 dp regression that motivated the old XML
   checker).
3. The four top-level routes still exist.
4. No literal `Text("...")` in `ui/` — all user-facing text comes from resources.
5. Every English string exists in `values-vi` with identical format specifiers, and no
   Vietnamese key lacks an English source.
6. `vi` is declared in `locales_config.xml`, offered by the picker, and the picker codes
   match both `language_entries` arrays in length.
7. The legacy View-system classes stay deleted.

### JVM unit tests

| File | Covers |
| --- | --- |
| `PackageListTest` | parse/join/contains/append-only repair rules |
| `LocaleTagTest` | locale normalization, including `vi` variants |
| `RepairEngineTest` | verified write pipeline, idempotency, confirmation, recovery blocking |
| `SnapshotStoreTest` | snapshot round-trip, SHA-256 integrity, retention |
| `OperationJournalTest` | state machine, terminal states, interruption detection |
| `FcmUiStateTest` | search/filter/count logic and the all-UNKNOWN presentation |
| `LogRingTest` | ring capacity, ordering, eviction, line format |
| `LocaleCodeTest` | BCP 47 normalization, validation, display helpers |
| `LocaleCatalogTest` | picker ordering, script-duplicate removal, search |
| `SystemLocaleSnapshotTest` | snapshot normalization, integrity, no-overwrite capture |
| `LocaleCapabilityPolicyTest` | what each SDK level and grant allows, including the system-locale write gate |
| `LanguageManagerViewModelTest` | load, search/filter, verified change, pending restart, restore, rejection, duplicate-tap guard |

The execution tests run against `FakeGateway` and a temporary directory. They never touch
the real `Settings.System`, so they are safe on any machine.

### Instrumentation tests (`app/src/androidTest`)

| Test | Assertion |
| --- | --- |
| `bottomBarReachesEveryTopLevelScreen` | each tab shows its screen; FCM screen starts unscanned |
| `themeSegmentedButtonsReflectTheSelection` | Dark/Light/System selection state |
| `languageDialogListsVietnamese` | the picker lists every declared locale |
| `vietnameseStringsResolveWithCorrectDiacritics` | `values-vi` is packaged and resolves |
| `vietnameseAndEnglishDiffer` | base English and Vietnamese are distinct |

They deliberately do **not** perform writes: `WRITE_SETTINGS` is a user-granted privilege,
and a test that flips the system setting would need a recovery path on the test device.
Write behaviour is covered by the fake-gateway unit tests instead.

## 2. What could not be verified here

No Android device or emulator was attached in the environment where Phases 3–5 were
implemented. Consequences:

- The instrumentation tests were **compiled** (`:app:assembleDebugAndroidTest`) but not
  **executed**.
- The release APK builds and its contents were inspected with `aapt2`, but it was not
  installed or launched.
- Scroll behaviour, MIUI insets, dynamic color against a HyperOS palette, notification
  permission follow-up, and Vietnamese rendering on-device remain unverified.

These are release blockers, not cosmetic gaps. See §5.

## 3. Commands

```bash
# Full local verification (no device)
python3 tools/check_layout_profiles.py
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:assembleRelease        # R8 + resource shrinking

# APK invariants
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk

# With a device attached
./gradlew :app:connectedDebugAndroidTest
```

## 4. Release checklist

1. `python3 tools/check_layout_profiles.py` → PASS.
2. `:app:testDebugUnitTest` → 0 failures.
3. `:app:assembleDebug` and `:app:assembleRelease` → success.
4. `aapt2 dump badging` on both APKs → `com.reed.fcmguard`, `targetSdk 22`, the four
   permissions, three components, `vi` in the locale list.
5. Install on a device and confirm: launch, language switch, theme switch, repair with the
   permission granted, restore of the previous value, and recovery after a mid-operation
   kill. Only then publish.

## 5. Device matrix (not yet executed)

| Case | Why it matters |
| --- | --- |
| Clean install on HyperOS 3 (Android 16) | the `targetSdk 22` install restriction may block it; see `COMPATIBILITY.md` |
| Upgrade over an existing FCMGuard install | same `applicationId`, higher `versionCode`; signing key must match |
| Permission missing → grant → repair | the only privileged path |
| Permission revoked while the service runs | must surface as an outcome, not a silent failure |
| Kill the process during a write | journal must offer restore/dismiss, never auto-repair |
| Storage full during snapshot | repair must abort without writing |
| Notification permission denied | quiet mode must still work, status must say so |
| Rotation and 320 dp width | layout and touch targets |
| Vietnamese system locale | navigation, dialogs, long strings |
| Dynamic color on/off, light/dark/system | theme coherence |
| Non-Xiaomi ROM | setting key absent, Autostart UNKNOWN — must not fabricate state |
