# Known-data logger: spec, revision 5 (2026-11-08)

Revision 2 folded in an independent review (2026-11-08). Revision 3 matches version 0.1.0 as built. Revision 4 added the plan for version 0.2; revision 5 matches 0.2.0 as built. API claims below were checked
against the Android SDK 36 stubs where marked "(stubs)".

A small app installed on a test device. The researcher records each action as they do it.
The app exports a machine-readable log that sits in `documentation/` beside the extraction,
and that can be checked against LEAPP output.

Decided: native Kotlin, Android only for version 1, sideloaded APK, private repo under
abrignoni/, built locally (no GitHub Actions).

## Why, and what exists

Known data today is a hand-kept document. Joshua Hickman's published images (Pixel3_A11,
Pixel3_A12 on Corpus) use a PDF: phone information, OS version information, accounts, then
per app the name, version, install date and time, username, a note, and a table of actions
(date, time to the minute, action). The Pixel 8 Pro log is a spreadsheet with free-form blocks
side by side. Both read well, but no script can read them, times are minute precision and typed
by hand, and the time zone is a sentence in the header.

Prior work found (web search, 2026-11-08) automates the phone instead of logging a person:
Puma (Android scripted interaction), Demmel, Gobel, Goncalves, Baier (2024, recorded GUI
playbooks), AutoPodMobile (iOS data population), TraceGen (2021, activity emulation). None is a
manual logger. This app covers what automation does badly: real calls, camera with location,
cross-device messaging, travel, power and SIM events. The playbook format has not been read;
compatibility with it is an open question, not a feature.

## Screens (version 1)

1. **Scenario and device.** Scenario id (shared by every phone in one test) and a device label
   ("phone A"). Device profile filled from the APIs (Build.FINGERPRINT, VERSION.RELEASE,
   VERSION.INCREMENTAL, VERSION.SECURITY_PATCH, model, kernel, baseband), carrier and notes typed.
   Refresh adds a new profile snapshot after an OS update; it never overwrites.
2. **Apps under test.** Pick from launcher apps; records package, version name, version code and
   install time from PackageManager. Account handle typed.
3. **Log an action.** Pick app and action (template or free text), then content, counterpart,
   direction, conversation and note. Timestamp taken at the tap. Fast-sequence support:
   "stamp now, describe later" with a visible queue of unfilled entries, and a repeat-last button.
   Calls and recordings get Start and Done taps that make one entry with a window.
4. **Timeline.** List, edit, annotate. Edits are revisions, never overwrites.
5. **Export.** Manual export to a folder picked with the system file picker (SAF).

## Time model

Each entry stores:

- `utc_ms`: the device's own wall clock, epoch ms. Deliberately the device clock, because that is
  what the parsers report. The independent references below show how far off it was.
- `tz_id`, `tz_offset_min`: read fresh at each tap (ZoneId.systemDefault() on every call).
- `elapsed_ms`: `SystemClock.elapsedRealtime()`, includes deep sleep, monotonic within a boot.
- `boot_count`: `Settings.Global.BOOT_COUNT` (stubs: constant "boot_count"; API 24; read without a
  permission per the Settings.Global page). A `boot_count` setting is present in
  `settings_global.xml` on the Pixel3_A11 corpus image (read with an XML parser). Android 12+
  images store that file as binary XML and were not checked. Ordering key is (boot_count,
  elapsed_ms, seq).
- `boot_wall_ms` = utc_ms minus elapsed_ms. Within one boot_count it is constant unless the clock
  was set; a jump between two entries is a clock change between them, detected with no receiver.
- `network_time_ms`: `SystemClock.currentNetworkTimeClock()` (stubs; API 33): last network time
  sync held by the system, no network traffic from the logger. Null when unavailable.
- `gnss_time_ms`: `SystemClock.currentGnssTimeClock()` (stubs; API 29). Null when unavailable.
- `time_quality`: exact, after, before or approximate, and `window_s` when the act spans time.

There is no network time check and no INTERNET permission.

Clock and time zone changes done as test steps are logged as actions by the researcher. The app
also logs a detected change as its own row and shows a short message; it does not ask for a note.

Checker tolerance defaults, written into every manifest so a checker does not have to guess:
120 s for `exact`, 300 s for `before` and `after`, 900 s for `approximate`, and the window for
windowed entries. These are chosen defaults, not measured values.

Detected changes are logged as their own rows before the entry that revealed them: a boot count
change (`system_event`, action `boot_detected`), a boot_wall_ms jump over 2 s within one boot, or
a time zone change (`clock_change`, action `clock_change_detected`).

## Footprint on the device under test

- Permissions: none. Manifest declares a `<queries>` element for MAIN/LAUNCHER intents, needed
  on Android 11+ to list launcher apps (package visibility), without QUERY_ALL_PACKAGES.
- `android:allowBackup="false"` plus dataExtractionRules excluding everything, so the log never
  goes to Google backup.
- SQLite in journal_mode=DELETE, so an extracted database has no live -wal.
- No receivers, services, notifications, analytics or crash reporting libraries.
- Fixed, documented package name so examiners can filter the logger out of usagestats, recent
  tasks and similar artifacts.
- Every entry is also appended to a JSONL file in the app-specific external files directory
  (no permission, no MediaStore rows, reachable with `adb pull`). This survives an app crash; it
  does not survive a factory reset.
- Logging a factory-reset action shows a reminder, logs the entry, then opens the Export tab. A manual export to Downloads creates
  MediaStore rows; each export is logged as its own entry.
- The app's own database is evidence too: schema versioned from day one and documented, so a
  later ALEAPP module can parse it.

## No secrets

No password, passcode or credential field. Exports go beside extractions and get shared.
Passcodes and test-account passwords stay in the private documentation, by hand. The note and
content fields carry a warning saying so.

## Export format, version 1

`manifest.json`: schema version, logger package and version, logger install time, scenario id,
device label, device profile snapshots, apps under test, checker tolerances, export utc_ms and
elapsed_ms, final boot_count, sha256 of actions.jsonl.

`actions.jsonl`, one object per row:

| field | meaning |
|---|---|
| seq | database autoincrement, unique per device |
| entry_id, revision_n | stable id of the entry; 0 for the original, then 1, 2, ... |
| kind | action, system_event, clock_change, export, note, tombstone |
| scenario_id, device_label, profile_id | which test, which phone, which profile snapshot |
| utc_ms, tz_id, tz_offset_min, elapsed_ms, boot_count, boot_wall_ms | time of the action (fixed across revisions) |
| network_time_ms, gnss_time_ms | independent references, nullable |
| time_quality, window_s | how the tap relates to the act |
| edited_utc_ms | when this revision was written |
| app_package, app_version, account | app under test |
| action | template key or free text |
| content | exact text sent, filename or caption |
| counterpart, direction, conversation_id | other party, in or out, thread label |
| refers_to | entry_id of an earlier entry (a deletion points at what it deleted) |
| expected_artifacts | list of `core:artifact_key`, the `__artifacts_v2__` key, e.g. `aleapp:telegram_messages` (format only; example key not checked) |
| detail | free note |

The current version of an entry is the highest revision_n; a tombstone revision removes it.

`actions.csv`: same rows flattened, local time and UTC in separate columns.

This format is its own. It is not `sample_data` (per-module row counts) and claims no
compatibility with any other tool.

## Version 0.2: automatic capture and screenshots

Goal: install the app, turn it on, and use the phone normally. Actions are captured as they happen,
with a screenshot at the moments that matter. Manual entry stays for what the screen cannot show.

### Capture

An accessibility service records, with the same time fields as every other entry:

- foreground app and screen title changes (window state events)
- taps, with the tapped element's text, content description and view id
- typed text, recorded once as the field's final value, never for password fields
  (`isPassword`), never for the logger's own screens. A field showing its hint is recorded as
  empty (an empty field reports its hint as its text).
- system UI events (quick settings toggles such as airplane mode)

Capture runs only between Start session and Stop session, both logged. A package allowlist
(apps under test, system UI, Settings) limits what is recorded. Raw rows use the new kind
`auto_event`.

### Marking and labelling

- A floating mark button (accessibility overlay, no extra permission) stamps "this is the
  action" without leaving the app under test. The mark carries the app last seen in the
  foreground.
- Per-app rule files turn raw events into action keys. A rule matches one event type, `tap` or
  `field_cleared`, by package plus view id and/or a label regex. `field_cleared` means one text
  change removed all of a field's text (two or more characters); it is the signal for sends in
  Jetpack Compose apps, which report no tap events (seen with Google Messages on the emulator).
  Rules are data files, so they can change without a new APK. Shipped rules: Google Messages
  send (field_cleared) and AOSP Camera shutter (tap), each checked on the emulator.
- A review screen lists marked moments and unlabelled events; the tester accepts or labels
  them before export. Accepted labels are normal `action` rows with `refers_to` pointing at the
  raw event.

### Screenshots

- Taken by the accessibility service (`AccessibilityService.takeScreenshot`, API 30+): no extra
  permission, no consent prompt, no recording indicator, and it keeps working after a reboot.
  On Android 8 to 10 the feature is off and the app says so.
- Triggers: every tap on the mark button (always), each auto-labelled action (on by default,
  can be turned off), every tap (off by default).
- The system rate-limits screenshots; a refused or failed capture is logged on the entry, never
  dropped silently. Screens of apps that block screenshots come back black; the entry records
  that the frame was empty rather than claiming a picture of the content.
- Stored as PNG in the app's private folder (no MediaStore rows), named after the entry, with
  sha256 computed at capture time and kept in the database.
- New entry field `screenshot`: file name, sha256, capture status, on the entry's original row
  (revision 0). The export copies the files to `screens/` and lists every file in the manifest
  with its hash at capture, its hash at export and whether they match.
- New entry fields `source` and `reviewed`; schema version 2.

### Footprint added in 0.2

- An enabled accessibility service is recorded in the device's secure settings; it is part of
  the test, and the session start and stop rows say when it was active.
- On Android 13+ a sideloaded app needs a one-time "Allow restricted settings" step before the
  service can be turned on (to be confirmed on a device).
- Some apps change behaviour when an accessibility service is on (mostly banking apps).
- Anything that uses UiAutomation (uiautomator, automation tools) pauses all accessibility
  services while it runs; each pause is logged as a disconnect and reconnect.
- Screenshots take space on the test device and are in any full file system extraction.
- Still no INTERNET permission, no receivers, no backup.

### Continuous video (optional, off the phone)

For sessions that need full video, record from the Mac with `scrcpy --record` over USB. Nothing
is written to the test phone. The logger's network time reference is used to align the video
with the log. Needs USB debugging on. Whether protected screens record as black this way is not
yet verified.

### Documentation output

An off-device script reads one or more exports (phone A, phone B) and writes an `.xlsx`: a device
sheet, an accounts sheet, one sheet per app with Date, Time, Action, Content, Counterpart and a
link to the screenshot, using current revisions only and flagging entries that still need
detail.

## Later

- iOS build.
- A checker script in the LEAPP repos that reads the export and LAVA output.
- An ALEAPP module for the logger's own database.
- Template packs per app; a look at the Demmel playbook format.

## Not verified yet

- BOOT_COUNT read without a permission on the Android 15 emulator (values 1, then 2 after a
  reboot). Not yet tried on a physical phone or other Android versions.
