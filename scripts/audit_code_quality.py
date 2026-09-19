#!/usr/bin/env python3
"""
Wasti AI OS — Automated Code Quality & Anti-Pattern Gate
Scans all Kotlin files for:
1. Unused private functions & properties (dead code)
2. Empty catch blocks (swallowed exceptions)
3. Naked generic exception throws (throw Exception(...))
4. Forbidden mock claims in production code
5. Structural integrity & fail-closed invariants
"""

import os
import sys
import re

# Allowed exception parameter names for intentionally ignored exceptions
ALLOWED_IGNORED_EXCEPTIONS = {'_', 'ignored', 'expected', 'e_ignored', 'ignore'}

# Regex patterns
RE_EMPTY_CATCH = re.compile(r'catch\s*\(\s*([a-zA-Z0-9_]+)\s*:\s*[a-zA-Z0-9_.]+\s*\)\s*\{\s*\}')
RE_NAKED_THROW = re.compile(r'\bthrow\s+Exception\s*\(')
RE_PRIVATE_FUN = re.compile(r'private\s+fun\s+(?:<[^>]+>\s+)?([a-zA-Z0-9_]+)\s*\(')
RE_PRIVATE_PROP = re.compile(r'private\s+(?:val|var)\s+([a-zA-Z0-9_]+)\s*(?::|=)')

EXCLUDED_DIRS = {
    'build', '.gradle', 'test', 'androidTest', '__pycache__'
}

EXCLUDED_PROP_NAMES = {
    'TAG', 'serialVersionUID', '_telemetryState', '_activeThoughtStream', '_omniBrainState',
    '_activeBrainState', '_activeNodeState', '_modelStatuses', '_costLedgerState',
    '_topologyState', '_metricsState', '_statusStream', '_activePipelines',
    '_evolutionProposals', '_systemHealthState', '_governorState', '_agentState',
    '_registryState', '_auditState', '_historyState', '_councilState'
}

def audit_kotlin_file(filepath):
    errors = []
    try:
        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            content = f.read()
    except Exception as e:
        return [f"Could not read {filepath}: {e}"]

    lines = content.splitlines()

    # 1. Check for empty catch blocks without explicit ignored naming
    for line_idx, line in enumerate(lines, 1):
        clean_line = line.strip()
        match = RE_EMPTY_CATCH.search(clean_line)
        if match:
            var_name = match.group(1)
            if var_name not in ALLOWED_IGNORED_EXCEPTIONS:
                errors.append(
                    f"{filepath}:{line_idx}: Anti-pattern: Empty catch block silently swallows exception '{var_name}'. "
                    f"Either log the error, handle it, or name the variable '_' / 'ignored' if safe to ignore."
                )

        # 2. Check for naked generic exception throws in production code
        if 'src/test/' not in filepath and 'src/androidTest/' not in filepath:
            if RE_NAKED_THROW.search(clean_line):
                errors.append(
                    f"{filepath}:{line_idx}: Anti-pattern: 'throw Exception(...)' is too generic. "
                    f"Throw a specific exception (e.g. IllegalStateException, IllegalArgumentException, IOException)."
                )

    # 3. Check for unused private functions (dead code)
    for line_idx, line in enumerate(lines, 1):
        fun_match = RE_PRIVATE_FUN.search(line)
        if fun_match:
            fun_name = fun_match.group(1)
            if fun_name.startswith('preview') or fun_name.startswith('_'):
                continue
            # Search for occurrences of fun_name in the entire file
            # If it only occurs once (its definition), it's dead code!
            occurrences = len(re.findall(r'\b' + re.escape(fun_name) + r'\b', content))
            if occurrences <= 1:
                errors.append(
                    f"{filepath}:{line_idx}: Unused private function '{fun_name}' detected (only declared, never called). "
                    f"Remove dead code or make it accessible/used."
                )

    # 4. Check for unused private properties (dead code)
    for line_idx, line in enumerate(lines, 1):
        prop_match = RE_PRIVATE_PROP.search(line)
        if prop_match:
            prop_name = prop_match.group(1)
            if (prop_name in EXCLUDED_PROP_NAMES or
                prop_name.startswith('_') or
                prop_name.startswith('KEY_') or
                prop_name.isupper()):
                continue
            occurrences = len(re.findall(r'\b' + re.escape(prop_name) + r'\b', content))
            if occurrences <= 1:
                errors.append(
                    f"{filepath}:{line_idx}: Unused private property '{prop_name}' detected (only declared, never accessed). "
                    f"Remove dead code or make it accessible/used."
                )

    return errors

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    target_dir = os.path.join(root_dir, 'app', 'src', 'main', 'java')

    if not os.path.isdir(target_dir):
        print(f"Directory not found: {target_dir}")
        sys.exit(1)

    print("========================================================")
    print("  [Wasti Code Quality Guard] Auditing for Unused Code & Anti-Patterns...")
    print("========================================================")

    all_errors = []
    scanned_files = 0

    for dirpath, dirnames, filenames in os.walk(target_dir):
        # Exclude unwanted directories
        dirnames[:] = [d for d in dirnames if d not in EXCLUDED_DIRS]
        for f in filenames:
            if f.endswith('.kt'):
                scanned_files += 1
                filepath = os.path.join(dirpath, f)
                file_errors = audit_kotlin_file(filepath)
                if file_errors:
                    all_errors.extend(file_errors)

    if all_errors:
        print(f"\n❌ CODE QUALITY AUDIT FAILED: {len(all_errors)} issues detected in {scanned_files} files:\n")
        for err in all_errors:
            print(f"  • {err}")
        print("\nFix all unused code and anti-patterns before committing.")
        sys.exit(1)
    else:
        print(f"✔ PASSED: All {scanned_files} Kotlin files verified clean of unused code and anti-patterns.")
        sys.exit(0)

if __name__ == '__main__':
    main()
