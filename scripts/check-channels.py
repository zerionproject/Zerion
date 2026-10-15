#!/usr/bin/env python3
"""Release channel drift check.

Compares docs/release-manifest.json with what the channels publish:
- the latest GitHub release must be the manifest's tag;
- the newest version F-Droid publishes must be the manifest's F-Droid
  version, so the manifest never claims more or less than F-Droid offers;
- the fdroiddata recipe's CurrentVersion must be the released version once
  a release is out, so a release that never reached the recipe is noticed.
Google Play has no public version API; its manifest entry is checked
against README.md by check-version.py and is confirmed by hand.

Needs network access. Usage: check-channels.py
"""
import json, os, re, sys, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
REPO = "zerionproject/Zerion"
PACKAGE = "com.professor.zerion"

errors = []


def fetch(url, accept=None):
    headers = {"User-Agent": "zerion-channel-check"}
    if accept:
        headers["Accept"] = accept
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")


def version_key(name):
    return tuple(int(x) for x in name.split("."))


def main():
    with open(os.path.join(ROOT, "docs/release-manifest.json"),
              encoding="utf-8") as f:
        a = json.load(f)["android"]
    channels = a["channels"]

    latest = json.loads(fetch(
        "https://api.github.com/repos/%s/releases/latest" % REPO,
        "application/vnd.github+json"))["tag_name"]
    if latest != a["tag"]:
        errors.append("GitHub's latest release is %s, the manifest says %s"
                      % (latest, a["tag"]))

    packages = json.loads(fetch(
        "https://f-droid.org/api/v1/packages/%s" % PACKAGE))["packages"]
    published = max((p["versionName"] for p in packages), key=version_key)
    if published != channels["fdroid"]["version"]:
        errors.append("F-Droid publishes %s, the manifest says %s"
                      % (published, channels["fdroid"]["version"]))

    recipe = fetch("https://gitlab.com/fdroid/fdroiddata/-/raw/master/"
                   "metadata/%s.yml" % PACKAGE)
    m = re.search(r"^CurrentVersion: (\S+)$", recipe, flags=re.M)
    if not m:
        errors.append("the fdroiddata recipe has no CurrentVersion")
    elif m.group(1) != a["version"]:
        errors.append("the fdroiddata recipe is at %s, the released version "
                      "is %s" % (m.group(1), a["version"]))

    if not errors:
        print("release channels: GitHub %s, F-Droid %s, fdroiddata %s; "
              "Google Play %s is confirmed by hand"
              % (latest, published, m.group(1), channels["play"]["version"]))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError) as e:
        errors.append("cannot read a channel: %s" % e)
    if errors:
        for e in errors:
            print("FAIL " + e)
        sys.exit(1)
