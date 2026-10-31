# Backup and rollback

Scope: the single setting Mamori modifies — the comma-separated whitelist under `Settings.System` (default key `MILLET_NO_RESTRICT_APP`). Nothing else in the system is written.

## 1. The pipeline

Every privileged change runs through one path, off the main thread, on a single serialized executor:

```
journal.start(operation)          → VALIDATING   (persisted before anything else happens)
canWrite?                         → no ⇒ PermissionMissing, no journal entry
read current value                → fail ⇒ FAILED
required item already present?    → yes ⇒ COMPLETED (already_correct), no write
whitelist empty & not confirmed?  → yes ⇒ CANCELLED (confirmation_required), no write
snapshot previous value           → fail ⇒ FAILED (snapshot_failed), write abandoned
write                             → rejected/throws ⇒ FAILED
read back                         → mismatch ⇒ PARTIALLY_COMPLETED (verification_failed)
                                  → match    ⇒ COMPLETED
```

`PARTIALLY_COMPLETED` is the honest state for "the platform said yes but the value did not change". It is never reported to the user as success.

## 2. Snapshots

A snapshot is the previous value of the setting, captured immediately before the write.

| Property | Value |
| --- | --- |
| Location | `filesDir/snapshots/<uuid>.snapshot` |
| Format | Line-based text, URL-encoded fields, fixed-format header `mamori-snapshot/1` |
| Integrity | SHA-256 over `header, id, operationId, key, createdAt, value`, stored inside the file |
| Atomicity | Written to `<uuid>.tmp`, then renamed into place |
| Retention | Newest 20 kept; older files deleted on the next save |
| Verification | Digest recomputed on load. A mismatching snapshot is never offered or written |

A snapshot always contains a value that existed. If the setting was absent or empty,
no snapshot is taken — Mamori has no verified way to delete a system setting, so
"restore to absent" is not offered rather than pretended.

## 3. Restore

Restore writes a snapshotted value back through the same verified pipeline:

1. Snapshot must exist and pass its digest check; otherwise `NoSnapshot` / `SnapshotCorrupt`.
2. `WRITE_SETTINGS` must be granted (checked before any journal entry).
3. The **current** value is snapshotted first, so the restore itself can be undone.
4. The write is verified by read-back; a mismatch is reported as `restore_unverified`.

A restore that finds the value already identical completes without writing.

## 4. Interrupted operations

The journal writes a record before any privileged call and clears it only on a terminal
state. If the process dies mid-operation, the record survives.

On the next start:

- The dashboard detects the leftover record and shows it (time, last state, key).
- **No repair happens automatically** while a record is pending — `RepairEngine` returns
  `RecoveryRequired` for every attempt, including background ones.
- The user either restores the saved value (when the record carries a snapshot id) or
  dismisses it, which records the operation as `INTERRUPTED` in history and clears the
  blocker.

This is deliberate: silently repairing after a crash can overwrite a value the user set
on purpose.

## 5. Operation history

Every attempted operation is stored in `SharedPreferences` (`guard_state`):

| Key | Content |
| --- | --- |
| `operation_active` | The in-flight record, removed on completion |
| `operation_history` | Newest-first list, capped at 50 |

Fields: `id`, `state`, `key`, `startedAt`, `updatedAt`, `detail`, `snapshotId`.
States: `VALIDATING`, `SNAPSHOTTING`, `WRITING`, `VERIFYING`, `ROLLING_BACK`, `COMPLETED`,
`PARTIALLY_COMPLETED`, `FAILED`, `CANCELLED`, `INTERRUPTED`.

`detail` carries a stable technical token (`write_rejected`, `verification_failed`,
`already_correct`, `confirmation_required`, `read_failed:<cause>`, …) so a later
diagnostics screen can explain a failure without parsing prose. History is recorded but
not yet displayed; the history screen is scheduled for Phase 4.

## 6. Behavior change from upstream

Upstream seeded an empty whitelist with `com.tencent.mm` and `com.android.vending`.
Mamori no longer does. When the effective list is empty, the engine returns
`ConfirmationRequired` and the dashboard asks before writing, then writes **only** the
required item. Background callers never confirm on the user's behalf, so a service tick
cannot invent packages.

## 7. Known limits

- No verified deletion of a setting, so an absent original cannot be restored to absent.
- Restore targets one key at a time; there is no whole-device or multi-key snapshot.
- Snapshot files are not encrypted; they contain package names only, not personal data.
- All of this is unit-tested against a fake gateway. **No device was attached during
  development**, so on-device write/verify/restore behavior on HyperOS is still
  unverified; see `REPOSITORY_AUDIT.md` §17 for the outstanding runtime checks.
