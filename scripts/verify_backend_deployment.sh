#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Wasti AI OS - Backend Deployment Verification Pipeline (P0-05)
# Generates cryptographic backend deployment evidence with zero fabrication.
# ==============================================================================

TARGET_URL="${1:-${WASTI_BACKEND_URL:-http://127.0.0.1:8080}}"
OUTPUT_JSON="${2:-backend_deployment_evidence.json}"

echo "========================================================"
echo "  WASTI AI OS: BACKEND DEPLOYMENT VERIFIER              "
echo "  Target: $TARGET_URL"
echo "  Output: $OUTPUT_JSON"
echo "========================================================"

# Execute Node.js deployment verifier
node backend/verify_backend_deployment.js "$TARGET_URL" "$OUTPUT_JSON" "$@"
