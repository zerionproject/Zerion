# Release verification

This page shows how to check a published Android APK and the signed release
announcement with standard tools, without the app.

## Release signing certificate

The APKs published on GitHub Releases are signed with one RSA 2048 key. Its
certificate is committed as [release-signing-cert.pem](release-signing-cert.pem)
and is public:

    -----BEGIN CERTIFICATE-----
    MIIC3DCCAcQCAQEwDQYJKoZIhvcNAQELBQAwMzEPMA0GA1UEAwwGWmVyaW9uMQ8w
    DQYDVQQLDAZaZXJpb24xDzANBgNVBAoMBlplcmlvbjAgFw0yNTEyMTcxNTM1MTJa
    GA8yMDUwMTIxMTE1MzUxMlowMzEPMA0GA1UEAwwGWmVyaW9uMQ8wDQYDVQQLDAZa
    ZXJpb24xDzANBgNVBAoMBlplcmlvbjCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC
    AQoCggEBALTS+n2StEdTTJ+O4HAW5M58+ck7f+OHOlH9k8AWGnEsrDNXEmqQ8c9t
    tU/WYB94jzfB+4syExaHQjX1he2TKmSE2JWQkYP5xn8fxZTj3j04GR9/J1Dlz0kG
    4IdEkUBtLce6owW6sSwmJEYW3w+p4qMENq2pgWnqJj8lp4asX/cpHakXMOSCReUk
    IWMA4vy8gq4NgVBR84G3Qijqs9yCct2ZZMQHacYZ0MaqtxxYHm+YiplhRz2bMIVa
    5IjjrqWAkikEOalLrC/FjiM1muFy48fy0/pQf0FPMYRcd/GAUZIyL1OLAVKfD3uS
    0UK+RqZJRE6NxzMuAZdkcygpdKg3o8sCAwEAATANBgkqhkiG9w0BAQsFAAOCAQEA
    hrhLegDLxF9pU8KWP7lz9lcd1n9A9Qtt9jw+0CANpBz3zxfqT0urGzx9uA/xgvCN
    2re2BNq+WLTUM65jh09XLy7l0GSlOJ1bCnn+Ege8vC+wdJnj5SJvr73RRD2aVAgT
    jzIstbnW3efHR+dokeTwfOABJfQXDWvcxMAFxahB9RBpEfBsa9rO9ip8wGQ7z0AV
    cCPcGF7uGrP+UGU20BPjDDCVRTM2rS319wdsZ+qOqkSTjYMBMmRtqEKpl9D5tnYZ
    ke7xoKyOa81BiBBToMolHCYBoRrLCbcf+CQAuIGEPBk0gqfmmOD1IyPTssu1WciO
    2Dcl2z1p7Pg4qE12uz/Agg==
    -----END CERTIFICATE-----

- Subject and issuer: `CN=Zerion, OU=Zerion, O=Zerion` (self-signed)
- Valid from 2025-12-17 to 2050-12-11
- SHA-256 of the DER certificate:
  `d7fdb11125890d133ae89d8ba4f4331d9045e21ef01d9899a7cdee6888f704c8`
  (the value in [README.md](../README.md) and `signingCertSha256` in
  [release-manifest.json](release-manifest.json))

Check the certificate file and a downloaded APK:

    openssl x509 -in release-signing-cert.pem -noout -fingerprint -sha256
    apksigner verify --print-certs zerion-3.0.18.apk

Both must show the SHA-256 above (`apksigner` prints it as the
`certificate SHA-256 digest` of signer 1). An installation from Google Play
carries Google's app signing certificate instead (next section) and does not
check release announcements.

## Certificates the app accepts

The app knows exactly two signing certificates. They are named in
`SecureBootGuard`
(`zerion-android/src/main/java/com/professor/zerion/android/security/SecureBootGuard.java`)
and pinned by its unit test:

| Certificate SHA-256 | Signs | Held by |
|---|---|---|
| `d7fdb11125890d133ae89d8ba4f4331d9045e21ef01d9899a7cdee6888f704c8` | The APKs on GitHub Releases and the reproducible F-Droid build | The project (the release key above) |
| `b12ddf964ac59e3914984ec93e068768756bb0b917cb45c3fb2b65dc6c7940c6` | The APK that Google Play installs | Google: Play app signing re-signs the uploaded bundle with a key Google holds. The certificate is shown in the Play Console under App signing, and `apksigner verify --print-certs` prints it for an APK pulled from a Play installation |

Two checks use the list. With the tamper protection of Hardened mode turned
on, the app refuses to start unless every certificate the installed APK is
signed with is one of the two (`SecureBootGuard.verifyAppSignature`; debug
builds skip this check); a repackaged APK is signed with another key and is
refused. The release announcement check (next section) accepts an
announcement only when its signature verifies under a certificate the
installed APK is signed with, so an installation from GitHub Releases or
F-Droid accepts announcements signed with the release key, and an
installation from Google Play, which carries Google's certificate, accepts
none and relies on Play for updates. No other certificate is accepted, and
adding one is a code change in this repository.

## Release announcement

An installation from GitHub Releases checks once a day, over Tor, for
`docs/release-manifest.json` and `docs/release-manifest.json.sig` on the
`master` branch of this repository
(`https://raw.githubusercontent.com/zerionproject/Zerion/master/docs/`).

`release-manifest.json.sig` is one line of base64 followed by a newline. It
decodes to an RSA PKCS#1 v1.5 signature with SHA-256 (Java
`SHA256withRSA`) made with the release signing key above. The signed message
is the ASCII line `zerion-release-manifest-v1`, a LF, and then the exact
bytes of `release-manifest.json` as committed (LF line endings; download the
raw file rather than copying it from a Windows checkout made with
`core.autocrlf`). The app (`ReleaseAnnouncements.verifiedSigner`) builds
the same message, refuses a manifest larger than 64 KiB or a signature file
larger than 4 KiB, and accepts the announcement only when the signature
verifies under a certificate that the installed APK itself is signed with.

To verify it, put `release-signing-cert.pem`, `release-manifest.json` and
`release-manifest.json.sig` in one directory and run:

    openssl x509 -in release-signing-cert.pem -noout -pubkey > release-signing-key.pem
    openssl base64 -d -A -in release-manifest.json.sig -out release-manifest.sig.bin
    { printf 'zerion-release-manifest-v1\n'; cat release-manifest.json; } | openssl dgst -sha256 -verify release-signing-key.pem -signature release-manifest.sig.bin

The last command prints `Verified OK`. A changed byte in the manifest, a
missing or different first line, or another key prints
`Verification failure`. After the signature verifies, the `apk.sha256` in
the manifest is the SHA-256 of the APK published for that release, and
`signingCertSha256` names the certificate above.
