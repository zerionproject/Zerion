"""Pin the two real wipe gaps closed in this sprint and document why
the third (MessagingManagerImpl) was a false positive.

KeyManagerImpl.stopService() must clear the managers map. The map
holds one TransportKeyManager per simplex / duplex plugin id and each
TransportKeyManager carries rotation-key material between rotations.
Leaving the map populated after stopServices pins every TKM for the
process lifetime - heap dump after Exit recovers them.

PcsStateManager must implement Service so its contactLocks map (which
grows with every contact touched this session) is cleared on Exit. The
class also needs to be REGISTERED with the LifecycleManager; the
sprint chose self-registration from the @Inject constructor so the
existing services iteration in stopServices reaches it. To make sure
the @Singleton provider is materialised at app start (and the
registerService call actually fires) PcsModule has an EagerSingletons
inner class wired into BrambleCoreEagerSingletons.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
KEY_MANAGER = ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "transport" / "KeyManagerImpl.java"
PCS_STATE = ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "crypto" / "pcs" / "PcsStateManager.java"
PCS_MODULE = ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "crypto" / "pcs" / "PcsModule.java"
EAGER = ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "BrambleCoreEagerSingletons.java"


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
    for f in (KEY_MANAGER, PCS_STATE, PCS_MODULE, EAGER):
        assert f.exists(), f"expected {f}"


def test_key_manager_stop_service_clears_managers():
    body = _method_body(
        _read(KEY_MANAGER),
        r"public\s+void\s+stopService\s*\(\s*\)",
    )
    assert "managers.clear()" in body, (
        "KeyManagerImpl.stopService() must clear the managers map; each "
        "TransportKeyManager held there carries rotation-key material "
        "between rotations and would otherwise pin for the process lifetime"
    )


def test_pcs_state_manager_implements_service():
    text = _read(PCS_STATE)
    assert re.search(
        r"class\s+PcsStateManager\s+implements\s+Service\b",
        text,
    ), (
        "PcsStateManager must declare 'implements Service' so it is "
        "eligible for LifecycleManager.registerService and reaches the "
        "services iteration in stopServices()"
    )


def test_pcs_state_manager_stop_service_clears_contact_locks():
    body = _method_body(
        _read(PCS_STATE),
        r"public\s+void\s+stopService\s*\(\s*\)\s*throws\s+ServiceException",
    )
    assert "contactLocks.clear()" in body, (
        "PcsStateManager.stopService() must clear contactLocks; the map "
        "grows monotonically per session and pins every ReentrantLock "
        "instance after Exit"
    )


def test_pcs_state_manager_self_registers_in_constructor():
    text = _read(PCS_STATE)
    ctor = _method_body(
        text,
        r"public\s+PcsStateManager\s*\(\s*DatabaseComponent\s+\w+\s*,\s*CryptoComponent\s+\w+\s*,\s*LifecycleManager\s+\w+\s*\)",
    )
    assert "registerService(this)" in ctor, (
        "PcsStateManager's @Inject constructor must call "
        "lifecycleManager.registerService(this) so it gets stopService() "
        "fired during LifecycleManager.stopServices()"
    )


def test_pcs_module_has_eager_singletons():
    text = _read(PCS_MODULE)
    assert re.search(
        r"public\s+static\s+class\s+EagerSingletons\s*\{[\s\S]*?@Inject[\s\S]*?PcsStateManager\s+\w+\s*;",
        text,
    ), (
        "PcsModule.EagerSingletons must hold an @Inject PcsStateManager so "
        "Dagger materialises the singleton at app start. Without this the "
        "registerService call in the constructor only fires on the first "
        "lazy lookup, which can land AFTER stopServices has run."
    )


def test_pcs_module_eager_singletons_wired_into_bramble_core():
    text = _read(EAGER)
    assert re.search(
        r"void\s+inject\s*\(\s*PcsModule\.EagerSingletons\s+\w+\s*\)\s*;",
        text,
    ), "inject(PcsModule.EagerSingletons) declaration missing in BrambleCoreEagerSingletons"
    assert re.search(
        r"c\.inject\s*\(\s*new\s+PcsModule\.EagerSingletons\s*\(\s*\)\s*\)",
        text,
    ), (
        "Helper.injectEagerSingletons must include "
        "c.inject(new PcsModule.EagerSingletons()) in the fan-out, "
        "otherwise the provider is only built on first use"
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
