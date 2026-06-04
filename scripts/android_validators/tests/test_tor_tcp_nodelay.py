"""Pin TCP_NODELAY on every Tor socket creation site.

Without TCP_NODELAY the Linux kernel coalesces sub-MSS writes via
Nagle's algorithm, holding small Mode 3-Full frames in the local send
buffer until either an ACK arrives or the 200 ms timer fires. Combined
with the chained per-message ML-KEM encap and the header/body two-pass
cipher init in the encrypter, that adds the perceived 2-3 s gap between
the clock icon and the sent (single-tick) icon that the tester saw
after Full Mode 3 rolled out.

These checks make sure every socket coming out of TorPlugin.java sets
TCP_NODELAY, defensively wrapped so a tunnelled SOCKS socket that does
not implement the option just keeps Nagle on rather than failing the
connection.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "plugin" / "tor" / "TorPlugin.java"

REQUIRED_CALL_SITES_BY_METHOD_OR_NEIGHBOUR = [
    "acceptB4NewOnionConnections",
    "acceptContactConnections",
    "dialOnion",
]


def _read() -> str:
    assert SRC.exists(), f"TorPlugin missing: {SRC}"
    return SRC.read_text(encoding="utf-8")


def _method_body(text: str, name: str) -> str:
    m = re.search(rf"\bvoid\s+{re.escape(name)}\s*\([^)]*\)\s*\{{|\b{re.escape(name)}\s*\(\s*@?Nullable[\s\S]*?\)\s*\{{", text)
    if not m:
        m2 = re.search(rf"private\s+(?:static\s+)?(?:[\w<>]+\s+)?{re.escape(name)}\s*\(", text)
        if m2:
            open_brace = text.find("{", m2.end())
            assert open_brace >= 0, f"could not find body start for {name}"
            start = open_brace + 1
        else:
            return ""
    else:
        start = m.end()
    depth = 1
    i = start
    while i < len(text) and depth:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
        i += 1
    return text[start : i - 1]


def test_source_file_exists():
    assert SRC.exists(), f"expected {SRC}"


def test_named_methods_set_tcp_nodelay():
    text = _read()
    for name in REQUIRED_CALL_SITES_BY_METHOD_OR_NEIGHBOUR:
        body = _method_body(text, name)
        assert body, f"could not locate method body for {name}()"
        assert re.search(r"setTcpNoDelay\s*\(\s*true\s*\)", body), (
            f"{name}() does not call s.setTcpNoDelay(true) - Nagle's "
            "algorithm will hold small Mode 3-Full frames up to 200 ms"
        )


def test_rendezvous_accept_loop_sets_tcp_nodelay():
    text = _read()
    block = re.search(
        r"ioExecutor\s*\.\s*execute\s*\(\s*\(\s*\)\s*->\s*\{[\s\S]*?ss\.accept\(\)[\s\S]*?\}\s*\)",
        text,
    )
    assert block, "rendezvous accept loop not located in TorPlugin"
    assert "setTcpNoDelay" in block.group(0), (
        "rendezvous endpoint accept loop must call setTcpNoDelay(true) on "
        "each accepted socket; otherwise inbound rendezvous traffic also "
        "pays the Nagle tax during contact-add"
    )


def test_tcp_nodelay_calls_are_defensively_wrapped():
    """A SOCKS-tunnelled socket may not implement setTcpNoDelay. The
    code MUST swallow SocketException at every call site so a non-
    supporting Tor build does not break the connection."""

    text = _read()
    for m in re.finditer(r"setTcpNoDelay\s*\(\s*true\s*\)\s*;", text):
        start = max(0, m.start() - 200)
        prefix = text[start : m.start()]
        assert "try" in prefix, (
            f"setTcpNoDelay at offset {m.start()} is not inside a try block - "
            "a SOCKS socket that does not implement the option would throw "
            "SocketException and silently break Tor connectivity"
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
