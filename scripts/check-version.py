#!/usr/bin/env python3
"""Version consistency check.

Fails when:
- versionName or versionCode in zerion-android/build.gradle is malformed, or
  versionCode does not follow the 3.0.x scheme (30000 + 100 * patch);
- the build version is older than the released version recorded in
  docs/release-manifest.json, or names the same version with another code;
- the released version has no CHANGELOG.md entry or fastlane changelog;
- a current document names a current release other than the manifest's;
- a store channel in the manifest is ahead of the released version, the
  GitHub channel is not the released version, or README.md names no or
  another Google Play or F-Droid version than the manifest;
- zerion-android/build.gradle sets or changes the version in any other way
  than one versionName and one versionCode line;
- on a tag push (GITHUB_REF_TYPE=tag) the tag is not v<build versionName>,
  does not equal the manifest tag, or the release has no changelogs.

Usage: check-version.py
"""
import json, os, re, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
os.chdir(ROOT)

errors = []

OTHER_VERSION_FORMS = re.compile(
    r"\bversion(Code|Name)\s*[=(]|\bversion(Code|Name)(Suffix|Override)\b"
    r"|\bsetVersion(Code|Name)\b")


def read(path):
    return open(path, encoding="utf-8").read()


def parse(name):
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", name)
    if not m:
        errors.append("version %r is not major.minor.patch" % name)
        return None
    return tuple(int(x) for x in m.groups())


def expected_code(version):
    major, minor, patch = version
    if (major, minor) != (3, 0) or patch > 99:
        errors.append("no versionCode scheme known for %d.%d.%d" % version)
        return None
    return 30000 + 100 * patch


def check_changelogs(name, code):
    if not re.search(r"^## %s \(" % re.escape(name), read("CHANGELOG.md"),
                     flags=re.M):
        errors.append("CHANGELOG.md has no entry for %s" % name)
    path = "fastlane/metadata/android/en-US/changelogs/%d.txt" % code
    if not os.path.isfile(path):
        errors.append("%s is missing" % path)


def check_channels(a, released_name, released):
    channels = a.get("channels", {})
    for name in ("github", "play", "fdroid"):
        if name not in channels:
            errors.append("manifest has no %s channel" % name)
            continue
        v = channels[name].get("version", "")
        parsed = parse(v)
        if parsed and released and parsed > released:
            errors.append("manifest %s channel %s is ahead of the released %s"
                          % (name, v, released_name))
    github = channels.get("github", {}).get("version")
    if github is not None and github != released_name:
        errors.append("manifest github channel %s is not the released %s"
                      % (github, released_name))
    readme = read("README.md")
    for name, pattern in (
            ("play", r"\[Google Play\]\([^)]*\) carries ([0-9.]+)"),
            ("fdroid", r"\[F-Droid\]\([^)]*\) offers ([0-9.]+)")):
        want = channels.get(name, {}).get("version")
        found = re.findall(pattern, readme)
        if not found:
            errors.append("README.md: no %s version reference matching %r"
                          % (name, pattern))
        for v in found:
            if v != want:
                errors.append("README.md names %s for %s, the manifest says %s"
                              % (v, name, want))


def main():
    gradle = read("zerion-android/build.gradle")
    names = re.findall(r'^\s*versionName "([^"]+)"', gradle, flags=re.M)
    codes = re.findall(r"^\s*versionCode (\d+)", gradle, flags=re.M)
    if len(names) != 1 or len(codes) != 1:
        errors.append("zerion-android/build.gradle must set versionName and "
                      "versionCode exactly once")
        return
    if OTHER_VERSION_FORMS.search(gradle):
        errors.append("zerion-android/build.gradle sets the version in "
                      "another form than versionName \"x\" and versionCode n")
    build_name, build_code = names[0], int(codes[0])
    build = parse(build_name)
    if build and expected_code(build) not in (None, build_code):
        errors.append("versionCode %d does not match versionName %s (want %d)"
                      % (build_code, build_name, expected_code(build)))

    a = json.loads(read("docs/release-manifest.json"))["android"]
    released_name, released_code = a["version"], a["versionCode"]
    released = parse(released_name)
    if released and expected_code(released) not in (None, released_code):
        errors.append("manifest versionCode %d does not match version %s"
                      % (released_code, released_name))
    if a["tag"] != "v" + released_name:
        errors.append("manifest tag %s is not v%s" % (a["tag"], released_name))
    if build and released:
        if build < released or build_code < released_code:
            errors.append("build %s (%d) is older than the released %s (%d)"
                          % (build_name, build_code, released_name,
                             released_code))
        if build == released and build_code != released_code:
            errors.append("build and manifest name %s with different codes"
                          % build_name)
    check_changelogs(released_name, released_code)
    check_channels(a, released_name, released)

    current = [
        ("README.md", r"describes the current release, \*\*([0-9.]+)\*\*"),
        ("README.md", r"\| AVAILABLE \| ([0-9.]+) on \[GitHub\]"),
        ("docs/ZERION_TECHNICAL_WHITEPAPER.md",
         r"describes the protocol as implemented in ([0-9.]+)"),
    ]
    for path, pattern in current:
        found = re.findall(pattern, read(path))
        if not found:
            errors.append("%s: no current release reference matching %r"
                          % (path, pattern))
        for v in found:
            if v != released_name:
                errors.append("%s names %s as the current release, the "
                              "manifest says %s" % (path, v, released_name))

    if os.environ.get("GITHUB_REF_TYPE") == "tag":
        tag = os.environ.get("GITHUB_REF_NAME", "")
        if tag != "v" + build_name:
            errors.append("tag %s does not match versionName %s"
                          % (tag, build_name))
        if tag != a["tag"]:
            errors.append("tag %s is not the manifest tag %s" % (tag, a["tag"]))
        check_changelogs(build_name, build_code)

    if not errors:
        print("version consistency: build %s (%d), released %s (%d)"
              % (build_name, build_code, released_name, released_code))


if __name__ == "__main__":
    main()
    if errors:
        for e in errors:
            print("FAIL " + e)
        sys.exit(1)
