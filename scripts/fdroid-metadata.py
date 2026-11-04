#!/usr/bin/env python3
"""Writes F-Droid metadata that builds the checked-out commit.

Takes the build recipe of the newest entry in fdroiddata's
metadata/com.professor.zerion.yml, so the reference build uses exactly the
recipe F-Droid builds with, and points it at the current commit and version.
The entry must be, field for field, the recipe this repository pins in
scripts/fdroid-recipe.yml (versionName, versionCode and commit excepted),
compared after YAML decoding, so no line folding, quoting or escape can hide a
difference and no step that is not already reviewed here can run. A recipe
change in fdroiddata therefore needs the matching change to the pinned file in
the same review. As a second layer, both are checked against the recipe fields
this build uses and must not mention dependency verification or touch the
Gradle files, so the pinned file cannot quietly grow such a step either.

The written file carries that one entry and nothing else under Builds, so no
other entry, whatever version it names, can be the one fdroid builds; the
fields above and below Builds are kept from the upstream text but may only be
the descriptive ones listed here, with RepoType git, Repo pointing at the
checked-out clone and no Binaries line.

Usage: fdroid-metadata.py <upstream yml> <commit> <repo url> <out yml>
"""
import os, re, sys

try:
    import yaml
except ImportError:
    raise SystemExit("PyYAML is needed to read the fdroiddata recipe")

upstream, commit, repo, out = sys.argv[1:5]
if not re.fullmatch(r"[0-9a-f]{40}", commit):
    raise SystemExit("commit must be a full 40-character hash")

gradle = open("zerion-android/build.gradle", encoding="utf-8").read()
if re.search(r"\bversion(Code|Name)\s*[=(]|\bversion(Code|Name)"
             r"(Suffix|Override)\b|\bsetVersion(Code|Name)\b", gradle):
    raise SystemExit("zerion-android/build.gradle sets the version in another "
                     "form than versionName \"x\" and versionCode n")
name = re.search(r'^\s*versionName "([^"]+)"', gradle, flags=re.M).group(1)
code = re.search(r"^\s*versionCode (\d+)", gradle, flags=re.M).group(1)

VERSION_FIELDS = {"versionName", "versionCode", "commit"}
SCALAR = {"versionName": str, "versionCode": int, "commit": str,
          "subdir": str, "ndk": str, "timeout": int}
LISTS = {"sudo", "init", "prebuild", "build", "postbuild", "gradle",
         "gradleprops", "srclibs", "rm", "scandelete", "scanignore"}
SCRIPTS = {"sudo", "init", "prebuild", "build", "postbuild"}
FORBIDDEN = re.compile(r"verification|org\.gradle\.dependency|gradle\.properties"
                       r"|build\.gradle|settings\.gradle|gradlew|\.lockfile"
                       r"|/gradle/|gradle/", re.I)
TOP_LEVEL = {"Categories", "License", "AuthorName", "AuthorEmail", "WebSite",
             "SourceCode", "IssueTracker", "Changelog", "Donate", "Bitcoin",
             "Litecoin", "Name", "AutoName", "Summary", "Description",
             "RepoType", "Repo", "Binaries", "Builds", "AllowedAPKSigningKeys",
             "AutoUpdateMode", "UpdateCheckMode", "UpdateCheckData",
             "CurrentVersion", "CurrentVersionCode", "Translation",
             "AntiFeatures", "RequiresRoot", "ArchivePolicy", "NoSourceSince"}


class StrictLoader(yaml.SafeLoader):
    """SafeLoader that refuses a mapping with a repeated key, as fdroidserver
    does, so this check and the builder read the same recipe."""

    def construct_mapping(self, node, deep=False):
        seen = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=deep)
            if key in seen:
                raise yaml.YAMLError("repeated key %r" % key)
            seen.add(key)
        return super().construct_mapping(node, deep)


def load(text, what):
    try:
        return yaml.load(text, Loader=StrictLoader)
    except yaml.YAMLError as e:
        raise SystemExit("%s is not valid YAML (%s)" % (what, e))


def refuse(why):
    raise SystemExit("the newest upstream recipe %s; refusing to build with it"
                     % why)


def check_entry(entry, what):
    if not isinstance(entry, dict):
        refuse("%s is not a mapping" % what)
    for key, value in entry.items():
        if key in SCALAR:
            if type(value) is not SCALAR[key]:
                refuse("%s gives %s a value of an unexpected kind" % (what, key))
            if isinstance(value, str) and FORBIDDEN.search(value):
                refuse("%s names a Gradle or verification file in %s"
                       % (what, key))
        elif key in LISTS:
            if not isinstance(value, list):
                refuse("%s gives %s something other than a list" % (what, key))
            for item in value:
                if type(item) is not str:
                    refuse("%s has a %s item that is not a plain string"
                           % (what, key))
                if "\n" in item or "\r" in item or "\0" in item:
                    refuse("%s has a %s item with a line break" % (what, key))
                if key in SCRIPTS or key == "rm":
                    if FORBIDDEN.search(item):
                        refuse("%s touches the Gradle files or dependency "
                               "verification in %s" % (what, key))
                elif re.search(r"verification|org\.gradle\.dependency", item,
                               flags=re.I):
                    refuse("%s mentions dependency verification in %s"
                           % (what, key))
        else:
            refuse("%s uses the field %s, which this build does not expect"
                   % (what, key))


here = os.path.dirname(os.path.abspath(__file__))
pinned = load(open(os.path.join(here, "fdroid-recipe.yml"),
                   encoding="utf-8").read(), "scripts/fdroid-recipe.yml")
check_entry(pinned, "the pinned recipe")
if VERSION_FIELDS & set(pinned):
    refuse("pin in scripts/fdroid-recipe.yml names a version or commit")

y = open(upstream, encoding="utf-8").read()
if not y.isascii():
    refuse("is not plain ASCII")
doc = load(y, "the upstream recipe")
if not isinstance(doc, dict) or not isinstance(doc.get("Builds"), list) \
        or not doc["Builds"]:
    refuse("has no build entry")
unknown = sorted(set(doc) - TOP_LEVEL)
if unknown:
    refuse("has the top-level field(s) %s, which this build does not expect"
           % ", ".join(unknown))
if doc.get("RepoType") != "git":
    refuse("does not name a git repository")
newest = doc["Builds"][-1]
check_entry(newest, "entry")
if not VERSION_FIELDS <= set(newest):
    refuse("lacks versionName, versionCode or commit")
recipe = {k: v for k, v in newest.items() if k not in VERSION_FIELDS}
if recipe != pinned:
    changed = sorted(k for k in set(recipe) | set(pinned)
                     if recipe.get(k) != pinned.get(k))
    refuse("differs from scripts/fdroid-recipe.yml in %s" % ", ".join(changed))

builds = y.index("\nBuilds:\n") + 1
end = y.index("\nAllowedAPKSigningKeys:")
if end < builds:
    refuse("does not list its builds before AllowedAPKSigningKeys")
starts = [m.start() for m in re.finditer(r"^  - versionName: ", y[:end],
                                          flags=re.M)]
if not starts:
    refuse("has no build entry")
last = y[starts[-1]:end].rstrip("\n") + "\n"
if load(last, "the newest entry") != [newest]:
    refuse("has a newest entry that reads differently as text and as YAML")

entry = re.sub(r"^  - versionName: .*$", "  - versionName: %s" % name, last,
               count=1, flags=re.M)
entry = re.sub(r"^    versionCode: .*$", "    versionCode: %s" % code, entry,
               count=1, flags=re.M)
entry = re.sub(r"^    commit: .*$", "    commit: %s" % commit, entry,
               count=1, flags=re.M)
expected = dict(pinned, versionName=name, versionCode=int(code), commit=commit)
if load(entry, "the rewritten entry") != [expected]:
    refuse("could not be pointed at this commit without changing it")

y = y[:builds] + "Builds:\n" + entry + y[end:]
y = re.sub(r"^CurrentVersion: .*$", "CurrentVersion: %s" % name, y,
           flags=re.M)
y = re.sub(r"^CurrentVersionCode: .*$", "CurrentVersionCode: %s" % code, y,
           flags=re.M)
y = re.sub(r"^Binaries: .*\n", "", y, flags=re.M)
y = re.sub(r"^Repo: .*$", "Repo: %s" % repo, y, count=1, flags=re.M)

written = load(y, "the written metadata")
if written.get("Builds") != [expected] or written.get("Repo") != repo \
        or written.get("RepoType") != "git" or "Binaries" in written \
        or written.get("CurrentVersion") != name \
        or written.get("CurrentVersionCode") != int(code) \
        or set(written) - TOP_LEVEL:
    refuse("could not be written as the one reviewed entry")
open(out, "w", encoding="utf-8", newline="\n").write(y)
print("metadata for %s (%s) at %s" % (name, code, commit))
