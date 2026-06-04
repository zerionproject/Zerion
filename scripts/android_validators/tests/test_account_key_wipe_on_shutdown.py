"""Pin the contract that AccountManagerImpl wipes its in-memory
databaseKey when LifecycleManager.stopServices() runs.

Before Sprint 2: SqlCipherDatabase.close() cleared its own copy of the
key but AccountManagerImpl.databaseKey was NEVER cleared. A heap dump
after Exit could still recover the DB encryption key from the second
copy and unlock the on-disk DB.

After Sprint 2: AccountManagerImpl implements Service. stopService()
clears databaseKey under stateChangeLock. The class is registered with
LifecycleManager from AccountModule, and EagerSingletons is wired into
BrambleCoreEagerSingletons so Dagger materialises the provider (and
therefore fires registerService) at app start rather than lazily.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
IMPL = ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "account" / "AccountManagerImpl.java"
MODULE = ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "account" / "AccountModule.java"
EAGER = ROOT / "bramble-core" / "src" / "main" / "java" / "org" / "briarproject" / "bramble" / "BrambleCoreEagerSingletons.java"


def _read(path: Path) -> str:
    assert path.exists(), f"file missing: {path}"
    return path.read_text(encoding="utf-8")


def test_files_exist():
    for path in (IMPL, MODULE, EAGER):
        assert path.exists(), f"expected file at {path}"


def test_account_manager_impl_declares_service():
    text = _read(IMPL)
    assert re.search(
        r"class\s+AccountManagerImpl\s+implements\s+AccountManager\s*,\s*Service\b",
        text,
    ), (
        "AccountManagerImpl must declare 'implements AccountManager, Service' "
        "so it is eligible for registerService and gets stopService called "
        "during LifecycleManager.stopServices()"
    )


def test_stop_service_clears_database_key_under_lock():
    text = _read(IMPL)
    body = re.search(
        r"public\s+void\s+stopService\s*\(\s*\)\s*throws\s+ServiceException\s*\{(.+?)\}\s*\n\s*@Override",
        text,
        re.S,
    )
    assert body, "stopService() method body not found"
    body_text = body.group(1)
    assert "synchronized" in body_text and "stateChangeLock" in body_text, (
        "stopService() must synchronize on stateChangeLock to serialise with "
        "signIn / changePassword / deleteAccount which also mutate databaseKey"
    )
    assert "databaseKey.clear()" in body_text, (
        "stopService() must call databaseKey.clear() to overwrite the "
        "underlying bytes; nulling the reference alone leaves the key in "
        "the heap until GC runs"
    )
    assert "databaseKey = null" in body_text, (
        "stopService() must null the field after clearing so a later "
        "hasDatabaseKey() check returns false"
    )


def test_module_registers_with_lifecycle_manager():
    text = _read(MODULE)
    assert re.search(r"import\s+org\.briarproject\.bramble\.api\.lifecycle\.LifecycleManager\s*;", text), (
        "AccountModule must import LifecycleManager so the provider can register the service"
    )
    provide = re.search(
        r"AccountManager\s+provideAccountManager\s*\(([^)]+)\)\s*\{(.+?)\}",
        text,
        re.S,
    )
    assert provide, "provideAccountManager method not found"
    params, body = provide.group(1), provide.group(2)
    assert "LifecycleManager" in params, (
        "provideAccountManager must take LifecycleManager as a parameter so it "
        "can call registerService on the AccountManagerImpl singleton"
    )
    assert "registerService" in body, (
        "provideAccountManager body must call lifecycleManager.registerService(impl); "
        "without this the impl is never on the services list and its "
        "stopService() never fires"
    )


def test_eager_singletons_inner_class_exists():
    text = _read(MODULE)
    assert re.search(
        r"public\s+static\s+class\s+EagerSingletons\s*\{[\s\S]*?@Inject[\s\S]*?AccountManager\s+accountManager\s*;",
        text,
    ), (
        "AccountModule.EagerSingletons must hold an @Inject AccountManager so "
        "Dagger materialises the provider at app start - otherwise registerService "
        "only fires on the first lazy lookup, which can land AFTER stopServices()"
    )


def test_eager_singletons_wired_into_bramble_core():
    text = _read(EAGER)
    assert "AccountModule.EagerSingletons" in text, (
        "BrambleCoreEagerSingletons must declare an inject(AccountModule.EagerSingletons) "
        "method AND call c.inject(new AccountModule.EagerSingletons()) in Helper.injectEagerSingletons; "
        "without both, the provider stays lazy and the registration race is open"
    )
    assert re.search(
        r"void\s+inject\s*\(\s*AccountModule\.EagerSingletons\s+init\s*\)\s*;",
        text,
    ), "inject(AccountModule.EagerSingletons) declaration is missing in the interface"
    assert re.search(
        r"c\.inject\s*\(\s*new\s+AccountModule\.EagerSingletons\s*\(\s*\)\s*\)",
        text,
    ), "Helper.injectEagerSingletons must include AccountModule.EagerSingletons in the fan-out"


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
