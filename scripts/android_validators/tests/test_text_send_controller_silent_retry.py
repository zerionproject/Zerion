"""Pin the silent-auto-accept timer-change behaviour in TextSendController.

Sprint 2 changed the response to UNEXPECTED_TIMER from 'show a modal
Disappearing-messages-changed dialog' to 'silently update expectedTimer
and resend once, only fall back to the dialog if the second attempt
ALSO trips'. This test prevents a future refactor from quietly
re-introducing the dialog on the first race.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "zerion-android" / "src" / "main" / "java" / "com" / "professor" / "zerion" / "android" / "view" / "TextSendController.java"


def _read() -> str:
    assert SRC.exists(), f"TextSendController missing: {SRC}"
    return SRC.read_text(encoding="utf-8")


def test_source_file_exists():
    assert SRC.exists(), f"expected source at {SRC}"


def test_retry_guard_flag_declared():
    text = _read()
    assert re.search(r"private\s+boolean\s+timerChangeAutoAccepted\s*=\s*false\s*;", text), (
        "timerChangeAutoAccepted guard flag missing - without it the first "
        "UNEXPECTED_TIMER would loop straight back to the modal dialog"
    )


def test_unexpected_timer_first_attempt_resends_silently():
    text = _read()
    assert re.search(
        r"if\s*\(\s*!\s*timerChangeAutoAccepted\s*\)\s*\{[^}]*timerChangeAutoAccepted\s*=\s*true\s*;[^}]*expectedTimer\s*=\s*currentTimer\s*;[^}]*onSendEvent\s*\(\s*\)\s*;",
        text,
        re.S,
    ), (
        "first-attempt branch must set the guard true, sync expectedTimer to "
        "currentTimer, and call onSendEvent() to silently resend"
    )


def test_dialog_is_fallback_not_default():
    text = _read()
    pattern = re.search(
        r"else\s*if\s*\(\s*sendState\s*==\s*UNEXPECTED_TIMER\s*\)\s*\{(.+?)\}\s*else\s*if",
        text,
        re.S,
    )
    assert pattern, "UNEXPECTED_TIMER branch not found in onSendStateChanged"
    branch_body = pattern.group(1)
    dialog_call = re.search(r"showTimerChangedDialog\s*\(", branch_body)
    assert dialog_call, "showTimerChangedDialog must still be reachable as the fallback"
    pre_dialog = branch_body[: dialog_call.start()]
    assert "!timerChangeAutoAccepted" in pre_dialog.replace(" ", "") or "! timerChangeAutoAccepted" in pre_dialog, (
        "showTimerChangedDialog must be inside the `else` branch of the "
        "!timerChangeAutoAccepted guard - it should only fire on the SECOND "
        "consecutive UNEXPECTED_TIMER, not the first"
    )


def test_flag_resets_on_sent():
    text = _read()
    sent = re.search(r"if\s*\(\s*sendState\s*==\s*SENT\s*\)\s*\{(.+?)\}", text, re.S)
    assert sent, "SENT branch not found"
    assert "timerChangeAutoAccepted = false" in sent.group(1), (
        "flag must reset after a successful send so the next compose cycle "
        "starts clean - otherwise a stale true could mute a real warning"
    )


def test_flag_resets_on_starting_message():
    text = _read()
    starting = re.search(r"void\s+onStartingMessage\s*\(\s*\)\s*\{(.+?)\}", text, re.S)
    assert starting, "onStartingMessage method not found"
    assert "timerChangeAutoAccepted = false" in starting.group(1), (
        "flag must reset when the user begins composing a fresh message"
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
