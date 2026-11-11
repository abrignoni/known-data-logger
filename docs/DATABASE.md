# kl.db layout (schema 4)

Path on the device: `/data/data/com.abrignoni.knowndatalogger/databases/kl.db`.
`PRAGMA user_version` holds the schema number. Journal mode is DELETE (no `-wal`).

## settings

| column | meaning |
|---|---|
| key | `scenario_id`, `device_label`, or one of the app's own state keys below |
| value | text typed by the user, or the state value |

State keys: `onboarded`, `capture_session_active`, `session_start` and `window_start` (clock
readings as JSON), `capture_all_apps`, `shot_on_auto_action`, `shot_on_every_tap`,
`mask_notification_codes`, `mark_y` (where MARK was left), `known_version:<package>`,
`export_tree` (the export folder picked last, as a content URI), and `session_hand_only` (1 while
a session started without capture is running).

Start over in the app empties `entries`, `screenshots` and `profiles`, resets their row numbering,
removes `session_start`, `window_start` and the `known_version:` keys, and rebuilds the file with
`VACUUM`. The other settings and `apps` stay.

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

## screenshots (schema 2)

| column | meaning |
|---|---|
| entry_id | the entry the screenshot belongs to (its original row) |
| file | PNG name under the app's private `files/screens/`, null when none was taken |
| sha256 | hash of the PNG computed when it was written |
| status | `ok`, `blank` (every sampled pixel black or near black), `failed:<reason>` or `unsupported` (Android 10 and earlier) |
| utc_ms | when the screenshot row was written |

## view_trees (schema 4)

One row per view tree saved with a screenshot: `entry_id` (the entry the screenshot belongs to),
`file` (name in the app's private `files/trees/` folder, empty when none was saved), `sha256`,
`status` (`ok`, `ok_cut_at_6000_nodes`, `not_captured:<why>` or `failed:<reason>`) and `utc_ms`.
Start over empties it.

## entries

Every row the app writes, including revisions, tombstones and system rows. Columns match the
`actions.jsonl` fields in [SPEC.md](SPEC.md), except `boot_wall_ms`, which is computed
(`utc_ms - elapsed_ms`) and not stored, and `expected_artifacts`, stored as a JSON array in text.

Schema 2 added `source` (`manual`, `auto_event`, `auto_rule`, `mark`, `system`) and `reviewed`
(0 only for auto-labelled actions nobody has accepted). Rows from schema 1 read as manual and
reviewed.

Schema 3 added `prev_hash` and `row_hash`, the hash chain described in SPEC.md.
Schema 4 added the `view_trees` table and no column to `entries`.

The current version of an entry is the row with the highest `revision_n` for its `entry_id`.
If that row has `kind = 'tombstone'`, the entry was removed. Order by
`boot_count, elapsed_ms, seq`.
