#!/usr/bin/env bash
# Builds the lyrebird pluggable-transport executable the app runs
# (liblyrebird.so, one per shipped ABI) from the upstream release tag at the
# pinned commit, after verifying the tag's OpenPGP signature against the
# committed key of the pinned fingerprint, with the official Go toolchain and
# Android NDK r29, both verified by hash, and with the Go modules the go.sum of
# that tag names, downloaded from the Go module proxy, refused unless they
# match go.sum, and verified again before the build, which runs with the proxy
# off. Every pin is fixed in this file, asserted, and the build aborts on any
# mismatch. The output is byte-reproducible against the values recorded in
# PROVENANCE.md: -trimpath removes every host path, the build ID is derived
# from the inputs, and every Go command runs in a fixed, minimal environment
# (env -i), so nothing the host exports can change the result. The same script
# runs in F-Droid's build server and on any Debian host with the packages
# listed in PROVENANCE.md.
#
# Usage:
#   ./build-lyrebird-android.sh                 builds arm64-v8a and armeabi-v7a
#   ABIS="arm64-v8a" ./build-lyrebird-android.sh
#   OUT=/some/dir ./build-lyrebird-android.sh   writes <OUT>/<abi>/liblyrebird.so
#
# Environment: only locations can be chosen, never a pin. ANDROID_NDK_R29 may
# point at an unpacked NDK r29; otherwise the NDK is taken from NDK_CACHE
# (default /opt), where it is downloaded and verified by SHA-256 when it is not
# there yet. An NDK is accepted only with the stamp the download step leaves,
# the same stamp packaging/tor-android/build-tor-android.sh writes, so the two
# builds can share one verified NDK. WORK (default /build/lyrebird) holds the
# Go toolchain, the module cache, the build cache and the source checkout; it
# must be an absolute path.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

LYREBIRD_TAG=0.9.0
LYREBIRD_COMMIT=89d54872b176f936232ff39f7a8189b77d83d65e
LYREBIRD_URL=https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird.git
LYREBIRD_VERSION="${LYREBIRD_TAG#lyrebird-}"
LYREBIRD_KEY_FINGERPRINT=07948FFA64160A425BCD27EAC732B1D1C28F4E2F
LYREBIRD_KEY_SHA256=51e92dd30cf4219c9dfce06f21e61903bcefb57ff09c5c7a7472b7e00796af63
LYREBIRD_KEY="${HERE}/keys/${LYREBIRD_KEY_FINGERPRINT}.asc"

GO_VERSION=1.26.9
GO_SHA256=42d158b4d8f7b61ac0a830567c940a86098fb7aac52e467a5ebec03ef5cc2f8d
GO_URL="https://dl.google.com/go/go${GO_VERSION}.linux-amd64.tar.gz"

NDK_URL=https://dl.google.com/android/repository/android-ndk-r29-linux.zip
NDK_SHA256=4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf
NDK_REVISION=29.0.14206865
NDK_API=21

# -checklinkname=0 lets github.com/wlynxg/anet reach two unexported variables,
# net.zoneCache and golang.org/x/net/internal/socket.zoneCache, through
# go:linkname and its own copy of their type. That is memory safe only while
# the copy has exactly the fields of the originals, so the build stops unless
# the toolchain's net package, the pinned x/net and anet all declare these
# fields in this order and anet links nothing else.
ZONE_CACHE_FIELDS="sync.RWMutex|lastFetched time.Time|toIndex map[string]int|toName map[int]string"
ANET_LINKNAMES="zoneCache net.zoneCache|zoneCacheX golang.org/x/net/internal/socket.zoneCache|"

ABIS="${ABIS:-arm64-v8a armeabi-v7a}"
WORK="${WORK:-/build/lyrebird}"
OUT="${OUT:-${HERE}/out}"
NDK_CACHE="${NDK_CACHE:-/opt}"
LICENSES_COPY="${HERE}/../../docs/licenses/lyrebird-modules.txt"

say() { echo "=== lyrebird-android $(date -u +%H:%M:%S) $*"; }
die() { echo "lyrebird-android: $*" >&2; exit 1; }

case "${WORK}" in
	/*) ;;
	*) die "WORK must be an absolute path, got ${WORK}" ;;
esac
[ "$(uname -s) $(uname -m)" = "Linux x86_64" ] || die "the pinned Go toolchain is the linux-amd64 archive; this host is $(uname -s) $(uname -m)"

TOR_SCRIPT="${HERE}/../tor-android/build-tor-android.sh"
if [ -f "${TOR_SCRIPT}" ]; then
	grep -qx "NDK_SHA256=${NDK_SHA256}" "${TOR_SCRIPT}" || die "the NDK pin differs from ${TOR_SCRIPT}; change both together"
	grep -qx "NDK_REVISION=${NDK_REVISION}" "${TOR_SCRIPT}" || die "the NDK revision differs from ${TOR_SCRIPT}; change both together"
fi

download() {
	local url="$1" dest="$2"
	if command -v curl > /dev/null 2>&1; then
		curl -sS -f -L --retry 3 -o "${dest}" "${url}"
	else
		wget -q -O "${dest}" "${url}"
	fi
}

# The same rule as the Tor build: an unpacked NDK is accepted only when it was
# unpacked from a zip whose SHA-256 a build script of this repository
# verified, recorded in a stamp file inside the NDK directory. There is no
# override.
require_verified_ndk() {
	local stamp="$1/.zerion-ndk-zip.sha256"
	[ -f "${stamp}" ] && [ "$(cat "${stamp}")" = "${NDK_SHA256}" ] \
		|| die "NDK at $1 was not unpacked from a zip with SHA-256 ${NDK_SHA256} (no matching .zerion-ndk-zip.sha256 stamp); remove it to re-download"
}

prepare_ndk() {
	if [ -n "${ANDROID_NDK_R29:-}" ]; then
		ANDROID_NDK_HOME="${ANDROID_NDK_R29}"
		require_verified_ndk "${ANDROID_NDK_HOME}"
		return
	fi
	ANDROID_NDK_HOME="${NDK_CACHE}/android-ndk-r29"
	if [ -d "${ANDROID_NDK_HOME}" ]; then
		require_verified_ndk "${ANDROID_NDK_HOME}"
		return
	fi
	say "downloading NDK r29"
	mkdir -p "${NDK_CACHE}"
	download "${NDK_URL}" "${NDK_CACHE}/android-ndk-r29.zip"
	echo "${NDK_SHA256}  ${NDK_CACHE}/android-ndk-r29.zip" | sha256sum -c - > /dev/null
	unzip -q "${NDK_CACHE}/android-ndk-r29.zip" -d "${NDK_CACHE}"
	rm -f "${NDK_CACHE}/android-ndk-r29.zip"
	echo "${NDK_SHA256}" > "${ANDROID_NDK_HOME}/.zerion-ndk-zip.sha256"
}

# The Go toolchain is the official linux-amd64 archive of the pinned release,
# verified by SHA-256 and unpacked by this script into WORK, with the same kind
# of stamp as the NDK. A toolchain directory without the stamp is refused.
prepare_go() {
	GOROOT_DIR="${WORK}/go${GO_VERSION}"
	local stamp="${GOROOT_DIR}/.zerion-go-archive.sha256"
	if [ -d "${GOROOT_DIR}" ]; then
		[ -f "${stamp}" ] && [ "$(cat "${stamp}")" = "${GO_SHA256}" ] || die "Go toolchain at ${GOROOT_DIR} was not unpacked by this script from an archive with SHA-256 ${GO_SHA256}; remove it to re-download"
		return
	fi
	say "downloading Go ${GO_VERSION}"
	local archive="${WORK}/go${GO_VERSION}.linux-amd64.tar.gz" tmp="${WORK}/go-unpack"
	rm -rf "${tmp}" "${archive}"
	download "${GO_URL}" "${archive}"
	echo "${GO_SHA256}  ${archive}" | sha256sum -c - > /dev/null
	mkdir -p "${tmp}"
	tar -xzf "${archive}" -C "${tmp}" --no-same-owner
	rm -f "${archive}"
	echo "${GO_SHA256}" > "${tmp}/go/.zerion-go-archive.sha256"
	mv "${tmp}/go" "${GOROOT_DIR}"
	rmdir "${tmp}"
}

# Every Go command runs with exactly this environment and nothing inherited
# from the host, apart from a proxy the host may need to reach the network.
# GOTOOLCHAIN=local forbids a toolchain download, -mod=readonly forbids any
# change to go.mod and go.sum, and GOENV=off ignores any go env file.
PASS_ENV=()
for v in http_proxy https_proxy no_proxy HTTP_PROXY HTTPS_PROXY NO_PROXY; do
	if [ -n "${!v:-}" ]; then
		PASS_ENV+=("${v}=${!v}")
	fi
done

go_env() {
	env -i \
		PATH="${GOROOT_DIR}/bin:/usr/local/bin:/usr/bin:/bin" \
		HOME="${WORK}/home" \
		LANG=C LC_ALL=C TZ=UTC \
		GOROOT="${GOROOT_DIR}" \
		GOPATH="${WORK}/gopath" \
		GOMODCACHE="${WORK}/gopath/pkg/mod" \
		GOCACHE="${WORK}/gocache" \
		GOENV=off GOWORK=off GOTOOLCHAIN=local \
		GOFLAGS="-mod=readonly -modcacherw" \
		GOPROXY=https://proxy.golang.org GOSUMDB=sum.golang.org \
		GONOSUMDB= GONOPROXY= GOPRIVATE= GOINSECURE= GOEXPERIMENT= \
		${PASS_ENV[@]+"${PASS_ENV[@]}"} \
		"$@"
}

abi_settings() {
	case "$1" in
		arm64-v8a)
			GOARCH_ABI=arm64; GOARM_ABI=; CC_ABI="aarch64-linux-android${NDK_API}-clang"
			CLASS=ELF64; MACHINE=AArch64; INTERP=/system/bin/linker64; ALIGN=0x4000
			;;
		armeabi-v7a)
			GOARCH_ABI=arm; GOARM_ABI=7; CC_ABI="armv7a-linux-androideabi${NDK_API}-clang"
			CLASS=ELF32; MACHINE=ARM; INTERP=/system/bin/linker; ALIGN=
			;;
		*) die "unsupported ABI $1" ;;
	esac
}

go_abi() {
	go_env GOPROXY=off \
		CGO_ENABLED=1 GOOS=android GOARCH="${GOARCH_ABI}" ${GOARM_ABI:+GOARM=${GOARM_ABI}} \
		CC="${TOOLCHAIN}/${CC_ABI}" \
		CGO_CFLAGS=-O2 \
		CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
		"$@"
}

fetch() {
	local dir="$1" url="$2" ref="$3" want="$4"
	rm -rf "${dir}"
	GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
		git -c advice.detachedHead=false -c core.autocrlf=false clone -q --branch "${ref}" --depth 1 "${url}" "${dir}"
	local got
	got="$(git -C "${dir}" rev-parse HEAD)"
	[ "${got}" = "${want}" ] || die "pin mismatch for ${ref}: expected ${want}, got ${got}"
	[ -z "$(git -C "${dir}" status --porcelain)" ] || die "the checkout of ${ref} is not clean"
	echo "lyrebird ${ref} ${got}" >> "${OUT}/SOURCES.txt"
	say "source lyrebird ${ref} ${got}"
}

zone_cache_fields() {
	awk -v slashes="/""/" '
		$0 == "type ipv6ZoneCache struct {" { inside = 1; next }
		inside && $0 == "}" { exit }
		inside {
			p = index($0, slashes)
			if (p > 0) $0 = substr($0, 1, p - 1)
			gsub(/[ \t]+/, " ")
			sub(/^ /, "")
			sub(/ $/, "")
			if ($0 != "") { printf "%s%s", sep, $0; sep = "|" }
		}
	' "$1"
}

check_zone_cache() {
	local xnet anet f got
	xnet="$(cd "${SRC}" && go_env GOPROXY=off go list -m -f '{{.Dir}}' golang.org/x/net)"
	anet="$(cd "${SRC}" && go_env GOPROXY=off go list -m -f '{{.Dir}}' github.com/wlynxg/anet)"
	for f in "${GOROOT_DIR}/src/net/interface.go" "${xnet}/internal/socket/sys_posix.go" "${anet}/interface_android.go"; do
		got="$(zone_cache_fields "${f}")"
		[ "${got}" = "${ZONE_CACHE_FIELDS}" ] || die "ipv6ZoneCache in ${f#"${WORK}"/} has the fields '${got}', expected '${ZONE_CACHE_FIELDS}'; anet's go:linkname copy would not match"
	done
	grep -qx "var zoneCache = ipv6ZoneCache{" "${GOROOT_DIR}/src/net/interface.go" || die "net.zoneCache is not an ipv6ZoneCache in go${GO_VERSION}"
	grep -qx "var zoneCache = ipv6ZoneCache{" "${xnet}/internal/socket/sys_posix.go" || die "x/net internal/socket.zoneCache is not an ipv6ZoneCache"
	grep -qx "var zoneCache ipv6ZoneCache" "${anet}/interface_android.go" || die "anet's zoneCache is not an ipv6ZoneCache"
	grep -qx "var zoneCacheX ipv6ZoneCache" "${anet}/interface_android.go" || die "anet's zoneCacheX is not an ipv6ZoneCache"
	got="$(find "${anet}" -name '*.go' -exec cat {} + | sed -n 's|^[/][/]go:linkname \([^ ]*\) \([^ ]*\)$|\1 \2|p' | LC_ALL=C sort | tr '\n' '|')"
	[ "${got}" = "${ANET_LINKNAMES}" ] || die "anet links '${got}', expected '${ANET_LINKNAMES}'"
	say "ipv6ZoneCache has the same fields in go${GO_VERSION} net, x/net and anet"
}

check_build_info() {
	local abi="$1" bin="$2" info="${OUT}/$1/liblyrebird.so.buildinfo"
	go_env go version -m "${bin}" | sed "1s|^[^:]*: |liblyrebird.so: |" > "${info}"
	[ "$(head -n1 "${info}")" = "liblyrebird.so: go${GO_VERSION}" ] || die "${abi}: not built by go${GO_VERSION}: $(head -n1 "${info}")"
	local want
	for want in \
		"path	gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/cmd/lyrebird" \
		"build	-buildmode=pie" \
		"build	-trimpath=true" \
		"build	CGO_ENABLED=1" \
		"build	GOOS=android" \
		"build	GOARCH=${GOARCH_ABI}"; do
		grep -qxF "	${want}" "${info}" || die "${abi}: build info lacks '${want}'"
	done
	if [ -n "${GOARM_ABI}" ]; then
		grep -qxF "	build	GOARM=${GOARM_ABI}" "${info}" || die "${abi}: build info lacks GOARM=${GOARM_ABI}"
	fi
	local kind path ver sum deps=0
	while read -r kind path ver sum; do
		case "${kind}" in
			dep)
				grep -qxF "${path} ${ver} ${sum}" "${SRC}/go.sum" || die "${abi}: module ${path} ${ver} ${sum} is not in go.sum"
				deps=$((deps + 1))
				;;
			"=>") die "${abi}: the build replaced a module: ${path} ${ver}" ;;
		esac
	done < "${info}"
	[ "${deps}" -gt 0 ] || die "${abi}: build info lists no modules"
	say "${abi} build info: go${GO_VERSION}, ${deps} modules, each matching go.sum"
}

check_elf() {
	local abi="$1" bin="$2"
	local header segments
	header="$("${READELF}" -h "${bin}")"
	segments="$("${READELF}" -lW "${bin}")"
	grep -qE "^ +Class: +${CLASS}$" <<< "${header}" || die "${abi}: not ${CLASS}"
	grep -qE "^ +Machine: +${MACHINE}$" <<< "${header}" || die "${abi}: machine is not ${MACHINE}"
	grep -qE "^ +Type: +DYN " <<< "${header}" || die "${abi}: not a position-independent executable"
	grep -qF "[Requesting program interpreter: ${INTERP}]" <<< "${segments}" || die "${abi}: interpreter is not ${INTERP}"
	if [ -n "${ALIGN}" ]; then
		local bad
		bad="$(awk '$1 == "LOAD" && $NF != "'"${ALIGN}"'"' <<< "${segments}")"
		[ -z "${bad}" ] || die "${abi}: a LOAD segment is not aligned to ${ALIGN}: ${bad}"
	fi
	if "${READELF}" -SW "${bin}" | grep -qE '\.debug_|\.symtab'; then
		die "${abi}: the executable is not stripped"
	fi
}

# The licence and notice files of everything linked into the executables, from
# the pinned sources: the Go standard library and runtime, lyrebird and every
# module the build links, taking each file at the module root and in every
# directory between the root and a package the build links. Each distinct
# text appears once, after the list of the files that carry it, with line
# endings normalised to LF. The result is out/licenses.txt; when the
# repository is present it must equal docs/licenses/lyrebird-modules.txt.
collect_licenses() {
	local entries="${WORK}/licenses.entries" hashes="${WORK}/licenses.hashes"
	local out="${OUT}/licenses.txt" abi mpath mver mdir pdir d f rel h
	: > "${entries}"
	for abi in ${ABIS}; do
		abi_settings "${abi}"
		(cd "${SRC}" && go_abi go list -deps -f '{{if .Module}}{{.Module.Path}}|{{.Module.Version}}|{{.Module.Dir}}|{{.Dir}}{{end}}' ./cmd/lyrebird) \
			| while IFS='|' read -r mpath mver mdir pdir; do
				[ -n "${mpath}" ] || continue
				[ -n "${mver}" ] || mver="${LYREBIRD_VERSION}"
				d="${pdir}"
				while :; do
					find "${d}" -maxdepth 1 -type f ! -name '*.go' \( -iname 'licen[cs]e*' -o -iname 'copying*' -o -iname 'notice*' -o -iname 'patents*' -o -iname 'unlicense*' \) \
						| while read -r f; do echo "${mpath}|${mver}|${f#"${mdir}"/}|${f}"; done
					[ "${d}" != "${mdir}" ] || break
					d="$(dirname "${d}")"
					case "${d}" in
						"${mdir}"|"${mdir}"/*) ;;
						*) die "package directory ${pdir} is outside its module ${mdir}" ;;
					esac
				done
			done >> "${entries}"
	done
	for f in LICENSE PATENTS; do
		echo "Go standard library and runtime|go${GO_VERSION}|${f}|${GOROOT_DIR}/${f}" >> "${entries}"
	done
	LC_ALL=C sort -u -o "${entries}" "${entries}"

	local linked listed
	linked="$(for abi in ${ABIS}; do cat "${OUT}/${abi}/liblyrebird.so.buildinfo"; done | awk '$1 == "dep" { print $2 " " $3 }' | LC_ALL=C sort -u)"
	listed="$(grep -v -e '^Go standard library and runtime|' -e '^gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird|' "${entries}" | cut -d'|' -f1,2 | tr '|' ' ' | LC_ALL=C sort -u)"
	[ "${linked}" = "${listed}" ] || die "the modules with licence files differ from the linked modules: $(diff <(echo "${linked}") <(echo "${listed}") | tr '\n' ' ')"
	grep -q '^gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird|' "${entries}" || die "no licence file of lyrebird itself"

	while IFS='|' read -r mpath mver rel f; do
		h="$(tr -d '\r' < "${f}" | sha256sum | cut -c1-64)"
		echo "${h}|${mpath}|${mver}|${rel}|${f}"
	done < "${entries}" > "${hashes}"

	local rule="================================================================================"
	{
		echo "Licence and notice texts of the lyrebird executable (liblyrebird.so)"
		echo
		echo "Written by packaging/lyrebird-android/build-lyrebird-android.sh from the"
		echo "pinned sources: lyrebird ${LYREBIRD_VERSION} at commit ${LYREBIRD_COMMIT},"
		echo "Go ${GO_VERSION} and the modules of lyrebird's go.sum that the build links"
		echo "for ${ABIS// / and }. Each file is taken from the module root and"
		echo "from every directory between the root and a linked package. Each distinct"
		echo "text appears once, after a line of = signs and the list of the files that"
		echo "carry it; line endings are normalised to LF."
		echo
		echo "Linked modules ($(echo "${linked}" | wc -l)):"
		echo "${linked}" | sed 's/^/  /'
		awk -F'|' '!seen[$1]++ { print $1 "|" $5 }' "${hashes}" | while IFS='|' read -r h f; do
			echo
			echo "${rule}"
			echo "The text below is in:"
			grep "^${h}|" "${hashes}" | cut -d'|' -f2-4 | tr '|' ' ' | sed 's/^/  /'
			echo "${rule}"
			echo
			tr -d '\r' < "${f}" | sed -e '$a\'
		done
	} > "${out}"
	say "licence texts of $(echo "${linked}" | wc -l) modules, lyrebird and Go written to ${out}"
	if [ "${ABIS}" != "arm64-v8a armeabi-v7a" ]; then
		say "not every shipped ABI was built; the texts are only in ${out}"
	elif [ -f "${LICENSES_COPY}" ]; then
		cmp -s "${out}" "${LICENSES_COPY}" || die "out/licenses.txt differs from docs/licenses/lyrebird-modules.txt; review it and replace the committed copy"
		say "licence texts match docs/licenses/lyrebird-modules.txt"
	else
		say "docs/licenses/lyrebird-modules.txt is not next to this script; the texts are only in ${out}"
	fi
}

umask 022
[ "$(sha256sum "${LYREBIRD_KEY}" | cut -d' ' -f1)" = "${LYREBIRD_KEY_SHA256}" ] || die "${LYREBIRD_KEY} does not have SHA-256 ${LYREBIRD_KEY_SHA256}"
prepare_ndk
export ANDROID_NDK_HOME
grep -q "Pkg.Revision = ${NDK_REVISION}" "${ANDROID_NDK_HOME}/source.properties" || die "NDK at ${ANDROID_NDK_HOME} is not ${NDK_REVISION}"
TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin"
READELF="${TOOLCHAIN}/llvm-readelf"
say "NDK ${NDK_REVISION} at ${ANDROID_NDK_HOME}"

mkdir -p "${WORK}" "${OUT}"
rm -rf "${WORK}/gocache" "${WORK}/bin" "${WORK}/home"
mkdir -p "${WORK}/home"
: > "${OUT}/SOURCES.txt"

prepare_go
[ "$(go_env go version)" = "go version go${GO_VERSION} linux/amd64" ] || die "unexpected Go toolchain: $(go_env go version)"
go_env go telemetry off
echo "go ${GO_VERSION} ${GO_SHA256}" >> "${OUT}/SOURCES.txt"
echo "ndk ${NDK_REVISION} ${NDK_SHA256}" >> "${OUT}/SOURCES.txt"
say "Go ${GO_VERSION} at ${GOROOT_DIR}"

SRC="${WORK}/lyrebird"
fetch "${SRC}" "${LYREBIRD_URL}" "${LYREBIRD_TAG}" "${LYREBIRD_COMMIT}"
"${HERE}/verify-signed-tag.sh" "${SRC}" "${LYREBIRD_TAG}" "${LYREBIRD_COMMIT}" "${LYREBIRD_KEY}" "${LYREBIRD_KEY_FINGERPRINT}" | tee -a "${OUT}/SOURCES.txt"
echo "go.sum $(sha256sum "${SRC}/go.sum" | cut -d' ' -f1)" >> "${OUT}/SOURCES.txt"

say "downloading and verifying Go modules"
(cd "${SRC}" && go_env go mod download)
(cd "${SRC}" && go_env go mod verify) | tee "${OUT}/go-mod-verify.txt"
grep -qx "all modules verified" "${OUT}/go-mod-verify.txt" || die "go mod verify did not verify every module"
git -C "${SRC}" diff --quiet -- go.mod go.sum || die "go.mod or go.sum changed during the module download"
check_zone_cache

for ABI in ${ABIS}; do
	abi_settings "${ABI}"
	say "building ${ABI}"
	BIN="${WORK}/bin/${ABI}/liblyrebird.so"
	mkdir -p "${WORK}/bin/${ABI}" "${OUT}/${ABI}"
	rm -f "${OUT}/${ABI}/liblyrebird.so" "${OUT}/${ABI}/liblyrebird.so.sha256"
	(cd "${SRC}" && go_abi \
		go build -trimpath -buildvcs=false -buildmode=pie \
		-ldflags "-s -w -checklinkname=0 -X main.lyrebirdVersion=${LYREBIRD_VERSION}" \
		-o "${BIN}" ./cmd/lyrebird)
	check_elf "${ABI}" "${BIN}"
	check_build_info "${ABI}" "${BIN}"
	install -m644 "${BIN}" "${OUT}/${ABI}/liblyrebird.so"
	touch --no-dereference -t 201001010000.00 "${OUT}/${ABI}/liblyrebird.so"
	sha256sum "${OUT}/${ABI}/liblyrebird.so" | cut -d' ' -f1 > "${OUT}/${ABI}/liblyrebird.so.sha256"
	say "RESULT ${ABI} $(cat "${OUT}/${ABI}/liblyrebird.so.sha256") $(stat -c %s "${OUT}/${ABI}/liblyrebird.so")"
done
collect_licenses
say "done"
