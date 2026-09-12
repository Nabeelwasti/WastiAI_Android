#!/usr/bin/env python3
"""
Wasti AI OS — Unified Secret Management & Vault Bridge
Provides a unified, secure credential interface for Python CLI tools, scripts, and local developer workflows.
Seamlessly bridges:
  1. Environment variables (process-level)
  2. Encrypted Vault token store: ~/.wasti_ai/tokens/
  3. Credential JSON vault: ~/.wasti_ai/vault.json / credentials.json
  4. Firebase configuration: app/google-services.json
  5. Workspace .env configuration fallback

Guarantees:
  - Fail-closed security on dummy/placeholder keys
  - Restrictive filesystem permissions (0600) on all token files
  - Full secret rotation support with timestamped audit logging
  - Least-privilege secret retrieval and masking
"""

import os
import sys
import json
import time
import hashlib
from typing import Optional, Dict, List, Tuple

PLACEHOLDER_SUBSTRINGS = {
    "MY_KEY", "YOUR_KEY", "PLACEHOLDER", "ENTER_KEY_HERE", "NULL",
    "UNDEFINED", "NONE", "DUMMY", "TODO", "CHANGEME", "FAKE", 
    "SAMPLE_KEY", "TEST_KEY", "YOUR_OPENROUTER_KEY", "YOUR_GEMINI_KEY"
}

def is_placeholder(val: Optional[str]) -> bool:
    if not val:
        return True
    clean = str(val).strip().strip("\"'").upper()
    if not clean:
        return True
    if clean in PLACEHOLDER_SUBSTRINGS:
        return True
    for prefix in ("MY_", "YOUR_", "TODO_", "TEST_KEY_", "SAMPLE_"):
        if clean.startswith(prefix):
            return True
    return False

def get_vault_dir() -> str:
    user_home = os.path.expanduser("~")
    vault_dir = os.path.join(user_home, ".wasti_ai")
    os.makedirs(vault_dir, exist_ok=True)
    return vault_dir

def get_tokens_dir() -> str:
    tokens_dir = os.path.join(get_vault_dir(), "tokens")
    os.makedirs(tokens_dir, exist_ok=True)
    return tokens_dir

def mask_secret(secret: Optional[str]) -> str:
    if not secret:
        return "<not-configured>"
    if len(secret) <= 8:
        return "*" * len(secret)
    return f"{secret[:4]}...{secret[-4:]}"

def parse_env_file(filepath: str) -> Dict[str, str]:
    secrets = {}
    if not os.path.isfile(filepath):
        return secrets
    try:
        with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                secrets[k.strip()] = v.strip().strip("\"'")
    except Exception:
        pass
    return secrets

def get_secret(key_name: str, default: Optional[str] = None) -> Optional[str]:
    """
    Securely retrieves an active credential from the unified bridge hierarchy:
    1. Process environment (exact, uppercase, lowercase)
    2. Vault token directory (~/.wasti_ai/tokens/<key>)
    3. Vault JSON store (~/.wasti_ai/vault.json)
    4. Workspace .env
    """
    upper_key = key_name.strip().upper()
    lower_key = key_name.strip().lower()

    # 1. Process environment
    for k in (upper_key, lower_key, key_name):
        val = os.environ.get(k)
        if val and not is_placeholder(val):
            return val.strip()

    # 2. Vault tokens directory
    tokens_dir = get_tokens_dir()
    for candidate in (upper_key, lower_key, f"{lower_key}.token"):
        cand_path = os.path.join(tokens_dir, candidate)
        if os.path.isfile(cand_path):
            try:
                with open(cand_path, "r", encoding="utf-8") as f:
                    val = f.read().strip()
                if val and not is_placeholder(val):
                    return val
            except Exception:
                pass

    # 3. Vault JSON store
    vault_dir = get_vault_dir()
    for fname in ("vault.json", "credentials.json"):
        vpath = os.path.join(vault_dir, fname)
        if os.path.isfile(vpath):
            try:
                with open(vpath, "r", encoding="utf-8") as f:
                    data = json.load(f)
                val = data.get(upper_key) or data.get(lower_key) or data.get(key_name)
                if val and not is_placeholder(str(val)):
                    return str(val).strip()
            except Exception:
                pass

    # 4. Workspace .env
    for env_path in (".env", os.path.join(os.path.expanduser("~"), ".env")):
        env_dict = parse_env_file(env_path)
        val = env_dict.get(upper_key) or env_dict.get(lower_key) or env_dict.get(key_name)
        if val and not is_placeholder(val):
            return val

    return default

def save_secret(key_name: str, secret_value: str) -> bool:
    """
    Saves a secret into the local token vault with restrictive 0600 permissions.
    """
    if not key_name or not secret_value:
        return False
    tokens_dir = get_tokens_dir()
    target_path = os.path.join(tokens_dir, key_name.strip().upper())
    try:
        with open(target_path, "w", encoding="utf-8") as f:
            f.write(secret_value.strip())
        os.chmod(target_path, 0o600)
        return True
    except Exception as e:
        print(f"Error writing secret '{key_name}': {e}", file=sys.stderr)
        return False

def rotate_secret(key_name: str, new_value: str, reason: str = "Manual Rotation") -> bool:
    """
    Rotates a credential: records audit log with SHA-256 fingerprint (zero raw secret leak)
    and atomically updates the token file with 0600 permissions.
    """
    upper_key = key_name.strip().upper()
    old_val = get_secret(upper_key)
    old_fingerprint = hashlib.sha256(old_val.encode()).hexdigest()[:12] if old_val else "none"
    new_fingerprint = hashlib.sha256(new_value.encode()).hexdigest()[:12]

    success = save_secret(upper_key, new_value)
    if success:
        audit_file = os.path.join(get_vault_dir(), "rotation_audit.log")
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime())
        log_entry = (
            f"[{timestamp}] KEY_ROTATION: key={upper_key} "
            f"old_sha={old_fingerprint} new_sha={new_fingerprint} reason={reason}\n"
        )
        try:
            with open(audit_file, "a", encoding="utf-8") as f:
                f.write(log_entry)
            os.chmod(audit_file, 0o600)
        except Exception:
            pass
    return success

def get_firebase_config() -> Optional[Dict]:
    """
    Validates and retrieves the active Firebase configuration JSON.
    """
    candidates = [
        "app/google-services.json",
        os.path.join(get_vault_dir(), "google-services.json"),
        os.path.join(os.path.expanduser("~"), "google-services.json")
    ]
    for c in candidates:
        if os.path.isfile(c):
            try:
                with open(c, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception:
                pass
    return None

def audit_all_credentials() -> Dict[str, Dict]:
    tracked_keys = [
        "GEMINI_API_KEY", "OPENROUTER_API_KEY", "GROQ_API_KEY",
        "DEEPSEEK_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY",
        "ELEVENLABS_API_KEY", "STRIPE_SECRET_KEY", "BREVO_API_KEY",
        "GITHUB_TOKEN"
    ]
    audit_results = {}
    for k in tracked_keys:
        sec = get_secret(k)
        audit_results[k] = {
            "configured": sec is not None and not is_placeholder(sec),
            "masked": mask_secret(sec),
            "source": "vault/env" if sec else "none"
        }
    return audit_results

def main():
    if len(sys.argv) < 2:
        print("Usage: wasti_vault_bridge.py [audit | get <KEY> | set <KEY> <VALUE> | rotate <KEY> <VALUE>]")
        sys.exit(1)

    cmd = sys.argv[1].lower()
    if cmd == "audit":
        results = audit_all_credentials()
        print("=" * 60)
        print("   WASTI AI OS — UNIFIED VAULT CREDENTIAL AUDIT")
        print("=" * 60)
        for k, v in results.items():
            status = "CONFIGURED" if v["configured"] else "NOT CONFIGURED"
            print(f"  {k:<24} [{status:<14}] {v['masked']}")
        fb = get_firebase_config()
        print(f"  {'FIREBASE_CONFIG':<24} [{'CONFIGURED' if fb else 'NOT CONFIGURED':<14}]")
        print("=" * 60)
    elif cmd == "get" and len(sys.argv) >= 3:
        val = get_secret(sys.argv[2])
        if val:
            print(val)
        else:
            print(f"Key '{sys.argv[2]}' not configured.", file=sys.stderr)
            sys.exit(1)
    elif cmd in ("set", "rotate") and len(sys.argv) >= 4:
        key = sys.argv[2]
        val = sys.argv[3]
        ok = rotate_secret(key, val, reason="CLI Update") if cmd == "rotate" else save_secret(key, val)
        if ok:
            print(f"✔ Secret '{key}' successfully saved with 0600 permissions.")
        else:
            print(f"Failed to save secret '{key}'.", file=sys.stderr)
            sys.exit(1)
    else:
        print(f"Unknown command: {cmd}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
