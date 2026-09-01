# Payjoin on-device tests

These run on a real device or emulator because they load the native library and,
for relay vetting, use Tor. They are not part of the JVM regression suite.

## Native load + JNA/UniFFI boundary hardening

`PayjoinNativeInstrumentedTest` verifies the native library loads on the device
ABI and that the binding boundary fails closed (malformed, oversized,
use-after-destroy, double free, repeated create/free, concurrency).

Run on a connected device:

```
gradlew :zerion-android:connectedOfficialDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.professor.zerion.android.payjoin.PayjoinNativeInstrumentedTest
```

Or install the two APKs and run the instrumentation directly:

```
adb install -r zerion-android/build/outputs/apk/official/debug/*.apk
adb install -r zerion-android/build/outputs/apk/androidTest/official/debug/*.apk
adb shell am instrument -w \
  -e class com.professor.zerion.android.payjoin.PayjoinNativeInstrumentedTest \
  com.professor.zerion.debug.test/com.professor.zerion.android.BriarTestRunner
```

ABI coverage: run once on the 64-bit device (arm64-v8a) and once on a 32-bit
target or emulator (armeabi-v7a). Both are shipped ABIs and both must pass
before ABI support is considered complete.

## Live BIP77 directory/relay vetting

Follow `RELAY_VETTING.md`. The endpoint must be reached only over the app's Tor
SOCKS proxy with a dedicated Payjoin isolation context and no clearnet
fallback. Confirm the OHTTP key config parses via `OhttpKeys.decode`, the
request/response round-trips through the relay for a throwaway session, and the
flow fails closed when Tor is down. Record the exact endpoint, date, and result
in `RELAY_VETTING.md`. Until every criterion passes, the Payjoin feature flag
stays off.
