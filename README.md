# Known-data logger

An Android app for test phones. Turn on capture, use the phone as you normally would, and the app
records what you do and when: taps, typed text, screens, and screenshots at the moments that
matter. You can also log actions by hand. At the end you export a log that goes in the
`documentation/` folder next to the extraction, and a script turns it into an `.xlsx` document, so
anyone validating a parser can check each action against the tool output.

It is meant for test devices only. Never install it on an evidence device.

## What it records

Each entry gets the device clock (UTC ms), the time zone, the time since boot, the boot count from
`Settings.Global.BOOT_COUNT`, and the system's last network and GNSS time when the phone has them.
The time since boot and the boot count keep the order right when you change the clock or reboot as
a test step. The app also notices those changes on its own and logs them. Clock and reboot
detection were tried on an Android 15 emulator; time zone detection is covered by unit tests only.

Edits never overwrite. Each edit is a new revision with the original action time, and removing an
entry writes a tombstone.

There is no field for passwords or passcodes. Keep those in your private notes.

## Automatic capture (0.2)

1. Add the apps under test on the Scenario tab.
2. Turn on "Known-data logger" in Settings, Accessibility. On Android 13 and later a sideloaded app
   may first need App info, menu, Allow restricted settings (not yet confirmed on a phone).
3. On the Capture tab, tap Start session. A red MARK button floats over every app.
4. Use the phone. Tap MARK at any moment you want recorded with a screenshot.
5. Stop the session, then go through Timeline, To review: accept or fix auto labels and label marks.

What is captured, only in the apps under test plus System UI and Settings unless you choose all apps:

- screen changes, taps (label and view id), and typed text as the field's final value
- never the content of password fields (tested: an SSID typed in the same dialog was captured,
  the password was not, not even in the database file)
- a field cleared in one step, which is how apps built with Jetpack Compose show a send: they
  report no tap event for their buttons (seen with Google Messages)

Auto-label rules turn events into actions, for example a cleared message field in Google Messages
becomes `sent_message` with the text as content. Rules ship in `app/src/main/assets/rules/` and can
be overridden with a `rules.json` pushed to the app's external files folder. Each shipped rule says
which app version it was checked against. Auto labels stay "to review" until someone accepts them.

Screenshots use the accessibility service's own capture (Android 11 and later): no extra
permission and no prompt (none appeared on the emulator). They are stored in the app's private folder with a
sha256 taken at capture, copied to `screens/` on export and checked again. A black frame from an app
that blocks screenshots is recorded as `blank`.

Known limits:

- Tools that drive the phone through UiAutomation (uiautomator, Puma and similar) pause every
  accessibility service while they run. The log shows each pause as
  `capture_service_disconnected` and `capture_service_connected` rows.
- Some apps behave differently when an accessibility service is on.
- Rules break when an app changes its screens or runs in another language. Review catches it.

For full video, record from a Mac over USB with `scrcpy --record` so nothing is written to the
phone, and line it up with the log using the network time column.

## Footprint

- No permissions. The manifest has a `<queries>` element for launcher apps so you can pick the apps
  under test.
- One accessibility service, which only the system can bind. Turning it on is recorded in the
  phone's secure settings. No receivers, providers or notifications. No network access.
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
- `screens/`: the screenshots, listed with their hashes in the manifest.

## Documentation (.xlsx)

```bash
python3 tools/make_documentation.py kdl_scenario_phoneA_... kdl_scenario_phoneB_... -o documentation.xlsx
```

Give it every phone's export for one scenario. It writes a Scenario sheet (devices, detected clock
changes and reboots, hash checks), an Apps sheet, one sheet per app with each action and a link to
its screenshot, a Needs review sheet and a Captured events sheet. Needs `openpyxl`.

Field definitions are in [docs/SPEC.md](docs/SPEC.md).

## Build

Builds locally, no CI. Needs JDK 17 and the Android SDK with platform 37.2. Gradle finds the SDK
through `sdk.dir` in `local.properties` (not committed) or the `ANDROID_HOME` variable.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest assembleDebug
python3 -m unittest discover -s tools
```

The APK is in `app/build/outputs/apk/debug/`. Install with `adb install`.

## Status

Version 0.2.0, Android only. Tested on an Android 15 emulator, not yet on a physical phone. iOS and a
checker script that reads the export against LEAPP output are planned.

Author: Alexis Brignoni
