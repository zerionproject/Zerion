#!/usr/bin/env python3
"""Compares the payload of a Play bundle with the payload of the APK built
from the same sources, entry by entry, and reports every difference.

A bundle stores the base module under base/: base/dex/classes*.dex,
base/lib/<abi>/*.so, base/assets/..., base/root/... (files the APK keeps
at its root such as META-INF/services and the bundled text resources),
base/res/..., base/manifest/AndroidManifest.xml and base/resources.pb.
The APK stores the same payload at its root. The manifest and the
resource table are compiled differently for the two formats and are compared
by presence only; the res/ tree is compared by entry count, because the
APK's resource optimisation shortens every resource file name; everything
else must be byte-identical for the bundle to be the APK's payload in
another wrapper.

Usage:
    scripts/compare-bundle-apk.py <bundle.aab> <app.apk> [--all]

Exit status 0 when every comparable entry matches, 1 otherwise. Signature
blocks (META-INF/*.RSA, *.SF, MANIFEST.MF, BNDLTOOL.*) are ignored, since the
two artefacts are signed separately.
"""
import hashlib
import sys
import zipfile

IGNORED_SUFFIXES = (".RSA", ".SF", ".DSA", ".EC", "MANIFEST.MF")
PRESENCE_ONLY = ("AndroidManifest.xml", "resources.arsc", "resources.pb")


def presence_only(name):
    """Entries the two formats compile differently: the manifest, the resource
    table and every compiled XML resource (protobuf in a bundle, binary XML in
    an APK). Their presence is compared, their bytes are not."""
    if name.endswith(PRESENCE_ONLY):
        return True
    return name.startswith("res/") and name.endswith(".xml")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def apk_path(bundle_name):
    if not bundle_name.startswith("base/"):
        return None
    rest = bundle_name[len("base/"):]
    if rest.startswith("dex/"):
        return rest[len("dex/"):]
    if rest.startswith("root/"):
        return rest[len("root/"):]
    if rest.startswith("manifest/"):
        return rest[len("manifest/"):]
    if rest in ("resources.pb", "native.pb", "assets.pb"):
        return rest
    return rest


def ignored(name):
    if name.startswith("META-INF/") and name.endswith(IGNORED_SUFFIXES):
        return True
    if name.startswith("META-INF/BNDLTOOL") or name.startswith("BUNDLE-METADATA/"):
        return True
    return name in ("base/native.pb", "base/assets.pb", "base/resources.pb",
                    "resources.arsc")


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    show_all = "--all" in argv
    with zipfile.ZipFile(argv[1]) as bundle, zipfile.ZipFile(argv[2]) as apk:
        apk_entries = {i.filename: i for i in apk.infolist() if not i.is_dir()}
        differing = []
        matched = 0
        presence = []
        missing = []
        seen = set()
        bundle_res = sum(1 for i in bundle.infolist()
                         if i.filename.startswith("base/res/") and not i.is_dir())
        apk_res = sum(1 for n in apk_entries if n.startswith("res/"))
        for info in bundle.infolist():
            if info.is_dir() or ignored(info.filename):
                continue
            target = apk_path(info.filename)
            if target is None:
                continue
            if target.startswith("res/"):
                continue
            seen.add(target)
            if target not in apk_entries:
                missing.append(("bundle only", info.filename))
                continue
            if presence_only(target):
                presence.append(target)
                continue
            a = bundle.read(info.filename)
            b = apk.read(target)
            if a == b:
                matched += 1
                if show_all:
                    print("same     ", target, len(a))
            else:
                differing.append((target, len(a), sha256(a), len(b), sha256(b)))
        for name in apk_entries:
            if name in seen or ignored(name) or name.startswith("res/"):
                continue
            if name.startswith("META-INF/") and (name.endswith(".SF") or name.endswith(".RSA")):
                continue
            missing.append(("apk only", name))
    print("matched entries:", matched)
    print("compared by presence only:", ", ".join(sorted(presence)))
    print("resource entries (names are shortened by the APK's resource"
          " optimisation, so only the count is compared): bundle", bundle_res,
          "apk", apk_res)
    if bundle_res != apk_res:
        differing.append(("res/ (count)", bundle_res, "", apk_res, ""))
    for target, la, ha, lb, hb in differing:
        print("DIFFERS  ", target, "bundle", la, ha[:16], "apk", lb, hb[:16])
    for kind, name in missing:
        print("ONLY IN  ", kind, name)
    print("differing entries:", len(differing), "unmatched entries:", len(missing))
    return 0 if not differing and not missing else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
