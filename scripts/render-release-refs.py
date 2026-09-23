#!/usr/bin/env python3
"""Render the website's release references from docs/release-manifest.json.

Usage: render-release-refs.py <path to website index.html> [--check]

Rewrites the hero version tag, the JSON-LD softwareVersion, the direct
download link and label, the SHA-256 block and the F-Droid note so that
they carry exactly the values recorded in the manifest. With --check the
file is not written; the script exits 1 if the file differs from what it
would render, which is what the documentation CI runs.
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
MANIFEST = os.path.join(HERE, "..", "docs", "release-manifest.json")


def render(html, m):
    a = m["android"]
    apk = a["apk"]
    fd = a["channels"]["fdroid"]
    subs = [
        (r"Post-Quantum Encrypted · v[0-9.]+", "Post-Quantum Encrypted · v%s" % a["version"]),
        (r'"softwareVersion": "[0-9.]+"', '"softwareVersion": "%s"' % a["version"]),
        (r'href="https://github\.com/zerionproject/Zerion/releases/download/v[0-9.]+/zerion-[0-9.]+\.apk"',
         'href="%s"' % apk["url"]),
        (r"<span>v[0-9.]+ · Direct from GitHub</span>", "<span>v%s · Direct from GitHub</span>" % a["version"]),
        (r"<span>v[0-9.]+ SHA-256:</span>", "<span>v%s SHA-256:</span>" % a["version"]),
        (r'<code id="hashValue">[0-9a-f]{64}</code>', '<code id="hashValue">%s</code>' % apk["sha256"]),
        (r'<span data-release="fdroid-note">[^<]*</span>',
         '<span data-release="fdroid-note">F-Droid offers %s; %s pending</span>' % (fd["version"], a["version"])
         if fd["version"] != a["version"] else
         '<span data-release="fdroid-note">Auto-updates via F-Droid</span>'),
    ]
    out = html
    for pattern, repl in subs:
        out, n = re.subn(pattern, lambda _m, r=repl: r, out)
        if n == 0:
            raise SystemExit("render-release-refs: anchor not found: %s" % pattern)
    return out


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    path = sys.argv[1]
    check = "--check" in sys.argv
    m = json.load(open(MANIFEST, encoding="utf-8"))
    html = open(path, encoding="utf-8", newline="").read()
    out = render(html, m)
    if check:
        if out != html:
            raise SystemExit("render-release-refs: %s differs from the manifest" % path)
        print("release references match the manifest")
        return
    if out != html:
        open(path, "w", encoding="utf-8", newline="").write(out)
        print("rendered release references into %s" % path)
    else:
        print("no change")


if __name__ == "__main__":
    main()
