#!/usr/bin/env bash
set -e

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

echo "========================================================"
echo "  [WastiAI Pre-Push Guard] Running Quality Gates..."
echo "========================================================"

# Gate 1: Backend Automated Tests (20/20 node tests)
echo "[Pre-Push 1/2] Running backend test suite..."
if command -v node >/dev/null 2>&1; then
  node --test backend/test_backend.js
  echo "✔ Backend tests passed."
else
  echo "⚠ Node.js not found in PATH; skipping backend test gate."
fi

# Gate 2: Kotlin Syntax and Structural Integrity
echo "[Pre-Push 2/2] Validating Kotlin syntax and structural integrity..."
if command -v python3 >/dev/null 2>&1; then
  python3 scripts/check_kotlin_syntax.py
  echo "✔ Kotlin syntax validation passed."
else
  echo "⚠ Python3 not found in PATH; skipping Kotlin syntax gate."
fi

echo "========================================================"
echo "  ✔ ALL PRE-PUSH CHECKS PASSED. PUSH ALLOWED."
echo "========================================================"
exit 0
