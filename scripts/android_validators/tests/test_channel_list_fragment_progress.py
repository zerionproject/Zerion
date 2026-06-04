"""Pin the ChannelListFragment progress dialog + background bootstrap.

The Sprint 2 fix only patched ChannelInviteHandlerActivity (the
zerion://channel/... intent-arrived path). The parallel
Channels-tab > Subscribe-from-link path inside ChannelListFragment was
missed. Tester walking through the in-app menu got NO feedback and the
inline bootstrapChannel() Tor pull could block up to 3 minutes.

Same fix pattern as ChannelInviteHandlerActivity (covered by
test_channel_join_bootstrap_async.py for that activity): handleJoin and
handleApply each use TWO ioExecutor.execute blocks - outer for the
DB-only joinChannel / applyToJoin call, inner for bootstrapChannel -
plus a ProgressDialog that dismisses in every terminal branch.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "zerion-android" / "src" / "main" / "java" / "com" / "professor" / "zerion" / "android" / "channel" / "ChannelListFragment.java"


def _read() -> str:
    assert SRC.exists(), f"file missing: {SRC}"
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


def _count_ioexecutor_blocks(body: str) -> int:
    return len(re.findall(r"ioExecutor\s*\.\s*execute\s*\(", body))


def _bootstrap_in_nested_executor(body: str) -> bool:
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


def test_file_exists():
    assert SRC.exists(), f"expected {SRC}"


def test_show_join_progress_helper_exists():
    text = _read()
    assert re.search(
        r"private\s+android\.app\.ProgressDialog\s+showJoinProgress\s*\(\s*\)",
        text,
    ), (
        "ChannelListFragment needs a showJoinProgress() helper that returns "
        "a started, non-cancellable ProgressDialog - both handleJoin and "
        "handleApply consume it"
    )


def test_handle_join_uses_two_io_executor_blocks():
    body = _method_body(_read(), "handleJoin")
    count = _count_ioexecutor_blocks(body)
    assert count >= 2, (
        f"handleJoin uses {count} ioExecutor.execute() block(s); needs at "
        "least 2 - outer for joinChannel (DB-only), inner for "
        "bootstrapChannel (Tor pull)"
    )


def test_handle_join_bootstrap_runs_in_nested_executor():
    body = _method_body(_read(), "handleJoin")
    assert _bootstrap_in_nested_executor(body), (
        "bootstrapChannel must be inside the NESTED ioExecutor.execute in "
        "handleJoin so the foreground ProgressDialog dismisses after the "
        "DB-only step, not after the Tor pull"
    )


def test_handle_join_dismisses_progress_in_every_terminal_branch():
    body = _method_body(_read(), "handleJoin")
    dismiss_calls = len(re.findall(r"progress\.dismiss\s*\(\s*\)", body))
    assert dismiss_calls >= 3, (
        f"handleJoin calls progress.dismiss() {dismiss_calls} time(s); needs "
        "at least 3 - one for the already-subscribed early return, one for "
        "the success path, one for the DbException catch. A missing dismiss "
        "leaves the dialog stuck."
    )


def test_handle_apply_uses_two_io_executor_blocks():
    body = _method_body(_read(), "handleApply")
    count = _count_ioexecutor_blocks(body)
    assert count >= 2, (
        f"handleApply uses {count} ioExecutor.execute() block(s); needs at "
        "least 2 for the same reason as handleJoin"
    )


def test_handle_apply_bootstrap_runs_in_nested_executor():
    body = _method_body(_read(), "handleApply")
    assert _bootstrap_in_nested_executor(body), (
        "handleApply must also kick bootstrapChannel into a nested "
        "ioExecutor.execute. Before Sprint 3 it did NOT call "
        "bootstrapChannel at all, so the channel feed opened empty - this "
        "test catches that regression too."
    )


def test_handle_apply_dismisses_progress_in_every_terminal_branch():
    body = _method_body(_read(), "handleApply")
    dismiss_calls = len(re.findall(r"progress\.dismiss\s*\(\s*\)", body))
    assert dismiss_calls >= 2, (
        f"handleApply calls progress.dismiss() {dismiss_calls} time(s); "
        "needs at least 2 (success + DbException)"
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
