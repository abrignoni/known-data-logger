# Known-data logger

An Android app for test phones. Turn on capture, use the phone as you normally would, and the app
records what you do and when: taps, typed text, screens, and screenshots at the moments that
matter. You can also log actions by hand. At the end you export a log that goes in the
`documentation/` folder next to the extraction, and a script turns it into an `.xlsx` document, so
anyone validating a parser can check each action against the tool output.

It is meant for test devices only. Never install it on an evidence device.

On the home screen it is labelled **KDL**. App info, Accessibility settings and Android's
permission dialogs show the full name, Known-data logger.

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

1. On first launch KDL walks you through four steps: the test-phone notice with a scenario name and
   device label, the apps to capture, turning on "Known-data logger" in Settings, Accessibility,
   and done. An APK installed from a file is blocked at that step: on a Pixel 7a with Android 17,
   an install through the Files app made Android answer "App was denied access" when capture was
   switched on. Fix: Settings, Apps, KDL, the three-dot menu, Allow restricted settings (asks for
   the phone's PIN), then switch it on again. An `adb install` did not trigger the block.
2. On the **Session** tab, tap Start session. A red MARK button floats over every app except KDL.
3. Use the phone. Tap MARK at any moment you want recorded with a screenshot. Chips on the Session
   tab log an action by hand, stamped at the tap.
4. Stop the session. A sheet shows how many items need checking, with Check now, Export and Later.
5. On the **Check** tab, label marks and locked notifications and accept or change inferred actions
   ("Accept all" has Undo). The Timeline view shows every entry to the second; tap one to edit it.
   **Setup** holds the scenario, apps, capture options, rules, device snapshots and the latest
   row hash.

What is captured, only in the apps under test plus System UI and Settings unless you choose all apps:

- screen changes, taps (label and view id), and typed text as the field's final value, stamped
  at the last keystroke
- never the content of password fields, including a password shown with "Show password" and
  number passwords; a field seen once as a password stays withheld for the session. While a
  password field has focus, taps are recorded without their label, so an on-screen keypad cannot
  spell a PIN. Tested in the Wi-Fi dialog: the SSID typed beside it was captured, the password
  (hidden or shown) was not, not even in the database file.
- while the phone is locked, only notifications from the captured apps and the app's own system
  rows (package changes, reboots) are recorded; taps, typed text and screens are not (not yet
  tried with a PIN set)
- screen titles are the window's title only: System UI also reports the whole notification
  shade, and that text is never recorded
- a field cleared in one step, which is how apps built with Jetpack Compose show a send: they
  report no tap event for their buttons (seen with Google Messages)

Incoming messages and calls come from notifications. Notifications posted by the apps under test
are recorded with their title (usually the sender or number) and text, even while the phone is
locked. Message and missed-call notifications also become `received_message` and `missed_call`
for review; a call notification becomes `incoming_call` only when the app marks it as an incoming
call (Android 12 and later), because dialers post ongoing calls in the same category. While the
phone is locked, Android 17 (seen on a Pixel 7a) sends the notification event without the
notification's details: no sender, text or category, only a short line such as "New message.". Those
are recorded with that line, marked "notification details withheld by Android while locked", and
put on the review list for you to label. Toasts and
group summaries are ignored, and the same notification re-posted within 60 seconds is logged once.
Tried on an Android 15 emulator (8 November 2026) with an SMS and a call sent from the emulator
console.

Notifications can carry one-time codes. By default ("Withhold codes in notifications" on the
Capture tab), any run of 4 to 8 digits in text that mentions a code, OTP, PIN or verification is
replaced with `[code withheld]`, in notifications, screen titles and tap labels. On the emulator,
an SMS code was absent from the database file and the live copy afterwards. Codes in other wording
are not caught, so keep real accounts off test phones.

Auto-label rules turn events into actions, for example a cleared message field in Google Messages
becomes `sent_message` with the text as content. Rules ship in `app/src/main/assets/rules/`. You can
teach new ones in the app: label a captured tap, cleared field or mark in Timeline and tick "Make a
rule". A rule from a tap without a view id matches the tap's label (whole label, case-insensitive),
so that label is stored in the rule; check taught rules before sharing them. A cleared field can
only be taught when it has a view id, because its label would be the typed message. If
`rules.json` has a rule the app cannot read, saving refuses rather than overwriting it. Taught rules are saved in `rules.json` in the app's external files folder, where you can
pull them to share or push someone else's. Each shipped rule says
which app version it was checked against. Auto labels stay "to review" until someone accepts them.

Screenshots use the accessibility service's own capture (Android 11 and later): no extra
permission and no prompt (none appeared on the emulator). They are stored in the app's private folder with a
sha256 taken at capture, copied to `screens/` on export and checked again. A black frame from an app
that blocks screenshots is recorded as `blank`.

Known limits:

- Tools that drive the phone through UiAutomation (uiautomator, Puma and similar) pause every
  accessibility service while they run. On the emulator each `uiautomator dump` showed up as a
  `capture_service_disconnected` and `capture_service_connected` pair in the log.
- A keypad made of buttons in an app with no password field (some banking or vault apps) is
  recorded tap by tap. Use test personas and test PINs only.
- Text still being typed when the app is killed is lost; it is written when the field is left.
- The live copy (`actions-live.jsonl`) holds everything captured, including typed text, and stays
  on the phone after an export.
- Some apps behave differently when an accessibility service is on.
- Rules break when an app changes its screens or runs in another language. Review catches it.

Apps under test that are installed, updated or removed, or have their data cleared, during a
session are logged with their versions. At session start, an app whose version differs from the
last one the log accounted for is flagged once.

## Hash chain (0.3)

Every row carries `prev_hash` and `row_hash`: a SHA-256 over the row's fields and the previous
row's hash. Changing, removing or reordering a row breaks every hash after it, and
`make_documentation.py` checks the whole chain. The Export screen shows the newest row's hash;
write it in your documentation, because someone who rewrites the whole chain can only be caught
against a copy of a hash kept somewhere else. Check it later with
`make_documentation.py ... --expect-hash <hash>`: the Scenario sheet says whether it is still in the
chain and where. The generator also warns when its own chain check and the app's (recorded in the
manifest at export) disagree. Rows written before 0.3 are hashed the first time the upgraded app
writes a row or shows the Export screen, and a `hash_chain_backfilled` row says so.

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
- `actions.csv`: the same rows for a spreadsheet, columns in reading order: time, what happened
  (action, app, content, counterpart), status (current, reviewed, source), screenshot, context,
  then clock internals and identifiers. Filter `is_current` to TRUE for the live version of each entry.
- `screens/`: the screenshots, listed with their hashes in the manifest.

## Documentation (.xlsx)

```bash
python3 tools/make_documentation.py kdl_scenario_phoneA_... kdl_scenario_phoneB_... -o documentation.xlsx
```

Give it every phone's export for one scenario. It writes a Scenario sheet (devices, detected clock
changes and reboots, hash checks, and one summary line of capture pauses: how many happened during
a capture session, which is when a pause can hide actions, and how long they lasted), a Capture
pauses sheet with each pause's start, end and length, an Apps sheet, one sheet per app with each
action and a link to its screenshot (a label made from a mark links the mark's screenshot), a
Needs review sheet and a Captured events sheet. Rows are grouped by device in each device's own
order (boot count, then time since boot), which stays right across clock changes. Needs
`openpyxl`.

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

Version 0.3.0, Android only. Tested on an Android 15 emulator and on a Pixel 7a with Android 17
(8 November 2026), installed with `adb` into a separate user profile on that phone. On the Pixel:
- installing with `adb install --user` and turning the service on did not ask for "Allow
  restricted settings"; installing the same APK from the Files app did (Android recorded it as a
  local-file install), and Allow restricted settings in App info cleared it
- sends in Google Messages (October 2026 build), an incoming call, a missed call, a MARK with its
  screenshot, an export, the hash chain (41 rows) and the spreadsheet all worked
- incoming texts showed only "New message" on the phone itself in that profile, and the log
  recorded the same; message content depends on the profile's notification settings
- with a test PIN set, the PIN never reached the log (not even in the database file's bytes), no
  keypad taps or System UI rows were recorded while locked, and a text arriving while locked was
  kept as a withheld notification for review
- iOS and a checker script that reads the export against LEAPP output are planned.

Author: Alexis Brignoni
