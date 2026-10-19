# lyrebird for Android build - provenance & reproducibility

This is the supply-chain record for the lyrebird executable the app ships,
`liblyrebird.so`, one per shipped ABI. lyrebird is the pluggable-transport
program Tor starts when the configured bridges use obfs4, meek, snowflake or
webtunnel; every byte the app sends through such a bridge passes through it.
It is built from the signed upstream release tag with the official Go
toolchain and the Android NDK, both verified by hash, and with exactly the Go
modules the release's `go.sum` names. The result is verified by hash by the
build before packaging and by the app before every Tor start, against the
same pin file as Tor. No prebuilt lyrebird binary is used.

## Why the build moved in tree

Up to 3.0.17 the app took lyrebird 0.6.2 from the Briar Project's published
`org.briarproject:lyrebird-android` artifact, built by that project's
go-reproducer with Go 1.22.12. Go 1.22 stopped receiving security fixes when
Go 1.24 was released (the Go project supports the two newest major releases),
and lyrebird 0.9.0, the current upstream release, declares `go 1.26.0` in its
`go.mod`, so it cannot be built with that toolchain at all. The build now runs
from this directory with the current Go 1.26 point release.

What changed upstream between 0.6.2 and 0.9.0 (lyrebird's ChangeLog): webtunnel
gained certificate chain pinning, several server names, uTLS by default and the
`sni-imitation` option (0.7.0); meek gained several url and front pairs per
bridge line through the `targets` argument (0.8.0); the chrome120 uTLS
imitation profile was fixed (0.8.1); the proxy configuration of snowflake was
fixed (0.9.0). The bridge lines the app bundles use none of the new arguments;
`BundledBridgeSourcesTest` checks every bundled line against the arguments the
pinned lyrebird parses and fails when a line needs a newer one, for example
meek `targets` with a lyrebird older than 0.8.0.

## What is built

- `liblyrebird.so` for `arm64-v8a` and `armeabi-v7a`: the lyrebird client
  (`./cmd/lyrebird`), with cgo, as a position-independent executable for
  Android API 21, stripped of symbols and debug information, with a 16 KB
  maximum page size. It reports version `0.9.0` to Tor.
- The executable is a Go program and statically links the Go runtime and
  standard library and the Go modules listed in `out/<abi>/liblyrebird.so.buildinfo`
  (the output of `go version -m`); their licences are recorded in NOTICE.md.

## Pins

| Component | Pin |
|---|---|
| lyrebird | tag `0.9.0`, commit `89d54872b176f936232ff39f7a8189b77d83d65e`, from `https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git`. The annotated tag carries a good signature (`GOODSIG`, `VALIDSIG`, made 2026-10-06) by Ruben Pollan (meskio), primary key `07948FFA64160A425BCD27EAC732B1D1C28F4E2F`, signing subkey `B3B33A7FF6695F35CC01047E52B8F5AC97A2DA86`. The copy of that key in tor-browser-build's `keyring/anti-censorship.gpg` has expired; the copy published on keys.openpgp.org (same fingerprint, valid until 2027-03-14), fetched on 2026-10-18 from `https://keys.openpgp.org/vks/v1/by-fingerprint/07948FFA64160A425BCD27EAC732B1D1C28F4E2F` and committed unchanged as `keys/07948FFA64160A425BCD27EAC732B1D1C28F4E2F.asc` (SHA-256 `51e92dd30cf4219c9dfce06f21e61903bcefb57ff09c5c7a7472b7e00796af63`, pinned in the script), verifies the tag. The script checks the signature itself on every build with `verify-signed-tag.sh`, which uses gpgv or, where gpgv is not installed, sqv, against that key alone, and refuses the checkout unless the tag points at the pinned commit and the signature is good and made by that primary key |
| `go.sum` | the file of that commit, SHA-256 `fa42d4b4701287b67f4e51bb52220d115fa2fa98f00cb4f8eb215a05a321965b` |
| Go | 1.26.9, the official `go1.26.9.linux-amd64.tar.gz`, SHA-256 `42d158b4d8f7b61ac0a830567c940a86098fb7aac52e467a5ebec03ef5cc2f8d` as listed at https://go.dev/dl/?mode=json; `GOTOOLCHAIN=local`. go1.26.9 is the newest 1.26 point release on 2026-10-18 |
| Go modules | exactly those named by `go.sum`, downloaded from `proxy.golang.org` with `-mod=readonly` (go.mod and go.sum cannot change, a module without a go.sum entry is refused) and checked by `go mod verify`; the build itself runs with `GOPROXY=off`. 61 modules are linked into each executable, and the script refuses an executable whose build information lists a module whose version and hash are not in `go.sum`, or a replaced module |
| Android NDK | r29 (`29.0.14206865`), zip SHA-256 `4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf`, the same zip and stamp file as the Tor build; the script refuses to run when its NDK pin differs from `../tor-android/build-tor-android.sh` |
| Android API | 21 (`aarch64-linux-android21-clang`, `armv7a-linux-androideabi21-clang`) |
| Go build | `CGO_ENABLED=1 GOOS=android GOARCH=arm64` (or `GOARCH=arm GOARM=7`), `CGO_CFLAGS=-O2`, `CGO_LDFLAGS=-Wl,-z,max-page-size=16384`, `go build -trimpath -buildvcs=false -buildmode=pie -ldflags "-s -w -checklinkname=0 -X main.lyrebirdVersion=0.9.0" ./cmd/lyrebird` |
| Pins | every pin in this table is a constant in the script; the environment chooses only locations (`ABIS`, `WORK`, `OUT`, `NDK_CACHE`, `ANDROID_NDK_R29`), and an NDK or Go toolchain directory is accepted only with the stamp of a hash this repository's scripts verified, with no override |
| Environment | every Go command runs under `env -i` with a fixed `PATH`, `HOME`, `GOROOT`, `GOPATH`, `GOMODCACHE`, `GOCACHE`, `LC_ALL=C`, `TZ=UTC`, `GOENV=off`, `GOWORK=off`, `GOFLAGS=-mod=readonly -modcacherw` and an empty `GOEXPERIMENT`; only a proxy setting of the host is passed through. The build cache is removed before each run |
| ABIs | arm64-v8a, armeabi-v7a |

`-checklinkname=0`: since Go 1.23 the linker refuses a `go:linkname`
reference into the standard library that the library does not mark as
allowed. `github.com/wlynxg/anet` 0.0.5, which the Pion WebRTC modules behind
snowflake use to list network interfaces on Android, refers to `net.zoneCache`;
without the flag the link fails with `invalid reference to net.zoneCache`
(checked with this script's inputs on 2026-10-18). The flag only lets the
linker accept such references, so the script checks what anet relies on before
it builds: `ipv6ZoneCache` must have exactly the fields `sync.RWMutex`,
`lastFetched time.Time`, `toIndex map[string]int` and `toName map[int]string`,
in that order, in the toolchain's `net/interface.go`, in x/net's
`internal/socket/sys_posix.go` and in anet's `interface_android.go`; the two
originals must declare `zoneCache` of that type, and anet's only `go:linkname`
targets must be `net.zoneCache` and x/net's `internal/socket.zoneCache`. A Go
release that changes the struct stops the build until anet's copy matches
again. In Go 1.26.9 the struct is at `src/net/interface.go` lines 182 to 187,
the same fields as anet 0.0.5's copy at lines 157 to 162.

The script asserts the commit after checkout, records the tag, commit, Go
release, NDK revision and `go.sum` hash in `out/SOURCES.txt`, the module
verification in `out/go-mod-verify.txt`, the build information of each
executable in `out/<abi>/liblyrebird.so.buildinfo` and its SHA-256 next to it.
It also refuses an output that is not an ELF executable of the ABI's class and
machine with Android's program interpreter (`/system/bin/linker64`,
`/system/bin/linker`), that still carries a symbol table or debug sections, or,
on arm64, whose loadable segments are not aligned to 16 KB.

After the build it writes `out/licenses.txt`: the licence and notice files of
lyrebird, of the Go standard library and runtime and of every linked module,
each taken from the module root and from every directory between the root and
a package the build links, read from the pinned sources, each distinct text
once after the list of the files that carry it. The script stops unless every
module in the build information has at least one such file and, for a build of
both ABIs inside the repository, unless the result equals the committed
`docs/licenses/lyrebird-modules.txt`, which NOTICE.md refers to.

## Recorded hashes

| File | SHA-256 | Size |
|---|---|---|
| `out/arm64-v8a/liblyrebird.so` | `9a89df982f1137b120fae152d46ab400558cef7d633987d5518c4ff24a088869` | 17674792 bytes |
| `out/armeabi-v7a/liblyrebird.so` | `1e521cb83a15fcbbe26a06683b3212d1a924d45c316dde46f0e0a9b25dd5e135` | 17014432 bytes |

The same values are the lyrebird pins in
`zerion-core-android/src/main/resources/org/zerionproject/tor/binaries.sha256`,
which the Gradle gate `verifyTorNativeArtifacts` checks before the files are
merged and the app checks before every Tor start, and in `TorBinaryPinsTest`.
A change of any pin above has to be accompanied by a rebuild in two
environments and an update of all three places.

## Verification

Each run started from an empty Go toolchain directory, module cache and build
cache. The runs of 2026-10-18 used the first revision of the script, which had
the same pins, environment and build commands but not the signature check,
the ipv6ZoneCache check or the licence texts, and read the pins from
overridable variables; the run of 2026-10-19 used the current revision.

- Three environments, byte-identical results: both executables and their build
  information were the same in all three.
  - `debian:trixie-slim` with `ca-certificates curl git unzip` added, as root,
    with `WORK=/build/lyrebird`.
  - `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` with no
    package added, as the build user `vagrant`, from an F-Droid style checkout
    (`/home/vagrant/build/com.professor.zerion`, run from `zerion-android/` as
    `NDK_CACHE=/build ../packaging/lyrebird-android/fdroid-build.sh`), with
    NDK r29 downloaded and verified inside the image.
  - A Debian trixie image without curl (the script fell back to wget), as
    root, with the scripts copied to another directory,
    `WORK=/var/tmp/elsewhere/work-dir`, and `GOFLAGS=-mod=mod`,
    `CGO_CFLAGS=-g`, `GOARM64=v8.5`, `GOEXPERIMENT=boringcrypto` and
    `GOPROXY=direct` exported by the caller; none of them reached the build.
  The first and the third used one NDK r29 directory that the third had
  downloaded and verified (same stamp); the second downloaded its own.
- The recipe's entry point: in the F-Droid image, as `vagrant`, from
  `zerion-android/`, `../packaging/tor-android/fdroid-build.sh` with the Tor
  build replaced by a stub ran this build after it, with the first revision
  and the shared NDK r29 directory, and installed the same two
  executables into `out/`.
- Current revision (2026-10-19): in the F-Droid image, as `vagrant`, from an
  F-Droid style checkout, with the shared NDK r29 directory, `fdroid-build.sh`
  verified the tag signature with
  gpgv, found the same ipv6ZoneCache fields in Go 1.26.9, x/net 0.59.0 and
  anet 0.0.5, rebuilt both executables with the recorded hashes and wrote the
  licence texts for 61 modules; a second run with that text in place as
  `docs/licenses/lyrebird-modules.txt` found it equal.
- Signature check (`verify-signed-tag.sh`, 2026-10-19), with gpgv in the F-Droid
  image and with sqv in `debian:trixie-slim`, which has no gpgv: the pinned tag
  verifies; a wrong commit, a wrong fingerprint, a tag object with a changed
  tagger date and a different key are each refused; with the clock moved to
  2028 and to 2035 by faketime, after the key's expiry, the tag still verifies
  (gpgv reports `EXPKEYSIG` with `VALIDSIG` for the pinned key).
- Build information (`go version -m`, in `out/<abi>/liblyrebird.so.buildinfo`):
  `go1.26.9`, `-buildmode=pie`, `-compiler=gc`, `-trimpath=true`,
  `CGO_ENABLED=1`, `GOOS=android`, `GOARCH=arm64 GOARM64=v8.0` or
  `GOARCH=arm GOARM=7`, and 61 modules, each with the version and hash its
  `go.sum` line gives, none replaced. With `-trimpath` the go command does not
  record `-ldflags` or the `CGO_` flags, because they could name host paths.
- Checksum database: every line of the pinned `go.sum` (269 lines for 194
  module versions, 75 with a module hash and 119 with only a go.mod hash) was
  checked against `sum.golang.org`. Each module version was requested outside
  any module, where no `go.sum` applies, so the go command verified it against
  the checksum database, and the hash it reported, or for a go.mod line the
  hash of the go.mod file it verified, equals the `go.sum` line: 194 of 194
  matched.
- Toolchain proof: the same NDK r29 zip and the official Go 1.22.12 archive
  (`go1.22.12.linux-amd64.tar.gz`, SHA-256
  `4fa4f869b0f7fc6bb1eb2660e74657fbf04cdd290b5aef905585c86051b34d43`), used
  with the paths and flags of the publisher's go-reproducer for lyrebird 0.6.2
  (Go at `/tmp/go`, `GOPATH=/tmp/go-path`, the source at `/tmp/lyrebird` at
  commit `a74149c066d0269326bd49900218744f4d0890fb`, `go build -buildmode=pie
  -ldflags "-w -s -extldflags=-pie -X main.lyrebirdVersion=0.6.2" -asmflags
  -trimpath`), reproduced the executables of the jar the app shipped up to
  3.0.17 byte for byte: `34e258346e12648b7206941cd4af790ad61ca4c663e47426f3631ee98870dc5c`
  (arm64-v8a) and `ec54b954399a75cddbc76862fbd887d91714531e21be03795708483809a2e7ed`
  (armeabi-v7a), the values the pin file held until this change.
- Runtime: on 2026-10-19 the 0.9.0 executable from this build ran next to Tor
  on the Android 15 phone with the built in obfs4 bridges; the app
  bootstrapped and stayed Online through them. Snowflake, meek and webtunnel
  were not yet exercised on a phone. The checks still to run are a direct
  Tor bootstrap without bridges, each bundled default obfs4 line on its own,
  the snowflake lines after consent, the meek line, a pasted webtunnel line and
  a pasted plain line, a mixed set of transports served by one lyrebird
  process, network changes while connected through a bridge, and messaging
  between two phones over obfs4.

## How to rebuild

On any x86_64 Debian host, or in a container of `debian:trixie` or
`registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie`, with
`ca-certificates curl git tar gzip unzip` (wget instead of curl works too) and
gpgv or sqv, one of which every Debian system has because apt depends on it:

    ./build-lyrebird-android.sh

writes `out/<abi>/liblyrebird.so`, `out/SOURCES.txt` and the build
information. `fdroid-build.sh` is the same build with the F-Droid defaults;
`../tor-android/fdroid-build.sh`, the recipe's Tor step, runs it after the Tor
build with the same `NDK_CACHE`, so the NDK r29 that step verified is used
again and the recipe needs no line of its own. The Gradle build copies the two
executables from `out/` into its own directory and verifies them; nothing is
read from the source tree at run time.
