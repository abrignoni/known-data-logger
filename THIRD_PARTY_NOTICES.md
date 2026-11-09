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

Used only during development and not part of the app: JUnit (Eclipse Public License 1.0) and
JSON-java (public domain) in the unit tests, and openpyxl (MIT) for the Python tools, which is
installed separately.
