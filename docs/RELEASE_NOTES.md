# Mamori release notes

## 2.0.0 — first Mamori release

`versionCode 40`, `versionName 2.0.0`, `applicationId com.reed.fcmguard` (unchanged).

### New

- **Compose + Material 3 Expressive UI.** All screens rewritten in Jetpack Compose:
  dashboard, FCM app assistant, operation history, settings, diagnostics. The Java UI,
  custom views and XML dashboard layouts are gone.
- **Dynamic color** with a static fallback palette; light, dark and system modes apply
  immediately.
- **Vietnamese (`vi`)** is a first-class locale: complete `values-vi`, a language picker
  entry, and locale-config wiring. Ten inherited locales keep working.
- **Operation history.** Journaled records are visible, expandable and show the backup
  reference and captured detail.
- **Diagnostics.** Read-only facts about the write mechanism, permissions, stored backups,
  device/ROM and the log, with share and clear.
- **Structured errors.** Every failed, partial or unverifiable operation shows a recovery
  suggestion and its technical detail, with a link to history.
- **Language manager.** A dedicated screen reads the system language list, changes it
  through the platform's own system-locale store (verified against
  `LocaleManager.getSystemLocales()`), captures the original list before the first change,
  restores it on request, and lists per-app overrides with search and filtering. Mamori's
  own app language is writable; other apps' languages are read-only because Android has no
  public API to write them. See `ARCHITECTURE.md` §7 and `COMPATIBILITY.md` §4.1.
- **Logging.** 300-entry in-memory ring plus a rolling 128/64 KB file on a background
  thread; systematic probe failures are logged once.

### Changed

- FCM app scanning and the vendor Autostart probe run off the main thread.
- The scroll-linked status card is replaced by a pinned status strip; the full detail card
  scrolls. Same information, standard scrolling.
- The rotating language-button labels are removed; language lives in Settings with the
  current value always visible.
- Release builds use R8 and resource shrinking (release APK ~1.4 MB).

### Compatibility and upgrade notes

- `applicationId` is unchanged, so an existing FCMGuard install upgrades in place —
  **provided the APK is signed with the same key**. Release signing is external to this
  repository; the CI workflow builds the debug-signed APK only.
- `versionCode` is now 40 (upstream 30), so this is an upgrade, not a downgrade.
- Android 14+ blocks **new** installs of APKs targeting API 22; see
  `COMPATIBILITY.md` §2.1. Verify upgrade-on-install before distributing.
- The app still targets `targetSdk 22` on purpose: the vendor `Settings.System` write
  depends on the legacy path.

### Preserved from upstream

No-root/no-Shizuku execution, the `MILLET_NO_RESTRICT_APP` write with append-only repair,
snapshot-before-write and verified read-back, the empty-whitelist confirmation, the
interrupted-operation recovery gate, the 30-minute non-waking fallback, and the FCM
reconnect kick after a real repair.

## 1.5.9 and earlier

Upstream FCMGuard-HyperOS releases. See the upstream repository's release history.
