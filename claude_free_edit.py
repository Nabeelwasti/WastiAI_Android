#!/usr/bin/env python3
import os
import sys
import requests
import json

API_URL = "https://openrouter.ai/api/v1/chat/completions"
TARGET_MODEL = "openrouter/free"

try:
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "scripts"))
    import wasti_vault_bridge
    HAS_VAULT_BRIDGE = True
except Exception:
    HAS_VAULT_BRIDGE = False

def is_placeholder(val_str):
    if HAS_VAULT_BRIDGE:
        return wasti_vault_bridge.is_placeholder(val_str)
    if not val_str:
        return True
    upper = str(val_str).strip().upper()
    placeholders = {
        "MY_KEY", "YOUR_KEY", "PLACEHOLDER", "ENTER_KEY_HERE", "NULL",
        "UNDEFINED", "NONE", "DUMMY", "TODO", "CHANGEME", "FAKE", "SAMPLE_KEY", "TEST_KEY"
    }
    if upper in placeholders or upper.startswith("MY_") or upper.startswith("YOUR_") or upper.startswith("TODO_") or upper.startswith("TEST_KEY"):
        return True
    return False

def resolve_openrouter_key():
    """
    Unifies API key resolution across both Kotlin (CredentialRegistry.kt) and Python runtimes
    via scripts/wasti_vault_bridge.py:
    1. Direct environment variable (OPENROUTER_API_KEY)
    2. Encrypted Vault token directory: ~/.wasti_ai/tokens/ (matching CredentialRegistry IPC bridge)
    3. JSON credentials registry: ~/.wasti_ai/vault.json or ~/.wasti_ai/credentials.json
    4. Fallback workspace .env file
    """
    if HAS_VAULT_BRIDGE:
        val = wasti_vault_bridge.get_secret("OPENROUTER_API_KEY")
        if val and not wasti_vault_bridge.is_placeholder(val):
            return val

    # Direct environment variable fallback
    env_val = os.environ.get("OPENROUTER_API_KEY")
    if env_val and not is_placeholder(env_val):
        return env_val.strip()

    # 2. Encrypted Vault token directory (~/.wasti_ai/tokens/)
    user_home = os.path.expanduser("~")
    token_dir = os.path.join(user_home, ".wasti_ai", "tokens")
    candidates = [
        "OPENROUTER_API_KEY",
        "openrouter_api_key",
        "openrouter.token",
        "openrouter_key"
    ]
    if os.path.isdir(token_dir):
        for candidate in candidates:
            token_path = os.path.join(token_dir, candidate)
            if os.path.isfile(token_path):
                try:
                    with open(token_path, "r", encoding="utf-8") as f:
                        val = f.read().strip()
                        if val and not is_placeholder(val):
                            return val
                except Exception:
                    pass

    # 3. JSON credentials registry
    for vault_name in ["vault.json", "credentials.json"]:
        vault_path = os.path.join(user_home, ".wasti_ai", vault_name)
        if os.path.isfile(vault_path):
            try:
                with open(vault_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    val = data.get("OPENROUTER_API_KEY") or data.get("openrouter_api_key")
                    if val and not is_placeholder(val):
                        return str(val).strip()
            except Exception:
                pass

    # 4. Fallback .env files
    for env_path in [".env", os.path.join(user_home, ".env")]:
        if os.path.isfile(env_path):
            try:
                with open(env_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line.startswith("#") or "=" not in line:
                            continue
                        k, v = line.split("=", 1)
                        if k.strip() in ("OPENROUTER_API_KEY", "openrouter_api_key"):
                            val = v.strip().strip("\"'")
                            if val and not is_placeholder(val):
                                return val
            except Exception:
                pass

    return None

OPENROUTER_KEY = resolve_openrouter_key()

def ask_claude_to_edit(target_file, prompt_instruction):
    if not os.path.exists(target_file):
        print(f"Error: Target file '{target_file}' not found.")
        return

    key = resolve_openrouter_key()
    if not key or is_placeholder(key):
        print("\n[WastiAI Authentication Error] OPENROUTER_API_KEY not configured.")
        print("Please configure your OpenRouter key via one of the following:")
        print("  1. Save key to ~/.wasti_ai/tokens/OPENROUTER_API_KEY")
        print("  2. Enter key in Wasti AI OS Settings -> Account Hub Vault")
        print("  3. Export OPENROUTER_API_KEY='your-key-here'")
        return

    with open(target_file, "r", encoding="utf-8", errors="replace") as f:
        original_code = f.read()

    print(f"[WastiAI OS] Packaging '{target_file}' for Claude Sonnet via Unified OpenRouter Bridge...")

    headers = {
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Linux; Android 14; Redmi Note 14 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "HTTP-Referer": "https://github.com",
        "X-Title": "WastiAI OS Mobile CLI Client"
    }

    payload = {
        "model": TARGET_MODEL,
        "messages": [
            {
                "role": "user",
                "content": f"You are an expert Android software engineer. Edit the code context precisely as requested.\n\nFile path context: {target_file}\n\nOriginal Source Code Contents:\n{original_code}\n\nModification Objective: {prompt_instruction}\n\nCRITICAL: Return ONLY the final updated source code text. Never wrap code blocks inside markdown triple backticks (```), do not add descriptions, and do not add greetings."
            }
        ]
    }

    try:
        response = requests.post(API_URL, json=payload, headers=headers, timeout=60)

        if response.status_code != 200:
            print(f"\nAPI Error Status Code: {response.status_code}")
            print(f"Raw Response: {response.text}")
            return

        if response.text.strip().startswith("<!DOCTYPE") or "<html>" in response.text:
            print("\n[OpenRouter Block] Cloud servers sent back an HTML error page.")
            print("This usually means your token is invalid or expired. Checking contents below:")
            print(response.text[:500])
            return

        response_data = response.json()
        new_code = response_data['choices'][0]['message']['content']

        with open(target_file, "w", encoding="utf-8") as f:
            f.write(new_code.strip())
        print(f"\n[Success] File '{target_file}' updated locally by Claude pipeline.")

        print("Executing automated Git push sync via PAT Token configuration...")
        os.system("git add . && git commit -m 'Automated code tracking adjustment via free AI engine workflow' && git push origin main")
        print("[Git Deployment Complete]")

    except Exception as e:
        print(f"\nRuntime Processing Exception: {e}")
        if 'response' in locals():
            print(f"Server response dump:\n{response.text[:500]}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python claude_free_edit.py <filename> '<instruction>'")
    else:
        ask_claude_to_edit(sys.argv[1], sys.argv[2])
