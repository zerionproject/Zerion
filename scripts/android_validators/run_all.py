"""Run every Android-side validator and print a single summary.

Static code-shape checks that can be run on Windows without an Android
build or emulator. Each script answers ONE question about the Sprint 2
Android fixes: did the intended change land in the repo at the right
file:line, and has it been undone or contradicted somewhere else.

Usage:
    python scripts/android_validators/run_all.py

Exit code = number of failed tests across all suites.
"""

from __future__ import annotations

import importlib
import sys
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

SUITES = [
    "tests.test_disappearing_picker_layout",
    "tests.test_text_send_controller_silent_retry",
    "tests.test_channel_join_bootstrap_async",
    "tests.test_tor_tcp_nodelay",
    "tests.test_account_key_wipe_on_shutdown",
    "tests.test_uri_scheme_allowlist",
    "tests.test_no_logging_invariants",
]


def run_suite(module_name: str) -> tuple[int, int]:
    try:
        module = importlib.import_module(module_name)
    except Exception:
        print(f"\n[{module_name}] FAILED TO IMPORT:")
        traceback.print_exc()
        return (0, 1)

    passed = 0
    failed = 0
    print(f"\n[{module_name}]")
    for name, fn in sorted(vars(module).items()):
        if not name.startswith("test_") or not callable(fn):
            continue
        try:
            fn()
            passed += 1
            print(f"  ok    {name}")
        except AssertionError as e:
            failed += 1
            print(f"  FAIL  {name}: {e}")
        except Exception as e:
            failed += 1
            print(f"  ERROR {name}: {type(e).__name__}: {e}")
    return passed, failed


def main() -> int:
    total_passed = 0
    total_failed = 0
    for suite in SUITES:
        p, f = run_suite(suite)
        total_passed += p
        total_failed += f

    print()
    print("=" * 60)
    print(f"  passed: {total_passed}")
    print(f"  failed: {total_failed}")
    print(f"  total : {total_passed + total_failed}")
    print("=" * 60)
    return 1 if total_failed else 0


if __name__ == "__main__":
    sys.exit(main())
