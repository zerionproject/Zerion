# Tor for Android build - provenance & reproducibility

This is the supply-chain record for the Tor executable the app runs,
`libtor.so`, one per shipped ABI. The app is a Tor-only messenger, so this
binary is the anonymity floor: it is built from the pinned official Tor
release source with its three static dependencies at pinned commits, through a
vendored build makefile, and the result is verified by hash by the build
before packaging and by the app before every start. No prebuilt Tor binary and
no unverified download is used.

## Why the build moved in tree

Until 3.0.12 the app took Tor from the Briar Project's published
`org.briarproject:tor-android` artifact, itself a reproducible build of the
same sources with the same makefile. Tor 0.4.9.13, a security release of
2026-09-23 with ten TROVE fixes, was not available from that publisher when
the app needed it, so the same build now runs from this directory with the
same toolchain, which keeps the output byte-identical to what the publisher
would produce: the 0.4.9.12 build from this script reproduces the published
artifact's executables exactly (see the verification section).

## What is built

- `libtor.so` for `arm64-v8a` and `armeabi-v7a`: the Tor client, statically
  linked against libevent, OpenSSL and zlib, built with
  `--disable-module-relay --disable-module-dirauth` (client and onion service
  only), `--enable-android`, PIE, stripped. Since 3.0.19 it is also built
  with `--enable-gpl`, which adds Tor's onion service proof-of-work module
  (proposal 327, configure: "pow: yes"); it uses Equi-X and HashX from
  `src/ext/equix` of the pinned Tor tag (Copyright (c) 2020 tevador,
  LGPL-3.0-only), and Tor built this way is covered by the GNU GPL,
  version 3, instead of its usual 3-clause BSD licence (see NOTICE.md and
  `docs/licenses/tor-equix-hashx.txt`). The exact configure lines are in
  `Makefile`.
- The lyrebird pluggable-transport executable is not part of this build. Since
  3.0.18 it is built in tree as well, from its signed upstream release tag with
  the official Go toolchain and the same NDK r29, by
  `../lyrebird-android/build-lyrebird-android.sh`, which `fdroid-build.sh` runs
  after Tor; its record is
  [../lyrebird-android/PROVENANCE.md](../lyrebird-android/PROVENANCE.md). Up to
  3.0.17 it came from the verified `org.briarproject:lyrebird-android:0.6.2`
  jar.

## Pins

| Component | Pin |
|---|---|
| Tor | tag `tor-0.4.9.17`, commit `a00b3e0bd288e9ae8eada8af2258a3002dfab6ff` (tag object `a24b3aa385d11bd386937e599c3e0c098abe08f9`, with a good signature from David Goulet's key `B74417EDDF22AC9F9E90F49142E86A2A11F48D36`; the matching `tor-0.4.9.14.tar.gz.sha256sum` on dist.torproject.org, `182449ac1c8ff43278da27b69b02040e11d03d0327db6d9126e4190ec6235e4a`, carries good signatures from that key and from Alexander Færøy's signing subkey `514102454D0A87DB0767A1EBBE6A0531C18A9179` (primary key `1C1BC007A9F607AA8152C040BEA7B180B1491921`), both listed in Tor's README and fetched from keys.openpgp.org by fingerprint; the 1442 files the tarball shares with the tag are byte-identical to it) |
| libevent | tag `release-2.1.13-stable`, commit `79ddfb460847999b807cba76d04e73891f29c6ee` (tag object `211c4234e44e0e7df720f744a0c70be017e39622`) |
| OpenSSL | tag `openssl-3.5.9`, commit `45e844fa2a14ec92d146bd8f5778ac130b6625fb` (tag object `d0ca66a1abe52545f14eca635c648932fcde5615`; the tag and the release tarball `openssl-3.5.9.tar.gz`, SHA-256 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`, carry good signatures from the signing subkey `C46ED3F2CBEFDA1FDAADA44264ED7B1DCCE71CB2` of the OpenSSL signing certificate `B146647E45A7B33947AB226B2A2C87D161692D40` that openssl-library.org/source names as the trust anchor, taken from its `pubkeys.asc` and the same subkey that signed 3.5.8; the 5779 files the tarball shares with the tag are byte-identical to it) |
| zlib | tag `v1.4`, commit `99623ef7097352da7993ba614161fd6a311e046e` (tag object `dd9d5c2d62b52e8acb0e678de5768087eedce914`) |
| Android NDK | r29 (`29.0.14206865`), zip SHA-256 `4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf` |
| Android API | 21 (the NDK platform level the makefile targets) |
| Build clock | `SOURCE_DATE_EPOCH=1234567890`, `TZ=UTC`, `-fdebug-compilation-dir .`, `-Os`, set by the makefile |
| Makefile | vendored from the Briar Project's tor-reproducer, originally Guardian Project's `tor-android/external/Makefile` (BSD 3-clause); modified only in its header and in Tor's configure line, which sets `CPPFLAGS="-D_FORTIFY_SOURCE=2"` (see the dependency review) and, since 3.0.19, passes `--enable-gpl` for the proof-of-work module; every other configure flag is unchanged |
| ABIs | arm64-v8a, armeabi-v7a |

The script asserts every commit after checkout and aborts on a mismatch; it
records the resolved commits in `out/SOURCES.txt` and the SHA-256 of each
`libtor.so` next to it.

## Dependency review (2026-11-18)

- Tor 0.4.9.17 (2026-11-13), 0.4.9.16 (2026-11-04) and 0.4.9.15 (2026-10-28)
  are security releases aimed at relays and directory authorities during
  the circuit flood of October and November 2026: relays track IPv6 clients
  by /64 prefix (TROVE-2026-031), rate-limit BEGIN_DIR cells per circuit and
  onion service directories accept one descriptor fetch or upload per
  circuit; the authorities worked around old clients that could not parse
  microdescriptors without TAP keys. None of that runs on a phone. What
  does reach a client: the keys of the directory authority bastet are
  removed after a potential compromise and maatuska has new addresses, so an
  older Tor still trusts a key the network no longer does and dials an
  authority that moved; the fallback directories were regenerated on
  2026-11-13; a RESOLVED cell whose hostname record is not a valid DNS name
  is rejected (TROVE-2026-075), which concerns the exit streams the app opens
  for clearnet wallet nodes, link previews and the update check; a latent
  client assert in indirect begin_dir requests is gone; a refused exit
  stream no longer marks the relay unusable until restart (0.4.9.15); and
  from 0.4.9.15 the network requires the DESC_NO_TAP and MICRODESC_NO_TAP
  capabilities, which 0.4.9.14 already has. The tag `tor-0.4.9.17` carries a
  good signature from David Goulet's key `B74417EDDF22AC9F9E90F49142E86A2A11F48D36`,
  the key pinned below, checked on 2026-11-18 against the key fetched by
  fingerprint from keys.openpgp.org; the release tarball on
  dist.torproject.org matched its published SHA-256
  `ff372c41ee7013557fe5b7acd96c909950b27a7959c59fcb87c02591682fabe2`.
- libevent 2.1.13-stable (2026-07-01) is a security release: out-of-bounds
  reads and an integer overflow in evtag and evrpc, HTTP header and trailer
  handling in evhttp, a heap out-of-bounds write with AF_UNIX sockets under
  NDEBUG, a dangling pointer in evbuffer_add_reference and an out-of-bounds
  write in evdns name encoding. Tor uses libevent's event loop and, on
  relays, evdns; the executable now links the fixed library either way.
- zlib 1.4 (2026-11-04) is a security release: among others a buffer
  overflow in non-blocking gzwrite (CVE-2026-85091), an out-of-bounds write
  in inflateGetDictionary after a header with an invalid window size, a
  one-byte window overrun after inflateSync, and reads before the input in
  inflatePrime. Tor inflates directory documents it receives from relays
  with zlib; it calls none of the gz, Sync, Prime or Dictionary entry
  points, but the fixed library is linked all the same. zlib's configure
  script is unchanged for this build; the 1.4 changes to CMake and to
  z_off_t on Windows do not apply.
- OpenSSL 3.5.9 (2026-09-29) is still the newest 3.5 release.
- Go 1.26.11 (2026-11-10) and 1.26.10 (2026-10-19) carry security fixes in
  crypto/x509, net/http, cgo, the compiler, the linker and the go command;
  the lyrebird executable is rebuilt with 1.26.11 (`../lyrebird-android`).
- lyrebird 0.10.0 (2026-11-04) updates its snowflake library to 2.15.3, whose
  changes since the version in 0.9.0 are broker and proxy fixes, and its
  tag is signed by a different maintainer key than the one pinned in
  `../lyrebird-android`; the pin stays at 0.9.0.

## Dependency review (2026-10-18)

- Tor 0.4.9.14 (2026-10-07) is a security release: clients again honour the
  MiddleOnly flag, so MiddleOnly relays are no longer used in onion service
  circuits (TROVE-2026-067); a router descriptor with an expired family
  certificate counts as expired (TROVE-2026-062); the circuit build timeout
  no longer gets stuck and a cancelled circuit no longer makes Tor drop a
  working guard connection; an introduction circuit re-extended after a NACK
  uses the new introduction point's key; onion services close a circuit that
  asks for an unconfigured port again; and a stream to a `unix:` onion service
  target, the target type this app uses, no longer leaks memory. The release
  does not change the required OpenSSL version or the TLS and crypto code.
- Tor's configure now picks the fortify level with `AX_ADD_FORTIFY_SOURCE`
  instead of a fixed `-D_FORTIFY_SOURCE=2`, and with the NDK compiler it
  picks level 3. The NDK r29 bionic headers use the stricter object size type
  1 for level 2 only (`sys/cdefs.h`) and give level 3 the whole-object checks
  of level 1, so a build left at level 3 has fewer checked calls (see the
  counts under the recorded hashes). The makefile therefore runs Tor's configure with
  `CPPFLAGS="-D_FORTIFY_SOURCE=2"`; with the level already defined, both
  probes of the macro add nothing and the build stays at level 2.
- OpenSSL moves from 3.5.8 to 3.5.9 (2026-09-29) for CVE-2026-35189 (Low): a
  peer certificate with many relative CRL distribution points makes OpenSSL
  allocate several hundred MiB while it caches the certificate's extensions
  during a normal handshake. Tor as a client verifies the chain of every relay
  and bridge it connects to (`SSL_VERIFY_PEER` with an accept-all callback),
  so a malicious guard or bridge could push the Tor process out of memory. The
  other 3.5.9 advisories concern DTLS, QUIC, CMP, SM2, curves without a
  dedicated implementation and servers that call `SSL_set_SSL_CTX()`, none of
  which this Tor uses.
- libevent stays at 2.1.12-stable. The security fixes of 2.1.13-stable
  (2026-07-01) are in evbuffer, bufferevent, evtag, evrpc, evdns and evhttp.
  Tor uses the event loop and evutil only; it uses evdns for the relay
  resolver, which this build leaves out (`--disable-module-relay`), and for
  the DNSPort server, which the app never configures (`TorConfigurationTest`),
  and none of the other modules.
- zlib stays at 1.3.2, the newest release.

## Recorded hashes

| File | SHA-256 | Size |
|---|---|---|
| `out/arm64-v8a/libtor.so` | `a083169439bee6689b35d615dcaa714887d5a0b91e05631b7d66f15bba960409` | 8888544 bytes |
| `out/armeabi-v7a/libtor.so` | `de2d37d054783a0cff6ee667a6b4bfbaeef2d59a84c140b382c24eb4c1741f90` | 7194124 bytes |

Both are stripped position-independent executables with `BIND_NOW`, no text
relocations and a non-executable stack; the arm64-v8a segments are aligned to
16 KB, the armeabi-v7a segments to 4 KB as before. They report
`tor 0.4.9.17` with the git id `a00b3e0bd288e9ae`, `OpenSSL 3.5.9 29 Sep 2026`,
libevent `2.1.13-stable` and zlib `1.4`, and Tor is compiled at
`-D_FORTIFY_SOURCE=2` (configure: "whether to add -D_FORTIFY_SOURCE=3 to
CPPFLAGS... no", the same for 2). Tor's configure summary reports
`License Option: GPL` and `pow (--disable-module-pow): yes` for both ABIs and
is otherwise the same as for the 3.0.18 build, apart from the configure line,
which adds `--enable-gpl`. The executables contain the Equi-X and HashX code
(for example the strings `HashX v1` and "Constructing EquiX challenge") and
print "This build of Tor is covered by the GNU General Public License" for
`--version`, and they no longer contain the message "this build of tor does
not include the required 'pow' module". The compiler's only new warning, on
armeabi-v7a, is Tor's own `src/ext/compat_blake2.h:38`, which narrows a 64-bit
length to `size_t` when it calls HashX's BLAKE2b; Tor passes fixed-size
buffers there.

Calls to bionic's checked functions, counted as branch instructions to their
PLT entries in the `llvm-objdump` disassembly of NDK r29 (for armeabi-v7a the
PLT entries are decoded to their `R_ARM_JUMP_SLOT` relocations;
`__stack_chk_fail` is not counted):

| Function | 0.4.9.13 | 0.4.9.14 at level 3 | 0.4.9.14 in 3.0.18 | 0.4.9.14 with pow (2026-10-26 build) |
|---|---|---|---|---|
| `__strlcpy_chk` | 49 | 26 | 49 | 49 |
| `__strrchr_chk` | 1 | 0 | 1 | 1 |
| `__strlen_chk` | 29 | 29 | 29 | 29 |
| `__memcpy_chk` | 13 | 13 | 13 | 14 |
| `__read_chk` | 4 | 4 | 4 | 4 |
| `__strlcat_chk` | 3 | 3 | 3 | 3 |
| `__strchr_chk`, `__strncpy_chk2`, `__FD_SET_chk`, `__FD_CLR_chk`, `__FD_ISSET_chk` | 2 each | 2 each | 2 each | 2 each |
| `__memset_chk` | 1 | 1 | 1 | 1 |
| `__vsnprintf_chk` (armeabi-v7a only) | 6 | 6 | 6 | 6 |
| Total arm64-v8a / armeabi-v7a | 110 / 116 | 86 / 92 | 110 / 116 | 111 / 117 |

The counts are the same on both ABIs apart from `__vsnprintf_chk`. The one
additional `__memcpy_chk` comes from the code that `--enable-gpl` adds; no
checked call of the 3.0.18 build is lost. The 3.0.18 column was measured again
with the same counter on 2026-10-26 and matched the values recorded then. The
table was not repeated for the 0.4.9.17 build of 2026-11-18; that build uses
the same makefile, configure line and NDK, and its configure output reports
the same fortify level, so the counts are expected to differ only where Tor's
own code changed between 0.4.9.14 and 0.4.9.17.

The same values are the pins in
`zerion-core-android/src/main/resources/org/zerionproject/tor/binaries.sha256`,
which the Gradle gate `verifyTorNativeArtifacts` checks before the files are
merged and the app checks before every Tor start, and in
`zerion-core-android/src/test/java/org/zerionproject/tor/TorBinaryPinsTest.java`.
A change of any pin above has to be accompanied by a rebuild and an update of
all three places.

## Verification

- Two environments (2026-11-18, Tor 0.4.9.17 with libevent 2.1.13, OpenSSL
  3.5.9 and zlib 1.4, the makefile unchanged): the hashes above came out byte
  for byte the same from `fdroid-build.sh` as root in the Briar reproducer
  image (`zerion/tor-reproducer-android`, image
  `sha256:01e6005fd3297208d7bbabbc2687c765f0a5e9a3ca62b6b7be0b9989bb235875`)
  and from `fdroid-build.sh` run the way the recipe runs it, as the `vagrant`
  user from `zerion-android` with `NDK_CACHE=/build` after the recipe's
  `sudo` lines, in `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`
  (`sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8`),
  the same two images as on 2026-10-26. In both the sources resolved to the
  pinned commits and the NDK r29 zip matched the pinned SHA-256; the
  lyrebird build that follows in the same recipe line gave the hashes pinned
  in `../lyrebird-android/PROVENANCE.md` in both environments as well. The
  executables are `DYN` with `BIND_NOW` and `NOW PIE` flags, a `GNU_STACK`
  segment with `RW` only and `LOAD` alignment of 16 KB (arm64-v8a) and 4 KB
  (armeabi-v7a), and they contain the strings `Tor 0.4.9.17`, the git id
  `a00b3e0bd288e9ae`, `2.1.13-stable`, `OpenSSL 3.5.9`, `inflate 1.4` and
  `HashX v1`.
- Toolchain proof, with the makefile as it was before the `CPPFLAGS` line: the
  same script with `TOR_TAG=tor-0.4.9.12` and
  `TOR_COMMIT=78923280eed3eff6a77910bba59ecc1fa2244e02` (the tag whose git id
  the shipped 3.0.12 daemon reports) produced exactly the same bytes as the
  published `tor-android-0.4.9.12.jar` executables
  (`bf36260486d44c2745fda8e4448668a1ee758db0aa79156299979916b03124a3` and
  `e28eee1ce24a54c8518a5ea91de9365f7db3ea0a9c67a6c2c039b6a85e632485`).
- Two environments (2026-10-26, Tor 0.4.9.14 with OpenSSL 3.5.9 and the
  makefile with the `CPPFLAGS` line and `--enable-gpl`): the hashes above came
  out byte for byte the same from `build-tor-android.sh` as root in the Briar
  reproducer image (`zerion/tor-reproducer-android`, image
  `sha256:01e6005fd3297208d7bbabbc2687c765f0a5e9a3ca62b6b7be0b9989bb235875`)
  and from `fdroid-build.sh` run the way the recipe runs it, as the `vagrant`
  user from `zerion-android` with `NDK_CACHE=/build` after the recipe's
  `sudo` lines, in `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`
  (`sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8`),
  the same images as on 2026-10-19 (Debian 13.7 with gcc 14.2.0, autoconf
  2.72 and automake 1.17). The NDK r29 zip kept from the 2026-10-18 download
  was checked against the pinned SHA-256 inside each environment before it
  was unpacked there, and the sources resolved to the pinned commits in both.
  The second environment ran the recipe's whole line twice: both runs gave
  these Tor hashes, and in the second the lyrebird build that follows it gave
  the hashes pinned in `../lyrebird-android/PROVENANCE.md` (in the first, the
  Go module download failed on a transient error from `proxy.golang.org`).
- Two environments (2026-10-19, the build shipped in 3.0.18, without
  `--enable-gpl`): `21604deaa31ecfd954220b25800c96374f92b450df2327aea916836799fbdc47`
  (arm64-v8a, 8842144 bytes) and
  `75ee09598e7efec2a242a96ce189d3aceab55089ce6d8df5966173ca5e6fc9da`
  (armeabi-v7a, 7127292 bytes) came out byte for byte the same from
  `build-tor-android.sh` as root in
  the Briar reproducer image (`zerion/tor-reproducer-android`, image
  `sha256:01e6005fd3297208d7bbabbc2687c765f0a5e9a3ca62b6b7be0b9989bb235875`)
  and from `fdroid-build.sh` run the way the recipe runs it, as the `vagrant`
  user from `zerion-android` with `NDK_CACHE=/build` after the recipe's
  `sudo` lines, in `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`
  (`sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8`).
  Both are Debian trixie with gcc 14.2.0, autoconf 2.72, automake 1.17 and
  libtool 2.5.4. The NDK r29 zip was downloaded once and checked against the
  pinned SHA-256 inside each environment before it was unpacked. The first
  0.4.9.14 build of 2026-10-18, at fortify level 3 without the `CPPFLAGS`
  line (`66171a3c07be68cc95be9c1ba2d492d528441ef228591d592a4b3ea15f010bb5`
  and `83d0e90a3faa2839b3c180eb059ae7116697b00553785d24a07175af7bd24061`),
  was equally identical in both environments; both are superseded by the
  2026-10-26 build.
- Previous build (Tor 0.4.9.13 with OpenSSL 3.5.8, shipped in 3.0.15 to
  3.0.17): `586209695b73aea897086d0500b7ef1dcccb0a5a8a002c4e1903ab2ad866dced`
  (arm64-v8a) and `1b83790304f4c15a283ada143d35ef86a30027d814d0cd8cf6e1004c360eeb09`
  (armeabi-v7a). On 2026-10-05 the Moto (Android 15) and the Pixel 9
  Pro (Android 17) ran that build as `Tor version 0.4.9.13
  (git-3c575400909efe65)` with OpenSSL 3.5.8: both phones signed in, reached
  each other over Tor and established audio calls, and a call survived a 35 s
  connection loss and ended on both sides within the liveness deadline after
  a lasting loss.
- Runtime of the 3.0.18 build: on 2026-10-19 the 0.4.9.14 executable built
  without `--enable-gpl` ran as the app's Tor on two phones (Android 15 and
  Android 16, debug builds of the same tree). Both reached Online and
  exchanged messages; address renewal, client authorization repair, deep
  sleep and a Tor restart were exercised on it. The 2026-10-26 build with the
  proof-of-work module has not yet run on a phone.

## How to rebuild

Inside the Briar reproducer image (Debian trixie with the build packages) or
on any Debian host with `ca-certificates curl unzip git build-essential make
patch pkg-config autoconf automake libtool perl python3 file xz-utils`:

    ./build-tor-android.sh

writes `out/<abi>/libtor.so` and `out/SOURCES.txt`. `fdroid-build.sh` is the
same build with the F-Droid defaults and is the recipe's build step; it then
runs the lyrebird build, so the one recipe line produces both executables. The
Gradle build copies the two executables from `out/` into its own directory
and verifies them; nothing is read from the source tree at run time.
