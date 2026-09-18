#!/usr/bin/env bash
# ==============================================================================
# Wasti AI OS — Sovereign Git Synchronization & Quality Gate Pipeline
# Performs pre-push quality validation, stages verified changes, and synchronizes
# ==============================================================================
set -e

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

echo "========================================================"
echo "  [WastiAI Git Deployment] Validating Pre-Push Gates..."
echo "========================================================"

if [ -f "scripts/pre-push.sh" ]; then
    bash scripts/pre-push.sh
fi

echo "Staging verified repository changes..."
git add .

COMMIT_MSG="${1:-Automated code tracking adjustment via free AI engine workflow}"
git commit -m "$COMMIT_MSG" || echo "Working tree clean; no changes to commit."

echo "Synchronizing with remote origin main..."
git push origin main

echo "========================================================"
echo "  ? [Git Deployment Complete] All changes synchronized."
echo "========================================================"