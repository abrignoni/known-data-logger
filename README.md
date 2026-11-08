# Known-data logger

An Android app for test phones. You do something on the phone (send a message, take a call, take a
photo), tap Log, and the app records what you did and when. At the end you export a log that goes in
the `documentation/` folder next to the extraction, so anyone validating a parser can check each
action against the tool output.

It is meant for test devices only. Never install it on an evidence device.

## What it records

Each entry gets the device clock (UTC ms), the time zone, the time since boot, the boot count from
`Settings.Global.BOOT_COUNT`, and the system's last network and GNSS time when the phone has them.
The time since boot and the boot count keep the order right when you change the clock or reboot as
a test step. The app also notices those changes on its own and logs them.

Edits never overwrite. Each edit is a new revision with the original action time, and removing an
entry writes a tombstone.

There is no field for passwords or passcodes. Keep those in your private notes.

## Footprint

- No permissions. The manifest has a `<queries>` element for launcher apps so you can pick the apps
  under test.
- No receivers, services, providers or notifications. No network access.
- Backup is off (`allowBackup="false"`, backup and data extraction rules exclude everything).
- The database (`databases/kl.db`) uses a rollback journal, not WAL. Its layout is in
  [docs/DATABASE.md](docs/DATABASE.md).
- Every entry is also appended to `Android/data/com.abrignoni.knowndatalogger/files/actions-live.jsonl`.
- An export creates a folder you pick, so it leaves MediaStore entries. The export is logged.

The package name is fixed (`com.abrignoni.knowndatalogger`) so you can filter the app out of
usage and recent-task artifacts.

## Export

A folder named `kdl_<scenario>_<device>_<UTC time>` with:

- `manifest.json`: device profile snapshots, apps under test (version when added and at export),
  checker tolerances, export time, and the sha256 of `actions.jsonl`.
- `actions.jsonl`: every row, every revision, one JSON object per line.
- `actions.csv`: the same rows with local and UTC time columns and an `is_current` column.

Field definitions are in [docs/SPEC.md](docs/SPEC.md).

## Build

Builds locally, no CI. Needs JDK 17 and the Android SDK with platform 37.2.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`. Install with `adb install`.

## Status

Version 0.1.0, Android only. Tested on an Android 15 emulator. Not yet tested on a physical phone.
iOS and a checker script that reads the export against LEAPP output are planned.

Author: Alexis Brignoni
