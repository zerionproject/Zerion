"""Pin the dialog_disappearing_messages.xml structure so the 'Off'
RadioButton can never get clipped on small screens again.

Tester report: on some devices the picker showed 9 options instead of 10
- 'Off' was missing. Root cause was the Material AlertDialog's built-in
~80% screen-height cap clipping the last RadioButton when there was no
scrollable container. Fix wrapped the RadioGroup in a ScrollView.

These checks make sure the layout still:
- has every one of the 10 timer RadioButton ids the picker code looks
  up by findViewById,
- still inflates inside a ScrollView,
- still uses the canonical strings table id (so a string-rename break is
  caught immediately).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
XML = ROOT / "zerion-android" / "src" / "main" / "res" / "layout" / "dialog_disappearing_messages.xml"
STRINGS = ROOT / "zerion-android" / "src" / "main" / "res" / "values" / "strings.xml"

# Every option that must appear in the picker. These are the @+id values
# the activity code passes to findViewById.
REQUIRED_RADIO_IDS = [
    "timer_30_seconds",
    "timer_5_minutes",
    "timer_30_minutes",
    "timer_1_hour",
    "timer_8_hours",
    "timer_12_hours",
    "timer_24_hours",
    "timer_1_week",
    "timer_4_weeks",
    "timer_off",
]

# Strings that must exist in strings.xml backing each option.
REQUIRED_STRINGS = [
    "dialog_disappearing_30_seconds",
    "dialog_disappearing_5_minutes",
    "dialog_disappearing_30_minutes",
    "dialog_disappearing_1_hour",
    "dialog_disappearing_8_hours",
    "dialog_disappearing_12_hours",
    "dialog_disappearing_24_hours",
    "dialog_disappearing_1_week",
    "dialog_disappearing_4_weeks",
    "dialog_disappearing_off",
]


def _tree() -> ET.ElementTree:
    assert XML.exists(), f"layout missing: {XML}"
    return ET.parse(str(XML))


def _ids(tree: ET.ElementTree) -> set[str]:
    ns = "{http://schemas.android.com/apk/res/android}"
    return {
        elem.get(f"{ns}id", "").removeprefix("@+id/").removeprefix("@id/")
        for elem in tree.iter()
    }


def test_layout_file_exists():
    assert XML.exists(), f"expected layout at {XML}"


def test_every_required_radio_id_is_present():
    ids = _ids(_tree())
    missing = [r for r in REQUIRED_RADIO_IDS if r not in ids]
    assert not missing, (
        f"layout dropped {len(missing)} radio ids: {missing}. "
        "If you renamed them, update the activity code AND this test."
    )


def test_off_option_is_included():
    """Specific regression: the original bug was 'Off' being clipped. If
    a future edit removes the RadioButton entirely instead of clipping it,
    this fails. Keep both this and the generic id check to make the intent
    obvious in the failure message."""

    ids = _ids(_tree())
    assert "timer_off" in ids, (
        "timer_off RadioButton missing from dialog layout. "
        "Without it, users CANNOT disable disappearing messages from "
        "the picker - the exact tester report."
    )


def test_radio_group_is_inside_a_scroll_container():
    tree = _tree()
    found = False
    for parent in tree.iter():
        for child in list(parent):
            tag = child.tag.rsplit("}", 1)[-1]
            if tag in ("ScrollView", "NestedScrollView"):
                for sub in child.iter():
                    sub_tag = sub.tag.rsplit("}", 1)[-1]
                    if sub_tag == "RadioGroup":
                        found = True
                        break
            if found:
                break
        if found:
            break
    assert found, (
        "RadioGroup is no longer wrapped in a ScrollView / NestedScrollView. "
        "Without scrolling, the Material AlertDialog clips the bottom rows on "
        "small / portrait screens and 'Off' goes invisible again."
    )


def test_required_strings_exist():
    assert STRINGS.exists(), f"strings.xml missing: {STRINGS}"
    text = STRINGS.read_text(encoding="utf-8")
    missing = []
    for name in REQUIRED_STRINGS:
        if not re.search(rf'<string\s+name="{re.escape(name)}"', text):
            missing.append(name)
    assert not missing, (
        f"strings.xml missing {len(missing)} required entries: {missing}. "
        "Layout references them via @string/<name>."
    )


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"ok    {t.__name__}")
        except AssertionError as e:
            print(f"FAIL  {t.__name__}: {e}")
            failed += 1
    sys.exit(failed)
