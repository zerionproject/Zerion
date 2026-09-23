# Contributing to Zerion

Zerion is a secure messenger whose online messaging runs over Tor with no
central server and whose transport is built to leak as little metadata as the
threat model in `docs/ZERION_TECHNICAL_WHITEPAPER.md` allows. A few of the
rules below are **non-negotiable** because they are part of that threat model,
not style preferences. Please read these before opening a pull request.

## Repository layout

* `zerion-android` - the Android app
* `zerion-core-api`, `zerion-core`, `zerion-core-android` - identity, storage,
  pairing, the ZTP/ZWF/ZPP/ZMM transport stack, the Mode 3-Full ratchet, the
  mesh and I2P transports (cross-platform core, Android bindings)
* `zerion-wire` - wire-format constants shared with other clients
* `zerion-app-api`, `zerion-app` - messaging, introductions, groups, channels,
  calls
* `i2p-embedded` - the shaded I2P router (release builds carry it; the
  transport is off unless the user enables it)
* `onionwrapper` - the Tor onion-service wrapper (a vendored copy of the Briar Project's onionwrapper library)

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
Preferences go through the in-tree Keystore-backed `ZerionEncryptedPrefs`;
sensitive metadata goes through the SQLCipher-backed `Settings`.

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
./gradlew :zerion-android:assembleOfficialDebug   # build the debug APK
./gradlew :zerion-core:test                  # transport / crypto unit tests
./gradlew :zerion-app:test                    # messaging / groups / channels tests
```

For any change to crypto, ratchet, or wire format: run the full unit suite,
build a debug APK, and smoke-test on two emulators before proposing it. A tag is
the end of validation, not the start.

## Documentation

Public documents, the store description and the website are checked against
`docs/release-manifest.json` (versions, hashes, channels, platform status) and
`docs/crypto-primitives.json` (the primitives the code uses) by
`scripts/check-docs.py`, which also refuses the stale phrases listed in it.
Run it before changing any document; the website's release block is rendered
from the manifest by `scripts/render-release-refs.py`, never edited by hand.
Historical posts and changelog entries keep their text and carry a dated note.

## Code style

* Match the surrounding code - indentation, naming, idiom.
* No comments that narrate history ("removed X", "was Y before", "see commit …").
  Put rationale in the commit message and keep the source clean.
* Fully qualify exceptions when the import isn't already present.

## License

By contributing you agree your work is licensed under **GPLv3**.
