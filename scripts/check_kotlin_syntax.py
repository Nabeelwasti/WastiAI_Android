#!/usr/bin/env python3
import sys
import os

def check_file_syntax(filepath):
    """
    Validates structural syntax of a Kotlin file:
    - Balanced braces, brackets, and parentheses
    - Balanced string literals and multiline raw strings
    - Full support for nested string template expressions (${...})
    """
    try:
        with open(filepath, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
    except Exception as e:
        return f"Unable to read file: {e}"

    lines = content.splitlines()
    stack = []
    # mode_stack entries:
    # 'CODE': normal code
    # 'STR': double-quoted string
    # 'RAW_STR': triple-quoted string
    # 'BLOCK_COMMENT': /* ... */
    mode_stack = ['CODE']
    escape = False

    for line_idx, line in enumerate(lines, 1):
        i = 0
        n = len(line)
        while i < n:
            ch = line[i]
            cur_mode = mode_stack[-1]

            if cur_mode == 'BLOCK_COMMENT':
                if ch == '*' and i + 1 < n and line[i+1] == '/':
                    mode_stack.pop()
                    i += 2
                    continue
                i += 1
                continue

            if cur_mode == 'RAW_STR':
                if ch == '"' and i + 2 < n and line[i+1] == '"' and line[i+2] == '"':
                    mode_stack.pop()
                    i += 3
                    continue
                elif ch == '$' and i + 1 < n and line[i+1] == '{':
                    stack.append(('${', line_idx, i + 1))
                    mode_stack.append('CODE')
                    i += 2
                    continue
                i += 1
                continue

            if cur_mode == 'STR':
                if escape:
                    escape = False
                    i += 1
                    continue
                if ch == '\\':
                    escape = True
                    i += 1
                    continue
                if ch == '"':
                    mode_stack.pop()
                    i += 1
                    continue
                if ch == '$' and i + 1 < n and line[i+1] == '{':
                    stack.append(('${', line_idx, i + 1))
                    mode_stack.append('CODE')
                    i += 2
                    continue
                i += 1
                continue

            # In CODE mode:
            # Check for comments
            if ch == '/' and i + 1 < n:
                if line[i+1] == '/':
                    # Line comment - skip remainder of line
                    break
                elif line[i+1] == '*':
                    mode_stack.append('BLOCK_COMMENT')
                    i += 2
                    continue

            # Check for triple quotes
            if ch == '"' and i + 2 < n and line[i+1] == '"' and line[i+2] == '"':
                mode_stack.append('RAW_STR')
                i += 3
                continue

            # Check for single string
            if ch == '"':
                mode_stack.append('STR')
                escape = False
                i += 1
                continue

            # Check for character literals
            if ch == '\'':
                if i + 2 < n and line[i+1] != '\\' and line[i+2] == '\'':
                    i += 3
                    continue
                elif i + 3 < n and line[i+1] == '\\' and line[i+3] == '\'':
                    i += 4
                    continue

            # Structural balancing
            if ch in '({[':
                stack.append((ch, line_idx, i + 1))
            elif ch in ')}]':
                if not stack:
                    return f"Unmatched closing '{ch}' at line {line_idx}:{i+1}"
                opener, open_line, open_col = stack.pop()
                if opener == '${':
                    if ch == '}':
                        # End of string interpolation! Pop CODE mode back to string
                        if len(mode_stack) > 1 and mode_stack[-1] == 'CODE':
                            mode_stack.pop()
                    else:
                        return f"Mismatched bracket in string template: opened '${{' at line {open_line}:{open_col}, closed with '{ch}' at line {line_idx}:{i+1}"
                else:
                    expected = {')': '(', '}': '{', ']': '['}[ch]
                    if opener != expected:
                        return f"Mismatched bracket: opened '{opener}' at line {open_line}:{open_col}, closed with '{ch}' at line {line_idx}:{i+1}"

            i += 1

        if mode_stack[-1] == 'STR':
            # Single-line strings must terminate on the same line in Kotlin
            return f"Unclosed string literal at line {line_idx}"

    if 'BLOCK_COMMENT' in mode_stack:
        return "Unterminated block comment (/* ... */) at end of file"
    if 'RAW_STR' in mode_stack:
        return "Unterminated triple-quoted raw string (\"\"\" ... \"\"\") at end of file"
    if stack:
        opener, open_line, open_col = stack[-1]
        return f"Unclosed '{opener}' opened at line {open_line}:{open_col}"

    return None

def main():
    root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    target_dirs = [
        os.path.join(root_dir, "app", "src"),
    ]
    
    kt_files = []
    for d in target_dirs:
        if not os.path.exists(d):
            continue
        for parent, _, files in os.walk(d):
            for f in files:
                if f.endswith(".kt") or f.endswith(".kts"):
                    kt_files.append(os.path.join(parent, f))

    for root_kts in ["build.gradle.kts", "settings.gradle.kts"]:
        p = os.path.join(root_dir, root_kts)
        if os.path.exists(p):
            kt_files.append(p)

    errors = []
    print(f"[Kotlin Syntax Guard] Checking {len(kt_files)} Kotlin files for structural syntax integrity...")
    for f in kt_files:
        err = check_file_syntax(f)
        if err:
            rel = os.path.relpath(f, root_dir)
            errors.append(f"  ❌ {rel}: {err}")

    if errors:
        print("\n[Kotlin Syntax Guard] FAILED! Found syntax errors:")
        for e in errors:
            print(e)
        sys.exit(1)
    else:
        print(f"[Kotlin Syntax Guard] PASSED! All {len(kt_files)} Kotlin files have valid structural syntax.")
        sys.exit(0)

if __name__ == "__main__":
    main()
