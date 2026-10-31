# Mamori — Implementation Plan (post-audit)

Status: **Phase 0–5 complete.** All planned phases are implemented; device verification remains outstanding (see `TESTING.md` §2).
Source of truth for current behavior: [`REPOSITORY_AUDIT.md`](REPOSITORY_AUDIT.md). Current structure: [`ARCHITECTURE.md`](ARCHITECTURE.md).

### Decisions taken

| Decision | Outcome |
| --- | --- |
| Scope | **A** — modernize the FCM watchdog. Việt hóa engine (B) is out of scope; option C remains open later. |
| Displayed name | **Mamori** (applied to `app_name` in all locales; `applicationId` unchanged). |
| Fabricated whitelist seeds | Replaced with an explicit user confirmation — scheduled in Phase 2. |

### Phase 1 status

Landed: Kotlin plugin + version catalog, `android.useAndroidX=true`, 9 of 12 classes converted to Kotlin, pure logic extracted into `PackageList`/`LocaleTag`, 19 JVM unit tests, Mamori rename, `.toolchain/` and `ci-signing/` gitignored.

Phase 1 closed by Phase 3: the remaining Java (`MainActivity`, `StickyDashboardLayout`, `CollapsingStatusCard`) was replaced by the Compose shell, not converted twice. No Java source remains.

### Phase 2 status (complete)

Landed: `SystemSettingsGateway` + `RepairEngine` (snapshot → write → read-back), `SnapshotStore` with SHA-256 integrity and retention, `OperationJournal` persisted state machine with interruption detection, `Operations` (serialized background executor, main-thread callbacks), `OutcomeText`, three dialogs and a restore action in the existing UI. Fabricated whitelist seeds removed. 52 JVM tests, 0 failures.

Contract and limits: [`BACKUP_AND_ROLLBACK.md`](BACKUP_AND_ROLLBACK.md).

Exit criteria:

| Criterion | State |
| --- | --- |
| Every write verified by read-back; failures surface as failures | Met in code and unit tests; on-device behavior unverified (no device attached). |
| Restoring a snapshot is a shipped, tested feature | Met: `restore` action, confirmation dialog, digest check, pre-restore snapshot. |
| Killing the process mid-operation is detectable and user-confirmable, never silent | Met: journal blocks repair with `RecoveryRequired` until the user chooses restore or dismiss. |

Carried forward from Phase 2: diagnostics screen (Phase 4). The Compose UI and the
off-main-thread FCM scan landed in Phase 3.

### Phase 3 status (complete)

Landed: Compose + Material 3 (`compose-bom 2024.09.03`, Kotlin compose plugin 2.0.21),
dynamic color with a static fallback palette, typography/shape scales, a four-destination
`NavigationBar` shell (`Home` / `FCM apps` / `History` / `Settings`), the dashboard with a
pinned status strip and real status lines, the FCM assistant with search/filter and the
scan plus vendor AppOps probe on `Dispatchers.IO`, a history screen backed by the real
journal, and settings (appearance, language, advanced configuration, about).
`MainActivity` is now a Kotlin `ComponentActivity`; the Java UI, its layouts and its
drawables are deleted.

Verification: `tools/check_layout_profiles.py` PASS, 58 JVM tests 0 failures,
`:app:assembleDebug` green, APK still `com.reed.fcmguard` / `targetSdk 22` / the four
permissions / three components. On-device behaviour is unverified — no device attached.

Deviations, and why:

1. **The scroll-linked status card was not ported.** Its geometry relied on a custom
   `drawChild` clipping mask and a decoupled visual bottom edge. The replacement is a
   `LazyColumn` sticky status strip plus a normal detail card — same information, same
   order, no fragile masking. Rationale in `ARCHITECTURE.md` §7.
2. **`History` shipped with Phase 3, not Phase 4.** The journal already persisted the
   records; rendering them is read-only and the navigation deliverable named the tab.
   The Phase 4 work that remains is diagnostics, logging, error surfaces and Vietnamese.
3. **`tools/check_layout_profiles.py` was rewritten, not deleted.** It no longer has XML
   geometry to inspect; it now guards compact-width and localization regressions that are
   statically checkable.
4. **Stage reporting is observation, not instrumentation.** `Operations.currentStage()`
   exposes the state the engine already journals; the engine itself was not modified.

### Phase 4 status (complete)

Landed: complete `values-vi` (163 string keys, `feature_strings`, language array), `vi`
wired into `locales_config.xml`, `LocaleTag.normalize` and the picker; `MamoriLog` (300-entry
in-memory ring, rolling 128/64 KB file on a daemon thread, `warnOnce` for systematic
probe failures); a read-only diagnostics screen reachable from Settings (write mechanism,
permission and service state, configuration, stored backups/records, device and ROM facts,
log viewer with share and clear); `OutcomeExplanations` giving every failed or partial
outcome a headline, a recovery suggestion and its captured technical detail, rendered in a
result dialog with a link to history; logging at the service, executor, scanner, AppOps
probe, deep-link and repository boundaries; and a parity guard in the layout checker.

Verification: `tools/check_layout_profiles.py` PASS (including English/Vietnamese key and
format parity), 63 JVM tests 0 failures, `:app:assembleDebug` green, APK still
`com.reed.fcmguard` / `targetSdk 22` / the four permissions / three components, and `vi`
present in the packaged locale list.

Deviations, and why:

1. **History was already shipped in Phase 3.** Phase 4 kept it unchanged; only the result
dialog now links to it.
2. **Log export uses `ACTION_SEND` plain text.** A file export would need a `FileProvider`
   and a new manifest component for no diagnostic gain; sharing the text keeps the manifest
   at three components.
3. **Some suggestions are deliberately generic.** The engine reports the exception class,
   not an errno, so "free up storage" is offered for snapshot failure instead of pretending
   to know the exact cause.
4. **The Vietnamese translation is complete but not native-reviewed** — recorded as a
   release limitation in `ARCHITECTURE.md` §10.
5. **Instrumentation tests still do not exist.** Phase 4 added unit coverage for the log
   ring and the `vi` locale mapping only; Compose UI tests remain Phase 5.

### Phase 5 status (complete)

Landed: release build fixed (scoped `ExpiredTargetSdkVersion` lint disable with the reason,
R8 + resource shrinking, `app/proguard-rules.pro`, unsigned release APK at ~1.4 MB),
`versionCode 40` / `versionName 2.0.0` so the fork upgrades over upstream, five Compose
instrumentation tests compiled into the debug test APK, and the release documentation set:
`TESTING.md`, `COMPATIBILITY.md`, `THIRD_PARTY_NOTICES.md`, `RELEASE_NOTES.md`. The layout
checker now also guards build identity, permissions and manifest component exports.

Verification: `tools/check_layout_profiles.py` PASS (8 checks), 63 JVM tests 0 failures,
`:app:assembleDebug`, `:app:assembleDebugAndroidTest` and `:app:assembleRelease` all
green; `aapt2` confirms `com.reed.fcmguard`, `targetSdk 22`, version 2.0.0/40, the four
permissions, the three components and `vi` in the packaged locales for both APKs.

Deviations and residual risks:

1. **Instrumentation tests were compiled, not executed.** No device or emulator is
   available here. `TESTING.md` §5 is the unexecuted device matrix.
2. **The release APK is unsigned.** No signing material exists in the repository; CI signs
   the debug APK only. Distribution must reuse the key existing installs trust.
3. **Android 14+ refuses new installs of `targetSdk 22`.** Surfaced in
   `COMPATIBILITY.md` §2.1 rather than papered over. Fixing it means replacing the write
   mechanism, which is a product decision outside this phase.
4. **R8 output is build-verified only.** Shrinking succeeded and packaging was inspected;
   a runtime smoke test is still owed.
5. **No screenshots or visual review.** The UI was not rendered in this environment, so
   visual polish and on-device layout remain for the device pass.

Deviations from the original plan, and why:

1. **Groovy Gradle DSL kept.** `build-apk.yml` parses `versionName "x.y.z"` out of `app/build.gradle` with `sed` to derive the release tag. Migrating to `.kts` would silently break release automation unless the workflow changed in the same commit; that is a separate, reviewable change.
2. **Compose dependencies not added yet.** No Compose UI exists until Phase 2/3; adding the toolchain now would ship unused runtime weight. `useAndroidX` is already flipped so the migration is unblocked.
3. **Kotlin stdlib adds ~810 KB to the debug APK** (113 KB → 927 KB). Expected; R8 with a proper keep-rule set lands in Phase 5.

---

## 0. Decision gate (resolved)

The specification describes a Vietnamese system-localization engine for *other* apps' APK resources. The repository is a no-root FCM watchdog with no localization engine (§0 of the audit). These are not the same product, and the difference is not cosmetic — the localization engine needs a privileged backend this project deliberately does not have.

| Option | What it means | Cost | Risk |
| --- | --- | --- | --- |
| **A. FCMGuard modernization** (recommended) | Kotlin + Compose + M3 Expressive + full Vietnamese UI + verified, backed-up system-setting changes + operation history | Large but bounded; every phase is shippable | Low: no new privilege class, behavior preserved |
| **B. Add Việt hóa engine now** | New APK-resource patch subsystem + root/Shizuku backend, FCM watchdog preserved as-is | Very large; needs device testing | High: new privilege class, signature/APK-patching risk, unverifiable on this host |
| **C. Hybrid (A, then B)** | Ship A through Phase 4, then an opt-in Việt hóa module with its own documented risk model | A now, B later | Lowest cumulative: B only starts once A is proven |

Phases below assume **A**, and are written so that **B** can be attached later without rework (the execution abstraction in Phase 2 is the seam).

---

## 1. Non-negotiable invariants (checked at every phase exit)

1. `applicationId` = `com.reed.fcmguard`, `targetSdk` = 22, the four permissions, and the three manifest components with unchanged `exported` values.
2. Repair semantics unchanged: preserve existing packages, order-preserving, append-only, single write, no write when already present.
3. Defaults unchanged: `MILLET_NO_RESTRICT_APP` / `com.google.android.gms`.
4. `FcmReconnect` only after a real, verified change or an explicit user action.
5. No root, no Shizuku, no ADB, no shell, no `QUERY_ALL_PACKAGES`.
6. `UNKNOWN` remains a real Autostart state; never coerced.
7. No `AlarmManager`/`WakeLock` introduced for the fallback.
8. No fabricated status, no non-functional control, no placeholder operation.
9. Existing 10 locales keep working; `vi` is added, never substituted.
10. SharedPreferences keys preserved or explicitly migrated: `guard_state`, `enabled`, `persistent_notification`, `settings_key`, `required_item`, `ui_language`, `appearance_mode`, `last_good_*`.

Verification command for every phase: `python3 tools/check_layout_profiles.py` + `gradle :app:assembleDebug` + the phase's tests.

---

## 2. Phase 1 — Kotlin and architecture foundation

*No user-visible change. The app must behave identically.*

Deliverables
- `android.useAndroidX=true`; Kotlin 2.0.x + Compose compiler plugin; AGP pinned at 8.6.1; Gradle wrapper 8.7 committed; `libs.versions.toml`.
- Dependencies pinned to versions compatible with AGP 8.6.1 / compileSdk 35 / minSdk 21.
- Package skeleton per spec §9, adapted: `core/`, `data/`, `domain/`, `execution/`, `ui/`, `di/` — created only as each is used, not as empty ceremony.
- `SettingsRepository` interface + `SharedPreferences` implementation; `OperationResult` sealed type replacing `SettingsGuard.Result`.
- Java → Kotlin conversion **class by class, in this order**: `BootReceiver`, `FcmReconnect`, `HyperOsSettings`, `AutostartStatusReader`, `FcmAppScanner`, `LocaleHelper`, `ThemeHelper`, `SettingsGuard`, then `GuardService`, then the custom views and `MainActivity` (which Phase 3 rewrites anyway).
- First tests: normalization/parse/join behavior, `hasRequiredItem`, locale normalization.

Exit criteria
- `assembleDebug` green; APK identity identical to the baseline; layout checks pass.
- Unit tests for `SettingsGuard` logic (with a fake content resolver) pass.
- No `GlobalScope`, no new main-thread I/O.

---

## 3. Phase 2 — Execution abstraction, verified operations, snapshot/restore ✅

*The genuine safety upgrade. This is where spec §7 becomes real for the setting Mamori actually modifies.*

Deliverables
- `PrivilegeManager`: distinguishes *permission missing* / *permission revoked mid-operation* / *write rejected* / *write failed*.
- `SystemSettingsGateway`: read, write, **read-back verification**; no success without confirmation.
- `OperationStateMachine`: `IDLE → VALIDATING → SNAPSHOTTING → WRITING → VERIFYING → COMPLETED | PARTIALLY_COMPLETED | FAILED | CANCELLED | ROLLING_BACK`, with a UUID per operation, persisted so interrupted runs are detectable at next start.
- `SnapshotStore`: pre-write snapshot of the whitelist value + SHA-256, metadata (key, timestamp, operation id, original value), integrity check, and **restore** action.
- Remove the fabricated fallback list (`com.tencent.mm`, `com.android.vending`); replace with: preserve current value; if empty, ask the user. Documented behavior change.
- All privileged work off the main thread; a single serialized executor prevents concurrent writes to the same key.
- Tests: state transitions, idempotency, verification failure, restore, interrupted-operation detection — all against a fake gateway.

Exit criteria
- Every write is verified by read-back; failures surface as failures.
- Restoring a snapshot is a shipped, tested feature.
- Killing the process mid-operation produces a detectable, user-confirmable recovery state — never silent auto-repair.

---

## 4. Phase 3 — Material 3 Expressive UI ✅

Deliverables (in this order)
1. Theme: dynamic color (light/dark), explicit static fallback palette, typography and shape scales, motion respecting system animation scale.
2. Shell: `ComponentActivity` + Navigation Compose; bottom navigation for Home / FCM apps / History / Settings.
3. Home: real status (permission, protection, item present, mode), whitelist value with chips, quick actions.
4. FCM app assistant: `LazyColumn`, scan + AppOps probe off the main thread, per-state badges, `UNKNOWN` presented honestly.
5. Settings: appearance, language, localization/backup preferences, advanced, about (attribution + license).
6. Sticky status card as a Compose scroll effect, ported last and verified against the current geometry (audit §13.3). Fallback: keep `CollapsingStatusCard` inside `AndroidView` if fidelity fails — behavior beats purity.
7. Locale/theme on `AppCompatDelegate` + `LocaleManager`, preserving the pre-33 path and the legacy-preference migration.

Exit criteria
- All screens in Compose, M3 Expressive, dynamic color on/off tested.
- 320–480 dp and landscape verified; contrast and touch targets pass; screen-reader labels present.
- Locale switch works on both API 33+ and below.

---

## 5. Phase 4 — Vietnamese localization, history, errors ✅

Deliverables
- `values-vi`: complete, correct diacritics, terminology from spec §11 (`Việt hóa hệ thống`, `Sao lưu`, `Khôi phục`, `Mức độ hoàn thiện bản dịch`, `Tính tương thích`, `Lịch sử thao tác`).
- `vi` added to `locales_config.xml`, `LocaleHelper.normalize`, and the language picker; Vietnamese becomes the default for `vi-*` devices.
- `LANGUAGE_BUTTON_LABELS` and other hardcoded UI strings moved into resources.
- Operation history screen backed by persisted records: id, type, target, timestamp, result, snapshot reference, error detail; failed records inspectable.
- Error model: human-readable message + technical detail + suggested action; a diagnostics screen (backend/permission state, device ROM info, recent log ring buffer).
- Logging introduced (ring buffer + optional export), ending the `catch (Throwable ignored)` pattern.

Exit criteria
- vi-VN and en-US complete; no hardcoded user-facing strings (verified by grep).
- Every operation produces a history record; every failure is explainable.

---

## 6. Phase 5 — Hardening and release ✅

Deliverables
- Interrupted-operation recovery UI; storage-exhaustion and permission-revocation handling.
- Instrumentation tests (navigation, search, confirmation dialogs, progress/error states, theme switching, Vietnamese rendering).
- R8 config, signed release, reproducible build notes, `ARCHITECTURE.md`, `TESTING.md`, `COMPATIBILITY.md`, release notes, upstream attribution + OSS notices.
- Device regression matrix documented; destructive paths tested only against a verified recovery path.

Exit criteria
- Release build succeeds; invariants §1 hold; no known data-loss or restore failure.
- Compatibility limitations (audit §12) documented as limitations, not papered over.

---

## 7. Open items to confirm before Phase 3

1. **Product naming**: keep `FCM Guard` as the displayed name, or rename to `Mamori`? Renaming affects `app_name` in 10 locales and the README; it does not affect `applicationId`.
2. **Fabricated fallback removal** (Phase 2): confirm replacing the `com.tencent.mm` / `com.android.vending` seed with an explicit user prompt — it is a behavior change, though a safer one.
3. **Scope B timing**: whether a Việt hóa engine is planned at all, which decides whether Phase 2's `ExecutionBackend` seam needs to accommodate a future root/Shizuku backend.
