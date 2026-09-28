#!/usr/bin/env python3
"""
[Cross-Language Canonical Serialization Parity Guard]
Proves that Node.js canonicalJsonString() and Python json.dumps(obj, sort_keys=True)
are bit-for-bit, character-for-character identical across all data types,
special characters, Unicode, control codes, nested structures, and key ordering.
"""

import json
import subprocess
import hashlib
import sys

FIXTURES = [
    {
        "name": "Primitive Booleans and Null",
        "data": {"b_true": True, "b_false": False, "null_val": None}
    },
    {
        "name": "Numbers (Integer, Float, Negative, Exponential)",
        "data": {
            "zero": 0,
            "pos_int": 42,
            "neg_int": -1337,
            "float_pi": 3.14159,
            "small_float": -0.00045,
            "large_int": 9007199254740991
        }
    },
    {
        "name": "Quotes and Backslashes",
        "data": {
            "double_quotes": 'He said "Hello"',
            "single_quotes": "She said 'Goodbye'",
            "backslashes": "C:\\Users\\Wasti\\Documents\\file.txt",
            "mixed_slashes": "/usr/local/bin/..\\subdir/\"name\"\\file.json",
            "empty_string": ""
        }
    },
    {
        "name": "Control Characters (0x00 to 0x1F)",
        "data": {
            "nul": "\x00",
            "bell": "\x07",
            "backspace": "\b",
            "tab": "\t",
            "newline": "\n",
            "vertical_tab": "\x0b",
            "formfeed": "\f",
            "carriage_return": "\r",
            "escape": "\x1b",
            "combined": "Line1\r\nLine2\tColumn2\b\x00\x1fEnd"
        }
    },
    {
        "name": "Unicode BMP and Non-ASCII",
        "data": {
            "arabic": "مرحبا بالعالم",
            "chinese": "世界你好",
            "russian": "Привет мир",
            "japanese": "こんにちは世界",
            "accented_french": "café crème brûlée déjà vu",
            "german": "Übergrößenträger",
            "greek": "Γειά σου Κόσμε"
        }
    },
    {
        "name": "Unicode Astral Characters and Emojis",
        "data": {
            "emojis": "🚀 🛡️ 🔥 ⚡ 🧠 💻",
            "symbols": "𝄞 𝕎𝕒𝕤𝕥𝕚 𝒪𝒮",
            "combined_text": "Wasti OS 🚀 (Version 2.0) — Fast & Sovereign 🛡️"
        }
    },
    {
        "name": "Key Ordering & Nested Structures",
        "data": {
            "z_root": {
                "b_child": 2,
                "a_child": {
                    "y_sub": "value_y",
                    "x_sub": "value_x"
                }
            },
            "a_root": [
                {"k2": "second", "k1": "first"},
                {"b": [3, 2, 1], "a": [True, False, None]}
            ],
            "m_root": 100
        }
    },
    {
        "name": "Full Real-World Evidence Record",
        "data": {
            "schemaVersion": "2.0",
            "verificationScope": "BACKEND_DEPLOYMENT",
            "verificationType": "BACKEND_DEPLOYMENT_VERIFIED",
            "targetUrl": "http://127.0.0.1:8080",
            "commitSha": "e4f89d3a12b7c6e0",
            "workflowRunId": "run_9847234",
            "timestamp": 1790252200000,
            "overallVerificationStatus": "VERIFIED",
            "isReachable": True,
            "latencyMs": 14,
            "httpCode": 200,
            "subsystems": {
                "githubConfigured": True,
                "brevoConfigured": False,
                "stripeConfigured": False,
                "firebaseConfigured": False,
                "authEnforced": True
            },
            "securityBoundary": {
                "unauthenticatedStatus": 401,
                "failClosedEnforced": True,
                "authenticatedProbe": "VERIFIED_200",
                "notes": "Verified fail-closed with 🚀 and \"quotes\"."
            }
        }
    }
]

def run_cross_language_parity_tests():
    print("================================================================")
    print("  WASTI AI OS: CROSS-LANGUAGE CANONICAL JSON PARITY AUDIT       ")
    print("  Comparing Node.js canonicalJsonString() vs Python json.dumps ")
    print("================================================================")

    all_passed = True
    for i, fixture in enumerate(FIXTURES, 1):
        name = fixture["name"]
        data = fixture["data"]

        # 1. Python canonical serialization
        py_canonical = json.dumps(data, sort_keys=True)
        py_hash = hashlib.sha256(py_canonical.encode("utf-8")).hexdigest()

        # 2. Node.js canonical serialization
        input_json_str = json.dumps(data)
        node_script = f"""
        const {{ canonicalJsonString }} = require('./backend/canonical_json.js');
        const input = {json.dumps(data)};
        const out = canonicalJsonString(input);
        process.stdout.write(out);
        """

        proc = subprocess.run(
            ["node", "-e", node_script],
            capture_output=True,
            text=True,
            check=True
        )
        js_canonical = proc.stdout
        js_hash = hashlib.sha256(js_canonical.encode("utf-8")).hexdigest()

        # 3. Assert bit-for-bit identical strings and SHA-256 hashes
        if py_canonical == js_canonical and py_hash == js_hash:
            print(f"✔ [Fixture {i}/8] {name}: PASS (SHA-256: {py_hash[:16]}...)")
        else:
            all_passed = False
            print(f"✖ [Fixture {i}/8] {name}: FAILED!")
            print(f"  Python ({len(py_canonical)} bytes): {py_canonical}")
            print(f"  NodeJS ({len(js_canonical)} bytes): {js_canonical}")
            print(f"  Py Hash: {py_hash}")
            print(f"  JS Hash: {js_hash}")

    print("================================================================")
    if all_passed:
        print("✔ ALL 8/8 CROSS-LANGUAGE FIXTURES PROVEN BIT-FOR-BIT IDENTICAL!")
        print("================================================================")
        return 0
    else:
        print("✖ PARITY FAILED ON ONE OR MORE FIXTURES!")
        print("================================================================")
        return 1

if __name__ == "__main__":
    sys.exit(run_cross_language_parity_tests())
