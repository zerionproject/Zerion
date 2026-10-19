#!/usr/bin/env bash
# Verifies that an annotated tag in a local git clone points at the expected
# commit and carries a good OpenPGP signature made by the expected primary key,
# checked against nothing but the given key file. It uses gpgv when it is
# installed and sqv otherwise; Debian's apt depends on gpgv, or since trixie on
# gpgv or sqv, so every Debian host and F-Droid's build server image has one. gpgv runs with a
# private, temporary home and a keyring holding only that key, so no key or
# trust setting of the host takes part. A signature made while the key was
# valid still verifies after the key expires; a bad signature, a signature by
# any other key, a revoked key, an unsigned tag or a missing verifier fails.
#
# Usage: verify-signed-tag.sh <clone> <tag> <commit> <key.asc> <fingerprint>
set -euo pipefail

[ "$#" -eq 5 ] || { echo "usage: $0 <clone> <tag> <commit> <key.asc> <fingerprint>" >&2; exit 2; }
clone="$1" tag="$2" commit="$3" key="$4" fpr="$5"

die() { echo "verify-signed-tag: $*" >&2; exit 1; }

[ "$(git -C "${clone}" cat-file -t "refs/tags/${tag}")" = tag ] || die "${tag} is not an annotated tag"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT
git -C "${clone}" cat-file tag "refs/tags/${tag}" > "${tmp}/tag"
[ "$(sed -n '1s|^object ||p' "${tmp}/tag")" = "${commit}" ] || die "tag ${tag} does not point at ${commit}"
grep -qx "tag ${tag}" "${tmp}/tag" || die "the tag object of ${tag} names another tag"

start="$(grep -n -x -m1 -e '-----BEGIN PGP SIGNATURE-----' "${tmp}/tag" | cut -d: -f1)"
[ -n "${start}" ] || die "tag ${tag} is not signed"
head -n "$((start - 1))" "${tmp}/tag" > "${tmp}/payload"
tail -n "+${start}" "${tmp}/tag" > "${tmp}/signature.asc"

grep -qx -e '-----BEGIN PGP PUBLIC KEY BLOCK-----' "${key}" || die "${key} is not an armored public key"
sed -e '1,/^$/d' -e '/^=/d' -e '/^-----END PGP PUBLIC KEY BLOCK-----$/,$d' "${key}" | base64 -d > "${tmp}/key.gpg"

if command -v gpgv > /dev/null 2>&1; then
	mkdir -m 700 "${tmp}/home"
	LC_ALL=C gpgv --homedir "${tmp}/home" --status-fd 1 --keyring "${tmp}/key.gpg" \
		"${tmp}/signature.asc" "${tmp}/payload" > "${tmp}/status" 2> "${tmp}/log" || true
	if grep -qE '^\[GNUPG:\] (BADSIG|ERRSIG|REVKEYSIG|EXPSIG|NO_PUBKEY) ' "${tmp}/status" \
		|| ! grep -qE '^\[GNUPG:\] (GOODSIG|EXPKEYSIG) ' "${tmp}/status"; then
		die "gpgv did not verify ${tag}: $(tr '\n' ' ' < "${tmp}/log")"
	fi
	primary="$(awk '$1 == "[GNUPG:]" && $2 == "VALIDSIG" { print $NF }' "${tmp}/status")"
	[ "${primary}" = "${fpr}" ] || die "${tag} is signed by ${primary:-an unknown key}, not by ${fpr}"
	verifier=gpgv
elif command -v sqv > /dev/null 2>&1; then
	LC_ALL=C sqv --keyring "${tmp}/key.gpg" --signature-file "${tmp}/signature.asc" \
		"${tmp}/payload" > "${tmp}/status" 2> "${tmp}/log" \
		|| die "sqv did not verify ${tag}: $(tr '\n' ' ' < "${tmp}/log")"
	grep -qix "${fpr}" "${tmp}/status" || die "${tag} is signed by $(tr '\n' ' ' < "${tmp}/status"), not by ${fpr}"
	verifier=sqv
else
	die "neither gpgv nor sqv is installed"
fi
echo "verified tag ${tag} at ${commit}: good signature by ${fpr} (${verifier})"
