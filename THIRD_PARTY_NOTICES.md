# Third-party notices

KDL's own code is under the MIT License (see `LICENSE`).

The Android app (the APK) includes these libraries, all under the Apache License, Version 2.0. A copy
of that license is in `LICENSES/Apache-2.0.txt`.

- AndroidX libraries (`androidx.*`), including Jetpack Compose (runtime, UI, foundation, animation,
  Material 3 and Material icons), Activity, Core, Lifecycle, SavedState, Window and their
  supporting libraries
- Kotlin standard library (`org.jetbrains.kotlin`) and kotlinx coroutines and serialization
  (`org.jetbrains.kotlinx`)
- JetBrains annotations (`org.jetbrains`)
- JSpecify annotations (`org.jspecify`)
- Guava ListenableFuture (`com.google.guava`)

The list was taken from the release runtime classpath (101 libraries) and each library's license from
its published POM, on 8 November 2026. Update it when dependencies change.

The repository also contains the Gradle wrapper (`gradlew`, `gradle/wrapper/`), under the Apache
License, Version 2.0.

The Python tools in `tools/` carry copies of these readers in `tools/vendor/`, each with its license
file beside it and its upstream commit and hash in `tools/vendor/vendored.json`. None is part of the
app.

- sqlite-wal (MIT, Copyright (c) 2026 Alexis Brignoni): `LICENSE-sqlite-wal`
- mmkv-parser (MIT, Copyright (c) 2026 Alexis Brignoni): `LICENSE-mmkv-parser`
- ccl_chromium_reader (MIT, Copyright 2020, CCL Forensics), as vendored in DLEAPP:
  `LICENSE-ccl_chromium_reader`

The tools also use the protobuf package (BSD 3-Clause), installed separately, when it is present.

Used only during development and not part of the app: JUnit (Eclipse Public License 1.0) and
JSON-java (public domain) in the unit tests, and openpyxl (MIT) for the Python tools, which is
installed separately.
