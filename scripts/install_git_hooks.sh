#!/bin/sh
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

cp scripts/pre-push.sh .git/hooks/pre-push
if command -v termux-fix-shebang >/dev/null 2>&1; then
  termux-fix-shebang .git/hooks/pre-push
fi
chmod +x .git/hooks/pre-push scripts/pre-push.sh
echo "✔ Pre-push git hook installed and ready."
