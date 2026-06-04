"""Pin the user-visible 'Zerion is running' foreground notification
with Open + Exit actions (HIGH 5 deferred from Sprint 2).

Three load-bearing seams:

  1. ZerionService declares ACTION_EXIT and onStartCommand routes that
     action through shutdown(true). shutdown(true) calls
     lifecycleManager.stopServices() which cascades through every
     registered Service - including AccountManager.stopService that
     clears the in-memory databaseKey, KeyManagerImpl.stopService that
     clears the managers map, and PcsStateManager.stopService that
     clears contactLocks. So 'Exit' really is a clean shutdown, not a
     mere kill.

  2. AndroidNotificationManagerImpl.getForegroundNotification posts to
     a builder that has setOngoing(true), addAction with the Exit
     PendingIntent.getService routed at ZerionService.ACTION_EXIT, a
     content tap that opens SplashScreenActivity, and a visibility /
     priority pair that actually shows in the shade (PRIORITY_LOW,
     VISIBILITY_PRIVATE - not the legacy PRIORITY_MIN + VISIBILITY_SECRET
     which suppressed it).

  3. ZerionService creates the notification channel itself with
     IMPORTANCE_LOW + VISIBILITY_PRIVATE lock-screen visibility. If the
     channel-level visibility were still SECRET, builder settings would
     be clamped and the user would never see the notification regardless
     of the builder.

  4. strings.xml carries the three labels: title, body, exit action.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SERVICE = ROOT / "zerion-android" / "src" / "main" / "java" / "com" / "professor" / "zerion" / "android" / "ZerionService.java"
NOTIF_MGR = ROOT / "zerion-android" / "src" / "main" / "java" / "com" / "professor" / "zerion" / "android" / "AndroidNotificationManagerImpl.java"
STRINGS = ROOT / "zerion-android" / "src" / "main" / "res" / "values" / "strings.xml"


def _read(path: Path) -> str:
    assert path.exists(), f"file missing: {path}"
    return path.read_text(encoding="utf-8")


def _method_body(text: str, signature_pattern: str) -> str:
    m = re.search(signature_pattern, text)
    assert m, f"signature not found: {signature_pattern}"
    open_brace = text.find("{", m.end())
    assert open_brace >= 0, "opening brace not found"
    depth = 1
    i = open_brace + 1
    while i < len(text) and depth:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    return text[open_brace + 1 : i - 1]


def test_files_exist():
    for f in (SERVICE, NOTIF_MGR, STRINGS):
        assert f.exists(), f"expected {f}"


def test_zerion_service_declares_action_exit():
    text = _read(SERVICE)
    assert re.search(
        r'public\s+static\s+final\s+String\s+ACTION_EXIT\s*=\s*"com\.professor\.zerion\.android\.EXIT"\s*;',
        text,
    ), (
        "ZerionService must declare ACTION_EXIT as a public static String. "
        "AndroidNotificationManagerImpl builds the PendingIntent against "
        "this exact symbol; renaming or moving it breaks the Exit button."
    )


def test_on_start_command_routes_exit_to_shutdown():
    text = _read(SERVICE)
    body = _method_body(
        text,
        r"public\s+int\s+onStartCommand\s*\(\s*Intent\s+\w+\s*,\s*int\s+\w+\s*,\s*int\s+\w+\s*\)",
    )
    assert "ACTION_EXIT" in body, (
        "onStartCommand must reference ACTION_EXIT - otherwise the Exit "
        "PendingIntent fires but the service does nothing"
    )
    assert re.search(r"shutdown\s*\(\s*true\s*\)", body), (
        "onStartCommand must call shutdown(true) on ACTION_EXIT so the "
        "lifecycle stops AND the Android Service stops"
    )


def test_foreground_notification_is_visible_and_ongoing():
    text = _read(NOTIF_MGR)
    body = _method_body(
        text,
        r"private\s+Notification\s+getForegroundNotification\s*\(\s*boolean\s+\w+\s*\)",
    )
    assert "setOngoing(true)" in body, "foreground notification must be ongoing (un-swipable)"
    assert "VISIBILITY_PRIVATE" in body, (
        "foreground notification visibility must be VISIBILITY_PRIVATE - "
        "VISIBILITY_SECRET clamps the notification off the lock screen and "
        "out of the shade, defeating the whole point"
    )
    assert "PRIORITY_LOW" in body, (
        "foreground notification priority must be PRIORITY_LOW - PRIORITY_MIN "
        "suppresses display on most launchers"
    )


def test_foreground_notification_has_exit_action():
    text = _read(NOTIF_MGR)
    body = _method_body(
        text,
        r"private\s+Notification\s+getForegroundNotification\s*\(\s*boolean\s+\w+\s*\)",
    )
    assert re.search(
        r"ZerionService\.ACTION_EXIT",
        body,
    ), (
        "Exit action must wire to ZerionService.ACTION_EXIT - any other "
        "intent action will silently no-op since onStartCommand only "
        "handles ACTION_LOCK and ACTION_EXIT"
    )
    assert re.search(
        r"PendingIntent\.getService\s*\(",
        body,
    ), (
        "Exit PendingIntent must use getService (not getBroadcast / "
        "getActivity) because ZerionService is the handler"
    )
    assert "addAction" in body, (
        "builder must call addAction to surface the Exit button on the "
        "notification UI"
    )


def test_notification_channel_visibility_relaxed_to_private():
    text = _read(SERVICE)
    m = re.search(
        r"new\s+NotificationChannel\s*\(\s*ONGOING_CHANNEL_ID[\s\S]+?createNotificationChannel\s*\(\s*ongoingChannel\s*\)\s*;",
        text,
    )
    assert m, "ongoing notification channel creation block not located"
    block = m.group(0)
    assert re.search(
        r"setLockscreenVisibility\s*\(\s*(?:android\.app\.Notification\.)?VISIBILITY_PRIVATE\s*\)",
        block,
    ), (
        "ongoing NotificationChannel must call setLockscreenVisibility("
        "VISIBILITY_PRIVATE) - VISIBILITY_SECRET on the channel CLAMPS "
        "the builder's per-notification visibility, so leaving the channel "
        "at SECRET would override the builder fix"
    )


def test_required_strings_exist():
    text = _read(STRINGS)
    for name in [
        "ongoing_notification_title",
        "ongoing_notification_text",
        "ongoing_notification_exit_action",
    ]:
        assert re.search(rf'<string\s+name="{re.escape(name)}"', text), (
            f"strings.xml missing entry '{name}'"
        )
    assert ">Zerion is running<" in text or ">Zerion is running</string>" in text, (
        "ongoing_notification_title should read 'Zerion is running' per spec"
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
