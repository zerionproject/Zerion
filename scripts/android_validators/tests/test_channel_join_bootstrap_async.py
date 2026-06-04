"""Verify ChannelInviteHandlerActivity does NOT await bootstrapChannel()
synchronously inside the 'Connecting to the channel...' progress dialog.

The original tester report showed the spinner stuck for what felt like
minutes. Root cause was bootstrapChannel() being called inline on the
IO executor inside a try/catch that ran to completion before the
progress dialog dismissed. bootstrapChannel makes a Tor request with
60 s connect + 120 s read timeouts so the user could legitimately wait
up to 3 minutes.

The fix moves bootstrapChannel into an inner ioExecutor.execute() block
so it runs in the background while the channel feed opens immediately.
This test pins that structure.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "zerion-android" / "src" / "main" / "java" / "com" / "professor" / "zerion" / "android" / "channel" / "ChannelInviteHandlerActivity.java"


def _read() -> str:
    assert SRC.exists(), f"source missing: {SRC}"
    return SRC.read_text(encoding="utf-8")


def _method_body(text: str, name: str) -> str:
    m = re.search(rf"private\s+void\s+{re.escape(name)}\s*\(", text)
    assert m, f"method {name}() not found"
    paren_depth = 1
    i = m.end()
    while i < len(text) and paren_depth:
        if text[i] == "(":
            paren_depth += 1
        elif text[i] == ")":
            paren_depth -= 1
        i += 1
    while i < len(text) and text[i] != "{":
        i += 1
    assert i < len(text), f"opening brace for {name}() not found"
    start = i + 1
    depth = 1
    j = start
    while j < len(text) and depth:
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
        j += 1
    return text[start : j - 1]


def _count_io_executor_blocks(body: str) -> int:
    return len(re.findall(r"ioExecutor\s*\.\s*execute\s*\(", body))


def _bootstrap_inside_nested(body: str) -> bool:
    outer = re.search(r"ioExecutor\s*\.\s*execute\s*\(\s*\(\s*\)\s*->\s*\{", body)
    if not outer:
        return False
    i = outer.end()
    depth = 1
    while i < len(body) and depth:
        if body[i] == "{":
            depth += 1
        elif body[i] == "}":
            depth -= 1
        i += 1
    outer_body = body[outer.end() : i - 1]
    inner = re.search(r"ioExecutor\s*\.\s*execute\s*\(\s*\(\s*\)\s*->\s*\{", outer_body)
    if not inner:
        return False
    k = inner.end()
    depth = 1
    while k < len(outer_body) and depth:
        if outer_body[k] == "{":
            depth += 1
        elif outer_body[k] == "}":
            depth -= 1
        k += 1
    inner_body = outer_body[inner.end() : k - 1]
    return "bootstrapChannel" in inner_body


def test_source_file_exists():
    assert SRC.exists(), f"expected source at {SRC}"


def test_handle_join_has_two_io_executor_blocks():
    body = _method_body(_read(), "handleJoin")
    count = _count_io_executor_blocks(body)
    assert count >= 2, (
        f"handleJoin uses {count} ioExecutor.execute() block(s); needs two "
        "(outer for joinChannel, nested for bootstrapChannel) - otherwise "
        "the 'Connecting to the channel...' spinner waits on the Tor pull"
    )


def test_handle_apply_has_two_io_executor_blocks():
    body = _method_body(_read(), "handleApply")
    count = _count_io_executor_blocks(body)
    assert count >= 2, (
        f"handleApply uses {count} ioExecutor.execute() block(s); needs two "
        "for the same reason as handleJoin"
    )


def test_handle_join_bootstrap_is_in_nested_executor():
    body = _method_body(_read(), "handleJoin")
    assert _bootstrap_inside_nested(body), (
        "bootstrapChannel() must be inside the NESTED ioExecutor.execute() "
        "block in handleJoin, not the outer one, so the progress dialog "
        "dismisses immediately after joinChannel returns"
    )


def test_handle_apply_bootstrap_is_in_nested_executor():
    body = _method_body(_read(), "handleApply")
    assert _bootstrap_inside_nested(body), (
        "bootstrapChannel() must be inside the NESTED ioExecutor.execute() "
        "block in handleApply"
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
