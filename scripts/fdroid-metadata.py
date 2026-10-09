#!/usr/bin/env python3
"""Writes F-Droid metadata that builds the checked-out commit.

Takes the build recipe of the newest entry in fdroiddata's
metadata/com.professor.zerion.yml, so the reference build uses exactly the
recipe F-Droid builds with, and points it at the current commit and version.
The recipe may not remove gradle/verification-metadata.xml.

Usage: fdroid-metadata.py <upstream yml> <commit> <repo url> <out yml>
"""
import re, sys

upstream, commit, repo, out = sys.argv[1:5]
if not re.fullmatch(r"[0-9a-f]{40}", commit):
    raise SystemExit("commit must be a full 40-character hash")

gradle = open("zerion-android/build.gradle", encoding="utf-8").read()
name = re.search(r'^\s*versionName "([^"]+)"', gradle, flags=re.M).group(1)
code = re.search(r"^\s*versionCode (\d+)", gradle, flags=re.M).group(1)

y = open(upstream, encoding="utf-8").read()
end = y.index("\nAllowedAPKSigningKeys:")
starts = [m.start() for m in re.finditer(r"^  - versionName: ", y[:end],
                                          flags=re.M)]
if not starts:
    raise SystemExit("no build entry in %s" % upstream)
last = y[starts[-1]:end].rstrip("\n") + "\n"
if "verification-metadata" in last:
    raise SystemExit("the newest upstream recipe removes dependency "
                     "verification; refusing to build with it")

entry = re.sub(r"^  - versionName: .*$", "  - versionName: %s" % name, last,
               count=1, flags=re.M)
entry = re.sub(r"^    versionCode: .*$", "    versionCode: %s" % code, entry,
               count=1, flags=re.M)
entry = re.sub(r"^    commit: .*$", "    commit: %s" % commit, entry,
               count=1, flags=re.M)

same = re.search(r"^    versionCode: %s$" % code, last, flags=re.M)
head = y[:starts[-1]] if same else y[:end].rstrip("\n") + "\n\n"
y = head + entry + y[end:]
y = re.sub(r"^CurrentVersion: .*$", "CurrentVersion: %s" % name, y,
           flags=re.M)
y = re.sub(r"^CurrentVersionCode: .*$", "CurrentVersionCode: %s" % code, y,
           flags=re.M)
y = re.sub(r"^Binaries: .*\n", "", y, flags=re.M)
y = re.sub(r"^Repo: .*$", "Repo: %s" % repo, y, count=1, flags=re.M)
open(out, "w", encoding="utf-8", newline="\n").write(y)
print("metadata for %s (%s) at %s" % (name, code, commit))
