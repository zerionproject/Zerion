# Contributing to Zerion

Zerion is a Tor-only, metadata-free secure messenger. A few of the rules below
are **non-negotiable** because they are part of the threat model, not style
preferences. Please read these before opening a pull request.

## Repository layout

* `zerion-android` - the Android app
* `bramble-*` - the transport/protocol stack (cross-platform)
* `briar-*` - messaging, groups, channels, and sync
* `*-api` - public interfaces and shared types
* `*-core` - cross-platform implementations
* `*-android` - Android-specific implementations
* `*-java` - desktop/headless implementations

## Non-negotiable rules

### 1. No logging. Anywhere.

Zerion ships **zero** logging. No `Logger`, no `android.util.Log`, no `Timber`,
no `System.out` / `System.err`, no `java.util.logging` - not even behind
`BuildConfig.DEBUG`. A Gradle gate fails the build if any production source
references a logger. Any log line is a metadata leak and will be rejected. If
you need to diagnose something locally, remove the instrumentation before you
commit.

### 2. No plaintext at rest

Never call `Context.getSharedPreferences()` directly - it writes plaintext XML.
Preferences go through Keystore-backed `EncryptedSharedPreferences` (or the
in-tree encrypted-prefs implementation); sensitive metadata goes through the
SQLCipher-backed `Settings`.

### 3. Don't change on-wire bytes casually

Wire framing, AEAD nonce derivation, KDF labels, and ratchet state machines are
security-critical and cross-platform - Android and iOS must agree byte-for-byte.
Changes here need a matching spec update under `docs/` and changes on both
clients.

## Branch workflow

* All work lands on **`dev`** first.
* `master` is only updated after cross-device testing is confirmed.
* A release tag triggers the F-Droid reproducible build. Build releases from a
  **fresh checkout of the exact tag commit** with **JDK 21**, or the
  reproducibility check will fail (the APK embeds the commit it was built from).

## Build & test

Use **JDK 21** - this is what the F-Droid build server uses; another JDK can
produce a non-reproducible APK.

```
./gradlew :zerion-android:assembleDebug      # build the debug APK
./gradlew :bramble-core:test                  # transport / crypto unit tests
./gradlew :briar-core:test                    # messaging / groups / channels tests
```

For any change to crypto, ratchet, or wire format: run the full unit suite,
build a debug APK, and smoke-test on two emulators before proposing it. A tag is
the end of validation, not the start.

## Code style

* Match the surrounding code - indentation, naming, idiom.
* No comments that narrate history ("removed X", "was Y before", "see commit …").
  Put rationale in the commit message and keep the source clean.
* Fully qualify exceptions when the import isn't already present.

## License

By contributing you agree your work is licensed under **GPLv3**.
