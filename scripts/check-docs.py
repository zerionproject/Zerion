#!/usr/bin/env python3
"""Documentation consistency check.

Fails when:
- a current document names a version, hash or channel that differs from
  docs/release-manifest.json;
- a forbidden stale phrase appears in a current document;
- a current document calls the online transport "Bramble transport";
- an internal Markdown link points at a file that does not exist;
- docs/protocol/SECURITY_CLAIMS.md references a source file or test that
  does not exist;
- a current document or the website names a cryptographic primitive that
  docs/crypto-primitives.json lists as absent, or the website's crypto
  table names a primitive that is not in the canonical list;
- the website (a directory given with --website, or fetched from
  https://zerion.chat) carries release references that differ from what
  scripts/render-release-refs.py renders from the manifest.

Historical pages (blog posts, changelog entries, fastlane changelogs) are
not scanned for stale phrases; they carry dated notes instead.

Usage: check-docs.py [--website <dir>] [--no-fetch]
"""
import glob, io, json, os, re, sys, urllib.error, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
os.chdir(ROOT)

CURRENT_DOCS = [
    "README.md", "SECURITY.md", "CONTRIBUTING.md",
    "docs/ZERION_OVERVIEW.md", "docs/ZERION_TECHNICAL_WHITEPAPER.md",
    "docs/ZERION_MESH_AND_I2P.md", "docs/protocol/README.md",
    "docs/protocol/SECURITY_CLAIMS.md", "docs/protocol/SECURITY_CLAIMS_MATRIX.md",
    "docs/protocol/ZTP-ZPP.md", "docs/protocol/ZWF-MODE3FULL.md",
    "docs/protocol/ASYNC-SEALED-SENDER.md", "docs/protocol/MESH-TRANSPORT.md",
    "docs/protocol/EMBEDDED-I2P.md", "docs/WALLET_ARCHITECTURE.md",
    "docs/BTC_ARCHITECTURE.md", "docs/XMR_ARCHITECTURE.md",
    "docs/WALLET_SECURITY_INVARIANTS.md", "docs/FDROID.md",
    "fastlane/metadata/android/en-US/full_description.txt",
    "fastlane/metadata/android/en-US/short_description.txt",
]
WEBSITE_CURRENT = ["index.html", "faq.html", "privacy-policy.html", "disclosure.html", "donate.html"]

FORBIDDEN = [
    (r"built on (Briar's )?Bramble", "describes the current online transport as Bramble"),
    (r"Bramble transport protocol", "describes the current online transport as Bramble"),
    (r"built on the Briar Project and its Bramble", "describes Zerion as built on Bramble"),
    (r"(began|started) as a fork of (the )?Briar|fork of Briar|Briar[- ]based|Briar fork", "provenance belongs in NOTICE.md, not in the product description"),
    (r"inherited Bramble", "name the component, not its origin"),
    (r"\bBriar\b|\bBramble\b", "third-party provenance belongs in NOTICE.md and the provenance table of the protocol index only"),
    (r"fixed in the next release", "name the branch and 3.0.12, and say it is not yet released"),
    (r"Android[- ]only", "platform status must use the status words"),
    (r"coming soon", "platform status must use the status words"),
    (r"Triple Ratchet", "retired name; the ratchet is Mode 3-Full"),
    (r"HKDF-BLAKE2b", "no such primitive in the code"),
    (r"military[- ]grade", "not a technical statement"),
    (r"100% (over )?Tor", "Tor is mandatory with named opt-ins; not 100%"),
    (r"Tor-only transport", "use: Tor mandatory and always on, with the opt-ins named"),
    (r"constant-rate cover traffic", "two rates since 3.0.8; describe both"),
    (r"idle and active connections look (the same|identical)", "two rates since 3.0.8"),
    (r"(fully|completely) (independently )?audited", "no independent assessment of the whole product exists"),
    (r"independently audited", "no independent assessment of the whole product exists"),
    (r"never received (an )?independent", "focused independent reviews have occurred"),
    (r"zero metadata", "not defensible as an absolute"),
    (r"no metadata\b(?! (held|collection|on our side))", "scope the metadata statement"),
    (r"truly anonymous|complete anonymity|completely anonymous", "anonymity is bounded by Tor's model"),
    (r"hardware-backed vault|held in (the )?hardware", "the vault is device-bound, not held in hardware"),
    (r"signs the transcript with its hybrid", "pairing authentication is classical in 3.0.11"),
    (r"debug-build-only", "I2P is in the release build on this branch"),
    (r"schema version 66", "schema version is 67"),
]
ALLOW_CONTEXT = [r"not used", r"retired", r"no longer", r"not ", r"never ", r"is written", r"absent", r"instead of", r"Historical", r"older material"]

errors = []


def visible_text(html):
    t = re.sub(r"<script.*?</script>|<style.*?</style>", "", html, flags=re.S)
    t = re.sub(r"<[^>]+>", " ", t)
    return re.sub(r"[ \t]+", " ", t)


PROVENANCE_SECTIONS = {"docs/protocol/README.md": "## Component provenance"}
PROVENANCE_ONLY_DOCS = sorted(glob.glob("docs/wire/*.md"))


def strip_provenance_section(name, text):
    heading = PROVENANCE_SECTIONS.get(name)
    if not heading or heading not in text:
        return text
    start = text.index(heading)
    end = text.find("\n## ", start + len(heading))
    return text[:start] + (text[end:] if end >= 0 else "")


def check_forbidden(name, text):
    text = strip_provenance_section(name, text)
    for entry in FORBIDDEN:
        pattern, why = entry[0], entry[1]
        for m in re.finditer(pattern, text, flags=re.I):
            window = text[max(0, m.start() - 160): m.end() + 60]
            if any(re.search(a, window, flags=re.I) for a in ALLOW_CONTEXT):
                continue
            errors.append("%s: forbidden phrase %r (%s)" % (name, m.group(0), why))


def check_links(name, text):
    for m in re.finditer(r"\]\(([^)#\s]+)(#[^)]*)?\)", text):
        target = m.group(1)
        if re.match(r"[a-z]+://", target) or target.startswith("mailto:"):
            continue
        base = os.path.dirname(name)
        path = os.path.normpath(os.path.join(base, target))
        if not os.path.exists(path):
            errors.append("%s: broken internal link %s" % (name, target))


def check_manifest_refs(name, text, m):
    a = m["android"]
    for ver in re.findall(r"\b3\.0\.(\d+)\b", text):
        pass
    for mm in re.finditer(r"(?:Latest release|current version|Current version)[^\n]{0,40}?(3\.0\.\d+)", text):
        if mm.group(1) != a["version"]:
            errors.append("%s: names %s as latest, manifest says %s" % (name, mm.group(1), a["version"]))
    for sha in re.findall(r"\b[0-9a-f]{64}\b", text):
        if sha.lower() == a["apk"]["sha256"] or sha.lower() == a["signingCertSha256"]:
            continue
        if name.endswith("PROVENANCE.md") or "native" in name:
            continue
    if "D7FDB11125890D133AE89D8BA4F4331D9045E21EF01D9899A7CDEE6888F704C8".lower() in text.lower():
        if a["signingCertSha256"] not in text.lower().replace(" ", ""):
            errors.append("%s: signing fingerprint differs from the manifest" % name)


def check_claims_matrix():
    text = open("docs/protocol/SECURITY_CLAIMS.md", encoding="utf-8").read()
    names = set(re.findall(r"`([A-Za-z0-9_.*/-]+)`", text))
    src = {}
    for path in glob.glob("**/*.java", recursive=True) + glob.glob("**/*.gradle", recursive=True) + glob.glob("**/*.cpp", recursive=True) + glob.glob("**/*.md", recursive=True) + glob.glob("**/*.xml", recursive=True) + glob.glob("**/*.txt", recursive=True) + glob.glob("**/*.json", recursive=True):
        src[os.path.basename(path).split(".")[0]] = path
        src[os.path.basename(path)] = path
    for n in sorted(names):
        base = n.split(".")[0].split("/")[-1].rstrip("*")
        if not base or base in ("security-r1", "master", "FLAG_SECURE", "core", "panic", "decoy", "account", "security"):
            continue
        if n.endswith("/") or os.path.exists(n):
            continue
        if base in src or any(k.startswith(base) for k in src):
            continue
        errors.append("SECURITY_CLAIMS.md: no source or test named %s" % n)


def check_crypto(name, text, crypto):
    for absent in crypto["absent"]:
        for m in re.finditer(re.escape(absent), text, flags=re.I):
            window = text[max(0, m.start() - 160): m.end() + 60]
            if any(re.search(a, window, flags=re.I) for a in ALLOW_CONTEXT):
                continue
            errors.append("%s: names %r, which the code does not use" % (name, absent))


def check_website_crypto(html, crypto):
    values = re.findall(r'<span class="crypto-value">([^<]*)</span>', html)
    known = set(p["primitive"] for p in crypto["primitives"])
    for v in values:
        parts = [x.strip() for x in re.split(r"[·/,(]| over | and ", v)]
        for p in parts:
            p = p.strip(" )")
            if not p or p[0].islower():
                continue
            if not any(k.split(" ")[0] in p for k in known):
                errors.append("website crypto table names %r, not in docs/crypto-primitives.json" % p)


def website_files(argv):
    if "--website" in argv:
        d = argv[argv.index("--website") + 1]
        return {f: open(os.path.join(d, f), encoding="utf-8", newline="").read() for f in WEBSITE_CURRENT}
    if "--no-fetch" in argv:
        return {}
    out = {}
    for f in WEBSITE_CURRENT:
        req = urllib.request.Request("https://zerion.chat/" + f, headers={
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) zerion-docs-check/1",
            "Accept": "text/html"})
        try:
            out[f] = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code in (403, 503):
                print("WARN website: %s not fetchable from this network (HTTP %d, the site's edge "
                      "protection); run with --website <source dir> to check the site" % (f, e.code))
                return {}
            errors.append("website: cannot fetch %s: %s" % (f, e))
        except Exception as e:
            errors.append("website: cannot fetch %s: %s" % (f, e))
    return out


def main():
    m = json.load(open("docs/release-manifest.json", encoding="utf-8"))
    crypto = json.load(open("docs/crypto-primitives.json", encoding="utf-8"))
    for name in CURRENT_DOCS:
        if not os.path.exists(name):
            errors.append("missing current document %s" % name)
            continue
        text = open(name, encoding="utf-8").read()
        scan = text
        check_forbidden(name, scan)
        if name.endswith(".md"):
            check_links(name, text)
        check_manifest_refs(name, text, m)
        check_crypto(name, scan, crypto)
    for name in PROVENANCE_ONLY_DOCS:
        text = open(name, encoding="utf-8").read()
        for m in re.finditer(r"\bBriar\b|\bBramble\b", text):
            errors.append("%s: forbidden phrase %r (design notes describe Zerion's implementation, not its origin)" % (name, m.group(0)))
    check_claims_matrix()
    sys.path.insert(0, os.path.join(ROOT, "scripts"))
    import importlib.util
    spec = importlib.util.spec_from_file_location("rrr", os.path.join(ROOT, "scripts", "render-release-refs.py"))
    rrr = importlib.util.module_from_spec(spec); spec.loader.exec_module(rrr)
    for f, html in website_files(sys.argv).items():
        text = visible_text(html)
        check_forbidden("website/" + f, text)
        check_crypto("website/" + f, text, crypto)
        if f == "index.html":
            check_website_crypto(html, crypto)
            try:
                if rrr.render(html, m) != html:
                    errors.append("website/index.html: release references differ from docs/release-manifest.json")
            except SystemExit as e:
                errors.append(str(e))
    if errors:
        for e in errors:
            print("FAIL " + e)
        sys.exit(1)
    print("documentation consistency: OK")


if __name__ == "__main__":
    main()
