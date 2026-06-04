"""Sweep every Sprint-2 touched file for accidental logging.

CLAUDE.md project rule: NO logging anywhere - no Logger, no
android.util.Log, no LOG.warning/info/severe, no System.err, no Timber.
Even behind BuildConfig.DEBUG it is forbidden because Zerion's threat
model assumes a forensic-imaging adversary, and any string lookup that
ever held an onion address or a contactId is a privacy leak.

Static check on the four Sprint-2 touched files in the Android repo.
If a future edit re-introduces a Log call to any of them, this fires.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]

TOUCHED = [
    ROOT / "zerion-android" / "src" / "main" / "res" / "layout" / "dialog_disappearing_messages.xml",
    ROOT / "zerion-android" / "src" / "main" / "java" / "com" / "professor" / "zerion" / "android" / "view" / "TextSendController.java",
    ROOT / "zerion-android" / "src" / "main" / "java" / "com" / "professor" / "zerion" / "android" / "channel" / "ChannelInviteHandlerActivity.java",
    ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "plugin" / "tor" / "TorPlugin.java",
]

FORBIDDEN_PATTERNS = [
    (r"\bandroid\.util\.Log\b", "android.util.Log import or qualified call"),
    (r"\bLog\.(?:v|d|i|w|e|wtf)\s*\(", "android.util.Log.{v,d,i,w,e,wtf}(...)"),
    (r"\bjava\.util\.logging\.Logger\b", "JUL Logger import"),
    (r"\bLogger\.getLogger\s*\(", "Logger.getLogger(...) - JUL bootstrap"),
    (r"\bSystem\.err\s*\.\s*println\s*\(", "System.err.println(...)"),
    (r"\bSystem\.out\s*\.\s*println\s*\(", "System.out.println(...)"),
    (r"\bTimber\.[a-z]+\s*\(", "Timber.x(...) call"),
    (r"\bprintStackTrace\s*\(\s*\)", "Throwable.printStackTrace() - dumps to stderr"),
]


def test_touched_files_exist():
    for f in TOUCHED:
        assert f.exists(), f"Sprint-2 touched file missing: {f}"


def test_touched_files_contain_no_logging():
    for f in TOUCHED:
        text = f.read_text(encoding="utf-8", errors="replace")
        for pattern, label in FORBIDDEN_PATTERNS:
            for m in re.finditer(pattern, text):
                line_no = text.count("\n", 0, m.start()) + 1
                raise AssertionError(
                    f"{f.relative_to(ROOT)}:{line_no} contains forbidden "
                    f"logging construct ({label}): {m.group(0)!r}"
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
