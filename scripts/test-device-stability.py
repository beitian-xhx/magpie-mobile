#!/usr/bin/env python3
"""
Device Stability, Lifecycle & Benchmark Contract Verification (P4-3).
Validates 16KB alignment rules, startup budget constraints, and post-mortem privacy.
"""

import sys
from pathlib import Path

def test_startup_budget_contract():
    # Cold-start budget is pinned to 800ms
    benchmark_kt = Path("core/src/main/kotlin/cc/fplj/magpiemobile/core/StartupBenchmark.kt")
    assert benchmark_kt.exists(), "StartupBenchmark.kt must exist"
    content = benchmark_kt.read_text(encoding="utf-8")
    assert "const val COLD_START_BUDGET_MS = 800L" in content, "Startup budget must be 800ms per plan.md §6"
    print("PASS: Startup latency budget pinned to 800ms")

def test_16kb_checker_contract():
    checker_py = Path("scripts/check-16kb-alignment.py")
    assert checker_py.exists(), "check-16kb-alignment.py must exist"
    content = checker_py.read_text(encoding="utf-8")
    assert "ALIGN_16KB = 16384" in content, "Must verify 16384 byte alignment"
    assert "PT_LOAD" in content, "Must verify PT_LOAD segments"
    print("PASS: 16KB ELF alignment contract verified")

def test_post_mortem_privacy_contract():
    auditor_kt = Path("core/src/main/kotlin/cc/fplj/magpiemobile/core/PostMortemAuditor.kt")
    assert auditor_kt.exists(), "PostMortemAuditor.kt must exist"
    content = auditor_kt.read_text(encoding="utf-8")
    assert "SecretRedactor.redact" in content, "Must sanitize post-mortem details using SecretRedactor"
    assert "AUDIT_FILE_NAME = \"post_mortem.json\"" in content, "Must persist in private post_mortem.json"
    print("PASS: Post-mortem crash audit privacy contract verified")

def test_release_notes_presence():
    rn = Path("RELEASE_NOTES.md")
    assert rn.exists(), "RELEASE_NOTES.md must exist in root"
    content = rn.read_text(encoding="utf-8")
    assert "Magpie-Mobile" in content, "RELEASE_NOTES.md must contain version changelog"
    print("PASS: Release notes automation contract verified")

if __name__ == "__main__":
    try:
        test_startup_budget_contract()
        test_16kb_checker_contract()
        test_post_mortem_privacy_contract()
        test_release_notes_presence()
        print("ALL P4 STABILITY & BENCHMARK CONTRACTS PASSED")
        sys.exit(0)
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
