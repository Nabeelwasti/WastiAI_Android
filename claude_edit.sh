#!/usr/bin/env bash
# ==============================================================================
# Wasti AI OS — Claude Free Edit & Deployment Pipeline
# Executes claude_free_edit.py and runs sync_git.sh on success
# ==============================================================================
set -e

if [ $# -lt 2 ]; then
    echo "Usage: bash claude_edit.sh <filename> '<instruction>'"
    exit 1
fi

python3 claude_free_edit.py "$1" "$2"
bash scripts/sync_git.sh "feat(ai): apply free AI edit to $1"