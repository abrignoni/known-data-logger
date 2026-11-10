# KDL for iOS (0.2.0)

The iOS version of the known-data logger. iOS gives apps no way to watch other apps' screens, taps,
typed text or notifications. Instead, the tester runs a screen capture, does each action in its app
and marks it (double-tap the back of the phone), then labels each mark from its screenshot. KDL
writes the same rows, hash chain and export folder as the Android app, and every app switch is
logged with the app's bundle id. `tools/make_documentation.py` and the other tools read an iOS
export the same way.

## Workflow

Once per phone: Setup (scenario id, device label, device snapshot), and a Shortcut that runs KDL's
Mark set as Settings > Accessibility > Touch > Back Tap > Double Tap.

Each test:

1. **Session** screen: start screen capture (round button, KDL Screen, Start Broadcast). This
   starts the session.
2. Do each action in its app. When it is done, double-tap the back of the phone. A "Marked" banner
   confirms it; the mark gets a screenshot and the app in front.
3. Stop the capture (round button or the red indicator). This ends the session.
4. **Review** screen, To label: each mark shows its screenshot and app. Pick the action, type the
   text that matters (content, counterpart), Save; the next mark opens. A label's time is the
   mark's, with time quality "after" (the mark comes right after the act).
5. Review lists the apps used that have no version yet (iOS cannot read other apps' versions).
6. Export, at the bottom of Review.

## What it does

- **Session**: start and stop screen capture, with live counts of marks, screenshots and apps.
  "Log something with no screenshot" logs events the screen does not show (a restart, airplane
  mode, a SIM swap, a call while locked), stamped when tapped, plus Start timing / Done for calls
  and recordings.
- **Review**: To label (unlabelled marks and stamped entries, oldest first) and Everything (newest
  first; a labelled mark is shown through its label, app switches read "Switched to Notes").
  Labelling a mark adds an action row at the mark's time that refers to it. Edits add a revision;
  removing adds a tombstone. Tap a screenshot to zoom.
- **Export** (bottom of Review): writes `kdl_<scenario>_<device>_<UTC time>/` with
  `manifest.json`, `actions.jsonl`, `actions.csv` and `screens/` to On My iPhone > KDL > Exports in
  the Files app, and offers the share sheet. Each export is logged as its own row first.
- **Setup** (gear on Session; opens by itself until the scenario id and device label are set):
  scenario and device, device snapshots, apps (optional; name, version, account), and
  Diagnostics (clocks, latest row hash).
- **Mark** runs from Back Tap or Shortcuts (with a "Marked" banner), a Home Screen widget, or a
  Control Center or Lock Screen control (iOS 18). Marking from Control Center or the Lock Screen
  puts that overlay in the screenshot; Back Tap does not.
- App names shown for bundle ids come from a small built-in table of Apple apps (display only;
  the log keeps the bundle id). Four of those ids were seen on the test phone; the rest are not
  checked on a device.

## Screen capture

The tester starts it from the Log tab (Screen capture, round button, KDL Screen, Start Broadcast).
It is a ReplayKit broadcast upload extension, `com.abrignoni.knowndatalogger.broadcast`. iOS shows a
red indicator while it runs.

- It keeps only the latest video frame. Every Mark, from wherever it is tapped, posts a Darwin
  notification; the extension then saves the latest frame as a PNG named after the mark, in the App
  Group `Library/KDL/screens/`, and records its SHA-256 and status (`ok`, `blank` when every sampled
  pixel is near black, or `failed:<reason>`) in the `screenshots` table. The export copies the files
  to `screens/` and checks each hash again, as on Android.
- Each app switch ReplayKit reports becomes an `auto_event` row, action `screen`, with the bundle
  id in `app_package`. ReplayKit does not report the Home Screen.
- While capture runs, a Mark carries the bundle id of the app last reported in front, so a label
  made from it lands on that app's sheet in the spreadsheet.
- Starting the broadcast logs `capture_session_start` and stopping it logs `capture_session_stop`,
  whose detail gives the number of screenshots, failures and app switches, the largest gap between
  a mark and its frame, and the extension's peak memory footprint. If iOS ends the extension without
  telling it, the app logs the stop the next time it is opened and the screen is not being captured
  (`UIScreen.isCaptured`); that path has not been exercised.
- Marking from Control Center or the Lock Screen puts that overlay in the screenshot. Back Tap
  (Settings > Accessibility > Touch > Back Tap, set to a Shortcut that runs KDL's Mark) leaves the
  screen alone. Run from Shortcuts or Back Tap, Mark shows a "Marked" banner with the time.

Apple marks the broadcast classes "No longer supported" from iOS 27, where ScreenCaptureKit takes
over. This route was tested on iOS 26.6 only.

## Clocks

Measured on one iPhone 13 with iOS 26.6 (Phase 0, 9 November 2026):

| Field | iOS source |
|---|---|
| `utc_ms`, `tz_id`, `tz_offset_min` | the device clock and time zone, read at each tap |
| `elapsed_ms` | `mach_continuous_time`, which kept counting while the phone slept; `ProcessInfo.systemUptime` did not |
| `boot_count` | always -1. No boot counter was found, and `kern.bootsessionuuid` was refused |
| `network_time_ms`, `gnss_time_ms` | always null; no iOS source |

A restart is logged (`boot_detected`) when time since boot goes backwards. A wall clock move of
more than 2 seconds is logged as `clock_change_detected`; when the move is forward and the new boot
moment is later than the previous row, the detail says a restart would look the same. The detail
also gives how far `kern.boottime` moved, so the two readings can be compared. Whether
`kern.boottime` moves when the clock is set by hand has not been measured.

Ordering: the tools order rows by `(boot_count, elapsed_ms, seq)`. With boot_count -1, rows logged
after a restart sort before earlier ones. Until the tools handle that, read across a
`boot_detected` row in seq order. The manifest's `row_rules` says the same.

Apple's boot-time privacy rule (reason 35F9.1, declared in `PrivacyInfo.xcprivacy`) says boot-time
information may not be sent off the device, except time elapsed between events in the app.
Exporting `elapsed_ms` and `boot_wall_ms` goes past that. Development-signed builds are not
reviewed; this has to be settled before any TestFlight or App Store build.

## Where it keeps the log

`kl.db`, with the Android app's schema 3 (`docs/DATABASE.md`), in the App Group container
`group.com.abrignoni.knowndatalogger`, under `Library/KDL/`, so the widget and controls can write
to it. Journal mode is DELETE. Each write runs in one `BEGIN IMMEDIATE` transaction that reads the
newest hash and inserts the row, so the app and its extensions never hash over the same previous
row. `actions-live.jsonl` beside it gets every row as it is written. Exports go to the app's own
Documents folder; `xcrun devicectl device copy from` can read that folder but not the App Group.

Bundle ids: `com.abrignoni.knowndatalogger` (app) and `com.abrignoni.knowndatalogger.widgets`.

## Build

Needs Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```bash
cd ios && xcodegen generate
```

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
```

```bash
xcodebuild -project ios/KDL.xcodeproj -scheme KDL -destination 'id=<UDID>' -allowProvisioningUpdates build
```

The team in `project.yml` is a free personal team, so a build runs for 7 days. A first install
needs Settings > General > VPN & Device Management > Developer App > Trust.

## Tests

```bash
cd ios/KDLCore && swift test
```

The core reproduces the shared test vector (`23843a77…`, the same row as `ChainTest` and
`tools/test_make_documentation.py`), re-verifies the hash chain of an export
(`KDL_ANDROID_EXPORT=<export folder>`; skipped when absent), checks revisions, tombstones, restart
and clock change detection, two writers on one database, and runs `tools/make_documentation.py` on
an iOS export (exit 0, no warnings).

## Tested

On one iPhone 13 with iOS 26.6, 9 November 2026, development build, made-up content: setup and a
profile snapshot, an app under test, Start and Stop session, a hand-logged action, Mark and Stamp in
the app, Mark from the Lock Screen Live Activity (removed in 0.2.0; iOS asked once to allow it), the
Control Center control, labelling a mark, an edit, a removal, and an export. The export (13 rows,
built as 1.0 before the version number was fixed) passed `make_documentation.py` (sha256 match,
chain intact, exit 0, no warnings) and the Swift verifier gave the same last hash. Across the
session, `utc_ms - elapsed_ms` stayed within 7 ms. It holds four mark rows: one from the in-app
button and three logged while the Lock Screen and Control Center steps were done. A row does not
say which surface wrote it, so which tap made which row, and whether every tap was logged, is not
established. After reinstalling as 0.1.0, a second export held the same 13 rows byte for byte plus
its own export row, and its last hash matched the one shown on the Setup tab.

Not tried on the phone: the Home Screen widget, the share sheet, a restart,
a clock or time zone change.

Screen capture, same phone, 0.2.0 development build (before the three changes listed below):
Notes, Safari and Settings > About, one Back Tap mark in each. Three marks, three screenshots, each
of the right screen (checked by eye on the phone), hash match at export. Frames were 888 x 1920
pixels (the screen is 1170 x 2532), each within 29 ms of its mark. Peak memory footprint 17 MB. Four
app switches logged (Notes, Safari, Settings, KDL). Back Tap missed some double taps; those made no
row, and nothing on screen said so. That led to the "Marked" banner, the app on the mark, and the
separate capture start and stop rows.

Re-test with those changes: one Back Tap mark in Safari and one in Notes. The banner showed after
each, on the first try. Neither screenshot shows the banner (checked by eye), and each frame was
within 47 ms of its mark. Both marks carried the right bundle id, so the labels landed on the
Safari and Notes sheets with their screenshots linked, and the start and stop rows were listed on
the Scenario sheet. `make_documentation.py` exited 0.

## Not built yet

- Screen capture on iOS 27 (ScreenCaptureKit).
- An ordering fix in the tools for boot_count -1 exports.
- The marks do not record which surface they came from (app, widget, control, Back Tap).
- An iLEAPP module for KDL's own database, and a map of the traces KDL leaves on the phone.
