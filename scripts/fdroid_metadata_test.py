#!/usr/bin/env python3
"""Self-test of scripts/fdroid-metadata.py against hostile recipe files.

The script that writes the F-Droid metadata must accept the pinned recipe
only, read as YAML and not as text, and refuse every attempt to smuggle a
different step past the check: an escaped keyword, a repeated key, an alias,
an entry that reads differently as text and as YAML, a field this build does
not expect, or a byte outside ASCII. Runs the script as F-Droid's build server
would, from the repository root, with a generated upstream file.

Usage: python3 scripts/fdroid_metadata_test.py
"""
import os
import subprocess
import sys
import tempfile
import unittest

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SCRIPT = os.path.join(HERE, "fdroid-metadata.py")
COMMIT = "0123456789abcdef0123456789abcdef01234567"
REPO = "/in/zerion.git"

HEAD = """Categories:
  - Internet
License: GPL-3.0-only
SourceCode: https://github.com/zerionproject/Zerion
IssueTracker: https://github.com/zerionproject/Zerion/issues

AutoName: Zerion
RepoType: git
Repo: https://github.com/zerionproject/Zerion.git
Binaries: https://github.com/zerionproject/Zerion/releases/download/v%v/zerion-%v.apk

Builds:
"""

TAIL = """
AllowedAPKSigningKeys: d7fdb11125890d133ae89d8ba4f4331d9045e21ef01d9899a7cdee6888f704c8

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
CurrentVersion: 3.0.18
CurrentVersionCode: 31800
"""


def scalar(value):
    text = yaml.safe_dump([value], width=float("inf"),
                          default_flow_style=False).strip()
    assert text.startswith("- "), text
    return text[2:]


def entry_text(version, code, commit, recipe):
    lines = ["  - versionName: %s" % version, "    versionCode: %d" % code,
             "    commit: %s" % commit]
    for key, value in recipe.items():
        if isinstance(value, list):
            lines.append("    %s:" % key)
            for item in value:
                lines.append("      - %s" % scalar(item))
        else:
            lines.append("    %s: %s" % (key, scalar(value)))
    return "\n".join(lines) + "\n"


def pinned():
    with open(os.path.join(HERE, "fdroid-recipe.yml"), encoding="utf-8") as f:
        return yaml.safe_load(f.read())


def run(upstream_text):
    with tempfile.TemporaryDirectory() as d:
        upstream = os.path.join(d, "upstream.yml")
        out = os.path.join(d, "metadata.yml")
        with open(upstream, "w", encoding="utf-8", newline="\n") as f:
            f.write(upstream_text)
        p = subprocess.run([sys.executable, SCRIPT, upstream, COMMIT, REPO,
                            out], cwd=ROOT, capture_output=True, text=True)
        written = None
        if os.path.exists(out):
            with open(out, encoding="utf-8") as f:
                written = f.read()
        return p.returncode, p.stdout + p.stderr, written


class FdroidMetadataTest(unittest.TestCase):

    def setUp(self):
        self.recipe = pinned()
        self.good = HEAD + entry_text("3.0.18", 31800, "v3.0.18",
                                      self.recipe) + TAIL

    def refused(self, text, why):
        code, output, written = run(text)
        self.assertNotEqual(0, code, output)
        self.assertIn("refusing", output)
        self.assertIn(why, output)
        self.assertIsNone(written)

    def test_the_pinned_recipe_is_accepted_and_pointed_at_the_commit(self):
        code, output, written = run(self.good)
        self.assertEqual(0, code, output)
        doc = yaml.safe_load(written)
        self.assertEqual(REPO, doc["Repo"])
        self.assertNotIn("Binaries", doc)
        self.assertEqual(1, len(doc["Builds"]))
        build = doc["Builds"][0]
        self.assertEqual(COMMIT, build["commit"])
        self.assertEqual(doc["CurrentVersion"], build["versionName"])
        self.assertEqual(doc["CurrentVersionCode"], build["versionCode"])
        for key in ("versionName", "versionCode", "commit"):
            del build[key]
        self.assertEqual(self.recipe, build)

    def test_only_the_newest_entry_is_kept(self):
        older = entry_text("3.0.17", 31700, "v3.0.17", self.recipe)
        text = HEAD + older + entry_text("3.0.18", 31800, "v3.0.18",
                                         self.recipe) + TAIL
        code, output, written = run(text)
        self.assertEqual(0, code, output)
        self.assertEqual(1, len(yaml.safe_load(written)["Builds"]))

    def test_an_escaped_keyword_is_read_as_the_word_it_spells(self):
        recipe = dict(self.recipe)
        recipe["build"] = list(recipe["build"]) + ["ESCAPED_PLACEHOLDER"]
        text = HEAD + entry_text("3.0.18", 31800, "v3.0.18", recipe) + TAIL
        text = text.replace("- ESCAPED_PLACEHOLDER",
                            '- "echo \\x76erification"')
        self.assertIn('"echo \\x76erification"', text)
        self.assertEqual("echo verification",
                         yaml.safe_load(text)["Builds"][0]["build"][-1])
        self.refused(text, "touches the Gradle files or dependency "
                           "verification in build")

    def test_a_repeated_key_is_not_valid_yaml(self):
        text = self.good.replace("    ndk: ", "    ndk: r26d\n    ndk: ", 1)
        code, output, written = run(text)
        self.assertNotEqual(0, code, output)
        self.assertIn("not valid YAML", output)
        self.assertIsNone(written)

    def test_an_alias_cannot_add_a_step(self):
        text = self.good.replace("    build:\n", "    build: &steps\n", 1)
        text = text.replace("    postbuild:\n", "    prebuild: *steps\n"
                            "    postbuild:\n", 1)
        self.refused(text, "differs from scripts/fdroid-recipe.yml")

    def test_a_changed_step_is_refused(self):
        recipe = dict(self.recipe)
        recipe["build"] = list(recipe["build"]) + ["echo extra"]
        text = HEAD + entry_text("3.0.18", 31800, "v3.0.18", recipe) + TAIL
        self.refused(text, "differs from scripts/fdroid-recipe.yml in build")

    def test_a_field_this_build_does_not_expect_is_refused(self):
        text = self.good.replace("    ndk: ", "    nativecode: true\n    ndk: ",
                                 1)
        self.refused(text, "uses the field nativecode")

    def test_an_entry_in_flow_style_is_refused(self):
        entry = dict(self.recipe, versionName="3.0.18", versionCode=31800,
                     commit="v3.0.18")
        flow = "  - " + yaml.safe_dump(entry, default_flow_style=True,
                                       width=float("inf")).strip() + "\n"
        text = HEAD + flow + TAIL
        self.assertEqual([entry], yaml.safe_load(text)["Builds"])
        self.refused(text, "has no build entry")

    def test_a_byte_outside_ascii_is_refused(self):
        text = self.good.replace("AutoName: Zerion", "AutoName: Zeri\u00f6n")
        self.refused(text, "is not plain ASCII")

    def test_a_top_level_field_this_build_does_not_expect_is_refused(self):
        text = self.good.replace("AutoName: Zerion",
                                 "AutoName: Zerion\nMaintainerNotes: x")
        self.refused(text, "top-level field")


if __name__ == "__main__":
    unittest.main(verbosity=2)
