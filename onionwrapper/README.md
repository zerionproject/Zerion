## Onion Wrapper

A Java library providing a wrapper for running a Tor client on Android, Linux, macOS and Windows. The
wrapper can be used for running hidden services.

The library supports the `obfs4`, `meek_lite` and `snowflake` pluggable transports.

Binaries for Tor and pluggable transports are not included. They can be found in the following
Maven artifacts: `org.briarproject:{tor,lyrebird}-{android,linux,macos,windows}`. The
`lyrebird` artifact provides the `obfs4`, `meek_lite` and `snowflake` transports.

The macOS binaries are not signed, so you will need to sign them when packaging your app.

If your Android app is uploaded to Google Play as an app bundle (AAB), you must include the following in
 `build.gradle` to ensure that the Tor and pluggable transport binaries are extracted during
 installation:

```
android {
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

On Android, the library uses
[dont-kill-me-lib](https://code.briarproject.org/briar/dont-kill-me-lib) to hold a wake lock
whenever Tor's network connection is enabled. The helper classes in `dont-kill-me-lib` can be used
to work around certain manufacturer-specific power management restrictions that would prevent Tor
from running in the background for long periods.
