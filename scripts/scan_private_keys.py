#!/usr/bin/env python3
import os
import re
import sys

def main():
    key_block_regex = re.compile(
        r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----\s*[A-Za-z0-9+/=]{40,}",
        re.MULTILINE
    )

    found_leak = False
    scan_targets = ["app/build/outputs/apk", "app/src/main", "backend", ".github/workflows"]
    for target in scan_targets:
        if not os.path.exists(target):
            continue
        for root, dirs, files in os.walk(target):
            for file in files:
                filepath = os.path.join(root, file)
                try:
                    with open(filepath, "rb") as f:
                        content = f.read().decode("latin1", errors="ignore")
                    if key_block_regex.search(content):
                        print(f"ERROR: Actual embedded private key with payload found in {filepath}!")
                        found_leak = True
                except Exception:
                    pass

    if found_leak:
        sys.exit(1)
    print("SUCCESS: Private key boundary verified — no actual private keys embedded.")

if __name__ == "__main__":
    main()
