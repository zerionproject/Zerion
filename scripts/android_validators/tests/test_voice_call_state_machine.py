"""Pin the call state-machine invariants that fix the
'both sides stuck on Connecting' tester report.

The bug was a notification gap: VoiceCallService.updateCallActivity()
only forwarded CONNECTED / DISCONNECTED / FAILED to the bound Activity,
so CONNECTING and RINGING transitions never reached the UI. The 90 s /
120 s setup timeouts then made the perceived stall feel infinite.

These tests prevent the gap from sneaking back in via a future edit:

  - VoiceCallActivity declares public onCallStateChanged(CallState)
    that re-uses the existing per-state branches (CONNECTING / RINGING /
    CONNECTED / DISCONNECTED / FAILED).
  - VoiceCallService.updateCallActivity() snapshots callState and calls
    bound.onCallStateChanged(snapshot) - no per-state branch in the
    Service that could drop a transition.
  - Setup timeouts are bounded - outgoing <= 60 s, incoming <= 60 s.
  - Timeout routes through handleSetupFailure(reason) which closes the
    endpoint, disposes torConnection, and clears isRecording before
    notifying onCallFailed.
  - No new logging anywhere in the call path (privacy invariant).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SERVICE = ROOT / "zerion-android" / "src" / "main" / "java" / "com" / "professor" / "zerion" / "android" / "conversation" / "voice" / "VoiceCallService.java"
ACTIVITY = ROOT / "zerion-android" / "src" / "main" / "java" / "com" / "professor" / "zerion" / "android" / "conversation" / "voice" / "VoiceCallActivity.java"


def _read(path: Path) -> str:
    assert path.exists(), f"file missing: {path}"
    return path.read_text(encoding="utf-8")


def _balanced_body(text: str, after_match_end: int) -> str:
    """Given a position after a method's opening '{', return the body
    up to the matching '}'."""

    depth = 1
    i = after_match_end
    while i < len(text) and depth:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    return text[after_match_end : i - 1]


def _method_body(text: str, signature_pattern: str) -> str:
    m = re.search(signature_pattern, text)
    assert m, f"signature not found: {signature_pattern}"
    open_brace = text.find("{", m.end())
    assert open_brace >= 0, "opening brace not found"
    return _balanced_body(text, open_brace + 1)


def test_files_exist():
    for f in (SERVICE, ACTIVITY):
        assert f.exists(), f"expected {f}"


def test_activity_declares_on_call_state_changed():
    body = _method_body(
        _read(ACTIVITY),
        r"public\s+void\s+onCallStateChanged\s*\(\s*VoiceCallService\.CallState\s+\w+\s*\)",
    )
    for required in ["CONNECTING", "RINGING", "CONNECTED", "DISCONNECTED", "FAILED"]:
        assert f"case {required}:" in body, (
            f"onCallStateChanged switch is missing 'case {required}:'. "
            "Every CallState enum value MUST have an arm - a missing arm "
            "is exactly the bug that caused the stuck-on-Connecting report."
        )


def test_activity_callback_runs_on_ui_thread():
    body = _method_body(
        _read(ACTIVITY),
        r"public\s+void\s+onCallStateChanged\s*\(\s*VoiceCallService\.CallState\s+\w+\s*\)",
    )
    assert "runOnUiThread" in body, (
        "onCallStateChanged must hop to the UI thread - the Service posts "
        "from the main handler today, but a future refactor that drops the "
        "main-handler hop would crash because TextView.setText must run on "
        "the main thread."
    )


def test_service_update_call_activity_forwards_every_state():
    text = _read(SERVICE)
    body = _method_body(text, r"private\s+void\s+updateCallActivity\s*\(\s*\)")
    assert "onCallStateChanged" in body, (
        "VoiceCallService.updateCallActivity() must call "
        "callActivity.onCallStateChanged(state); a per-state switch here "
        "is exactly what caused the bug - any missed case silently drops "
        "the transition"
    )
    forbidden = ["onCallConnected()", "onCallDisconnected()", "onCallFailed("]
    for token in forbidden:
        assert token not in body, (
            f"updateCallActivity() must not call {token} directly. Funnel "
            "every transition through onCallStateChanged(state) so a future "
            "edit cannot reintroduce the missing-CONNECTING-arm bug."
        )


def test_service_update_call_activity_snapshots_state():
    """Race guard: if mainHandler.post(...) reads callState lazily inside
    the closure, the snapshot it observes is whatever value is current at
    post-execution time, NOT at post-schedule time. That would let a
    later transition silently overwrite an earlier one if they post in
    quick succession. Snapshotting into a final local before .post fixes
    this."""

    text = _read(SERVICE)
    body = _method_body(text, r"private\s+void\s+updateCallActivity\s*\(\s*\)")
    assert re.search(
        r"final\s+CallState\s+\w+\s*=\s*callState\s*;",
        body,
    ), (
        "updateCallActivity() must snapshot callState into a final local "
        "before mainHandler.post(...), so the Activity sees the exact "
        "state that triggered the post"
    )


def test_outgoing_call_timeout_within_bounds():
    text = _read(SERVICE)
    m = re.search(
        r"sendCallOffer\(\)\s*;[\s\S]+?scheduleCallSetupTimeout\(\s*(\d[\d_]*)\s*\)",
        text,
    )
    assert m, "outgoing call setup timeout not located after sendCallOffer()"
    timeout_ms = int(m.group(1).replace("_", ""))
    assert 5_000 <= timeout_ms <= 60_000, (
        f"outgoing setup timeout is {timeout_ms} ms; must be between 5 s and "
        "60 s. Anything longer makes Connecting look frozen; anything "
        "shorter risks failing a slow but otherwise-fine Tor circuit."
    )


def test_incoming_call_timeout_within_bounds():
    text = _read(SERVICE)
    m = re.search(
        r"sendCallAnswer\(\)\s*;[\s\S]+?scheduleCallSetupTimeout\(\s*(\d[\d_]*)\s*\)",
        text,
    )
    assert m, "incoming call setup timeout not located after sendCallAnswer()"
    timeout_ms = int(m.group(1).replace("_", ""))
    assert 5_000 <= timeout_ms <= 60_000, (
        f"incoming setup timeout is {timeout_ms} ms; must be between 5 s and 60 s"
    )


def test_handle_setup_failure_exists():
    text = _read(SERVICE)
    body = _method_body(
        text,
        r"private\s+void\s+handleSetupFailure\s*\(\s*String\s+\w+\s*\)",
    )
    assert "callState = CallState.FAILED" in body, (
        "handleSetupFailure must set callState = FAILED"
    )
    assert "closeEndpoint" in body, (
        "handleSetupFailure must close the rendezvous endpoint(s) so the Tor "
        "hidden service is taken down on setup failure"
    )
    assert ".dispose(" in body or "torConnection = null" in body, (
        "handleSetupFailure must dispose torConnection or null its reference "
        "so the next attempt does not reuse a stale half-open socket"
    )
    assert "onCallFailed" in body, (
        "handleSetupFailure must notify the Activity via onCallFailed(reason); "
        "otherwise the UI stays stuck even though the state machine has moved "
        "to FAILED"
    )


def test_setup_timeout_routes_through_handle_setup_failure():
    text = _read(SERVICE)
    body = _method_body(
        text,
        r"private\s+void\s+scheduleCallSetupTimeout\s*\(\s*long\s+\w+\s*\)",
    )
    assert "handleSetupFailure" in body, (
        "scheduleCallSetupTimeout's runnable must call handleSetupFailure "
        "rather than mutating callState inline - otherwise the endpoint, "
        "torConnection, and audio recorder leak past the timeout"
    )


def test_handle_setup_failure_guards_against_late_fire():
    text = _read(SERVICE)
    body = _method_body(
        text,
        r"private\s+void\s+handleSetupFailure\s*\(\s*String\s+\w+\s*\)",
    )
    assert "DISCONNECTED" in body and "CONNECTED" in body, (
        "handleSetupFailure must short-circuit if the call already reached "
        "CONNECTED or DISCONNECTED - the timer can fire after a successful "
        "connect because Handler.removeCallbacks races with the post"
    )


def test_no_logging_in_call_files():
    for path in (SERVICE, ACTIVITY):
        text = path.read_text(encoding="utf-8")
        forbidden = [
            (r"\bandroid\.util\.Log\b", "android.util.Log"),
            (r"\bLog\.(?:v|d|i|w|e|wtf)\s*\(", "Log.{v,d,i,w,e,wtf}(...)"),
            (r"\bjava\.util\.logging\.Logger\b", "JUL Logger"),
            (r"\bSystem\.err\s*\.\s*println\s*\(", "System.err.println"),
            (r"\bSystem\.out\s*\.\s*println\s*\(", "System.out.println"),
            (r"\bTimber\.[a-z]+\s*\(", "Timber call"),
            (r"\bprintStackTrace\s*\(\s*\)", "printStackTrace()"),
        ]
        for pattern, label in forbidden:
            for m in re.finditer(pattern, text):
                line_no = text.count("\n", 0, m.start()) + 1
                raise AssertionError(
                    f"{path.relative_to(ROOT)}:{line_no} contains {label} - "
                    "the call path MUST stay log-free (CLAUDE.md privacy rule); "
                    "a single Log.d() with a call-id or onion address is a "
                    "metadata leak"
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
