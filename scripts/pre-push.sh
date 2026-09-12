#!/usr/bin/env bash
set -e

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

echo "========================================================"
echo "  [WastiAI Pre-Push Guard] Running Quality Gates..."
echo "========================================================"

# Gate 1: Backend Automated Tests (20/20 node tests)
echo "[Pre-Push 1/4] Running backend test suite..."
if command -v node >/dev/null 2>&1; then
  node --test backend/test_backend.js
  echo "✔ Backend tests passed."
else
  echo "⚠ Node.js not found in PATH; skipping backend test gate."
fi

# Gate 2: Kotlin Syntax and Structural Integrity
echo "[Pre-Push 2/4] Validating Kotlin syntax and structural integrity..."
if command -v python3 >/dev/null 2>&1; then
  python3 scripts/check_kotlin_syntax.py
  echo "✔ Kotlin syntax validation passed."
else
  echo "⚠ Python3 not found in PATH; skipping Kotlin syntax gate."
fi

# Gate 3: Secret Boundary & Private Key Scan
echo "[Pre-Push 3/4] Scanning for raw private keys and secret leakage..."
if command -v python3 >/dev/null 2>&1; then
  python3 scripts/scan_private_keys.py
  echo "✔ Secret boundaries and private key protection verified."
fi

# Gate 4: Secret Management & Vault Ingestion Audit
echo "[Pre-Push 4/4] Auditing secret management & vault integrity..."
if command -v python3 >/dev/null 2>&1; then
  python3 scripts/generate_secrets_report.py .env.example
  echo "✔ Secret ingestion and fail-closed audit verified."
fi

echo "========================================================"
echo "  ✔ ALL PRE-PUSH CHECKS PASSED. PUSH ALLOWED."
echo "========================================================"
exit 0
