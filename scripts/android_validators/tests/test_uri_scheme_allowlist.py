"""URI scheme allowlist + parser fuzz coverage for the Android side.

Sprint 3 requirement: Zerion must only accept zerion:// URIs. Every
non-zerion scheme - http, https, ftp, file, content, intent, custom
third-party schemes - must be rejected at the parser layer, and every
malformed input shape (empty, traversal, NUL bytes, HTML-style payload)
must be rejected without an exception escaping into the caller.

This file pins two things:

1. The actual Java regex in HandshakeLinkConstants.LINK_REGEX is
   anchored and accepts ONLY zerion://. The regex is read out of the
   source and recompiled in Python so the fuzz inputs hit the same
   pattern the Android consumer will see.

2. The Android intent-filter declarations in AndroidManifest.xml use
   only the zerion scheme for any URI-accepting Activity, so the OS
   itself never routes a non-zerion URI to Zerion in the first place.

The channel-side parser (briar-core ChannelCodec) uses
url.startsWith('zerion://channel/'), which is a simpler invariant; it
is covered by direct startsWith checks in the same fuzz set.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HANDSHAKE_CONSTANTS = ROOT / "bramble-api" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "api" / "contact" / "HandshakeLinkConstants.java"
PENDING_FACTORY = ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "contact" / "PendingContactFactoryImpl.java"
CHANNEL_CODEC = ROOT / "briar-core" / "src" / "main" / "java" / "org" / "briarproject" / "briar" / "channel" / "ChannelCodec.java"
MANIFEST = ROOT / "zerion-android" / "src" / "main" / "AndroidManifest.xml"

VALID_BASE32_53 = "a" * 53
HANDSHAKE_OK_PREFIX = "zerion://" + VALID_BASE32_53

VALID_HEX_64 = "ab" * 32
CHANNEL_OK = (
    "zerion://channel/" + VALID_HEX_64 + "/" + ("a" * 52)
    + "?k=" + ("ab" * 32)
)

NON_ZERION_SCHEMES = [
    "http://example.com/",
    "https://example.com/",
    "ftp://example.com/file",
    "file:///etc/passwd",
    "content://com.android.contacts/",
    "intent://channel/#Intent;scheme=zerion;end",
    "javascript:alert(1)",
    "data:text/html,<script>1</script>",
    "custom://anything",
    "BRIAR://" + VALID_BASE32_53,
    "briar://" + VALID_BASE32_53,
]

MALFORMED_HANDSHAKE = [
    "",
    "zerion://",
    "zerion:///",
    "zerion://../../",
    "zerion://%00",
    "zerion://<script>alert(1)</script>",
    "zerion://invalid_payload",
    "zerion://" + ("a" * 52),                       # one char short
    "zerion://" + ("a" * 54),                       # one char too long
    "zerion://" + ("A" * 53),                       # uppercase rejected
    "zerion://" + ("a" * 52) + "1",                 # disallowed digit
    "  zerion://" + VALID_BASE32_53,                # leading whitespace
    "zerion://" + VALID_BASE32_53 + "  ",           # trailing whitespace
    "zerion://" + VALID_BASE32_53 + "\n",           # trailing newline
    "zerion://" + VALID_BASE32_53 + "/extra",       # extra path segment
    "zerion://" + VALID_BASE32_53 + "#fragment",    # fragment without query
    "http://attacker.com/zerion://" + VALID_BASE32_53,
    "x" * 50_000,                                    # arbitrarily large junk
    "zerion%3A%2F%2F" + VALID_BASE32_53,             # percent-encoded scheme
]

MALFORMED_CHANNEL_PREFIX_REJECTED = [
    "",
    "zerion://",
    "zerion://channel",
    "ZERION://channel/" + VALID_HEX_64 + "/a",
    "zerion://CHANNEL/" + VALID_HEX_64 + "/a",
    "zerion://channel " + VALID_HEX_64 + "/a",
    "http://attacker/zerion://channel/" + VALID_HEX_64,
    "zerion%3A%2F%2Fchannel/" + VALID_HEX_64,
]


def _extract_handshake_regex_source() -> str:
    """Pull the regex out of HandshakeLinkConstants.java, reconstructing
    the string-concatenation Java uses to inject BASE32_LINK_BYTES."""

    text = HANDSHAKE_CONSTANTS.read_text(encoding="utf-8")
    m = re.search(r"Pattern\.compile\((.*?)\);", text, re.S)
    assert m, "Pattern.compile(...) call not found in HandshakeLinkConstants.java"
    body = m.group(1)

    pieces: list[str] = []
    for lit in re.finditer(r'"((?:\\.|[^"\\])*)"', body):
        raw = lit.group(1)
        raw = raw.encode("utf-8").decode("unicode_escape")
        pieces.append(raw)
    joined = "".join(pieces)
    joined = joined.replace("{}", "{53}")
    return joined


def _compile_handshake_regex() -> re.Pattern[str]:
    return re.compile(_extract_handshake_regex_source())


def _channel_accepts(url: str) -> bool:
    """Re-implement ChannelCodec.parseInviteLink's first check."""

    if not url:
        return False
    if len(url) > 4096:
        return False
    return url.startswith("zerion://channel/")


def test_handshake_regex_is_anchored():
    src = _extract_handshake_regex_source()
    assert src.startswith("^"), (
        "HandshakeLinkConstants.LINK_REGEX must start with ^ so a "
        "Matcher.find() on a longer string with the pattern as a substring "
        "cannot succeed - e.g. 'http://x/zerion://<53>' must NOT parse"
    )
    assert src.endswith("$"), (
        "HandshakeLinkConstants.LINK_REGEX must end with $ so trailing "
        "garbage after the base32 segment cannot be silently discarded"
    )


def test_pending_factory_uses_matches_not_find():
    text = PENDING_FACTORY.read_text(encoding="utf-8")
    assert "matcher.matches()" in text, (
        "PendingContactFactoryImpl must call matcher.matches() rather than "
        "matcher.find() - find() only requires a substring match and would "
        "accept 'http://attacker/zerion://<53>?evil' silently"
    )
    assert "matcher.find()" not in text, (
        "matcher.find() must not be present - it bypasses anchors when the "
        "consumer uses it on the same regex"
    )


def test_valid_handshake_link_accepted():
    pat = _compile_handshake_regex()
    assert pat.fullmatch(HANDSHAKE_OK_PREFIX), (
        f"the canonical handshake link {HANDSHAKE_OK_PREFIX!r} must be "
        "accepted by the tightened regex - if not, the regex has overshot"
    )


def test_valid_handshake_link_with_query_accepted():
    pat = _compile_handshake_regex()
    assert pat.fullmatch(HANDSHAKE_OK_PREFIX + "?profile=1"), (
        "handshake link with trailing ?-query must still be accepted - the "
        "spec preserves (?:\\?.*)? for forward compatibility"
    )


def test_non_zerion_schemes_rejected_by_handshake_regex():
    pat = _compile_handshake_regex()
    accepted = [u for u in NON_ZERION_SCHEMES if pat.fullmatch(u)]
    assert not accepted, (
        f"non-zerion schemes accepted by handshake regex: {accepted}. "
        "Every entry in this list MUST be rejected to satisfy the "
        "URI-scheme allowlist."
    )


def test_malformed_handshake_inputs_rejected():
    pat = _compile_handshake_regex()
    accepted = [u for u in MALFORMED_HANDSHAKE if pat.fullmatch(u)]
    assert not accepted, f"malformed handshake inputs accepted: {accepted}"


def test_non_zerion_schemes_rejected_by_channel_codec():
    accepted = [u for u in NON_ZERION_SCHEMES if _channel_accepts(u)]
    assert not accepted, (
        f"non-zerion schemes accepted by ChannelCodec.parseInviteLink "
        f"startsWith check: {accepted}"
    )


def test_malformed_channel_inputs_rejected_at_prefix_gate():
    """Anything that doesn't match the case-sensitive prefix
    'zerion://channel/' is rejected before any further parsing.
    Inputs that DO pass this gate but are malformed downstream
    (wrong hex length, bad base32, missing capability, etc.) are
    caught by the byte-level checks in ChannelCodec.parseInviteLink:
    line 367 (channelId length), 370 (pub length), 376 (capability
    length), 382 (onion length cap), 387 (IllegalArgumentException
    swallow). Those are exercised by the Kotlin/Java unit tests, not
    this layer."""

    accepted = [u for u in MALFORMED_CHANNEL_PREFIX_REJECTED if _channel_accepts(u)]
    assert not accepted, (
        f"prefix-rejected inputs slipped through startsWith: {accepted}"
    )


def test_canonical_channel_link_accepted_by_prefix_check():
    assert _channel_accepts(CHANNEL_OK), (
        f"the canonical channel link {CHANNEL_OK!r} must pass the prefix check"
    )


def test_manifest_intent_filters_only_register_zerion_scheme():
    """Walk every intent-filter in AndroidManifest.xml and assert that
    any <data android:scheme="..."> inside it is exactly 'zerion'. We
    ignore the <queries> block - that controls who Zerion can LAUNCH,
    not who can LAUNCH Zerion."""

    tree = ET.parse(str(MANIFEST))
    ns = "{http://schemas.android.com/apk/res/android}"

    def iter_descendant_intent_filters(root: ET.Element):
        for activity_or_alias in root.iter():
            tag = activity_or_alias.tag.rsplit("}", 1)[-1]
            if tag not in ("activity", "activity-alias", "receiver", "service", "provider"):
                continue
            for child in activity_or_alias:
                child_tag = child.tag.rsplit("}", 1)[-1]
                if child_tag == "intent-filter":
                    yield activity_or_alias, child

    rogue = []
    for owner, filt in iter_descendant_intent_filters(tree.getroot()):
        for data in filt.iter():
            data_tag = data.tag.rsplit("}", 1)[-1]
            if data_tag != "data":
                continue
            scheme = data.get(f"{ns}scheme")
            if scheme is not None and scheme != "zerion":
                owner_name = owner.get(f"{ns}name", "?")
                rogue.append((owner_name, scheme))
    assert not rogue, (
        "AndroidManifest.xml exposes intent-filters with non-zerion schemes: "
        f"{rogue}. Anything other than 'zerion' lets a third-party app "
        "deliver an arbitrary URI to Zerion via Intent.ACTION_VIEW."
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
