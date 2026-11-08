# kl.db layout (schema 1)

Path on the device: `/data/data/com.abrignoni.knowndatalogger/databases/kl.db`.
`PRAGMA user_version` holds the schema number. Journal mode is DELETE (no `-wal`).

## settings

| column | meaning |
|---|---|
| key | `scenario_id` or `device_label` |
| value | text typed by the user |

## profiles

One row per device profile snapshot. A new snapshot is added, never overwritten.

| column | source |
|---|---|
| id | autoincrement |
| utc_ms, elapsed_ms, boot_count | clocks at the snapshot |
| manufacturer, model, device, fingerprint | `android.os.Build` |
| release, incremental, sdk_int, security_patch | `Build.VERSION` |
| kernel | `System.getProperty("os.version")` |
| baseband | `Build.getRadioVersion()` |
| carrier, notes | typed by the user |

## apps

Apps under test. Version fields are read from PackageManager when the app is added.

| column | meaning |
|---|---|
| package | package name (primary key) |
| label | launcher label |
| version_name, version_code | at the time it was added |
| first_install_ms, last_update_ms | `PackageInfo.firstInstallTime`, `lastUpdateTime` |
| account | handle typed by the user |
| added_utc_ms | when it was added to the list |

## entries

Every row the app writes, including revisions, tombstones and system rows. Columns match the
`actions.jsonl` fields in [SPEC.md](SPEC.md), except `boot_wall_ms`, which is computed
(`utc_ms - elapsed_ms`) and not stored, and `expected_artifacts`, stored as a JSON array in text.

The current version of an entry is the row with the highest `revision_n` for its `entry_id`.
If that row has `kind = 'tombstone'`, the entry was removed. Order by
`boot_count, elapsed_ms, seq`.
