#!/usr/bin/env python3
"""
Stage 1: String Extraction
Parses Java source files via AST to extract all string literals
with surrounding context for AI classification and translation.

Input:  ghidra/Ghidra/Framework/Docking/src/main/java/docking/widgets/*.java
Output: i18n-scripts/extract/output/strings.json
"""

import json
import os
import re
import sys
from pathlib import Path


# =============================================================================
# Configuration
# =============================================================================

# Source directories to scan (relative to repo root)
# Each is a sub-path of the Docking module
DOCKING_BASE = Path("ghidra/Ghidra/Framework/Docking/src/main/java/docking")
SOURCE_DIRS = [
    DOCKING_BASE,                    # top-level files (DialogComponentProvider, etc.)
    DOCKING_BASE / "action",         # DockingAction, MenuData
    DOCKING_BASE / "menu",           # menu rendering
    DOCKING_BASE / "options",        # options panels
    DOCKING_BASE / "widgets",        # already translated, kept for consistency
]

# Output file
OUTPUT_DIR = Path("i18n-scripts/extract/output")
OUTPUT_FILE = OUTPUT_DIR / "strings.json"

# Patterns to skip (non-UI strings)
SKIP_PATTERNS = [
    # Empty or whitespace-only
    lambda s: not s.strip(),
    # Pure numbers
    lambda s: s.strip().isdigit(),
    # Pure punctuation/symbols
    lambda s: all(c in '[]{}()/:,;.-_+=*&^%$#@!~`<>?\\|\'" ' for c in s),
    # Single character
    lambda s: len(s.strip()) <= 1,
    # Package-like patterns (e.g. "ghidra.util", "java.lang.String")
    lambda s: bool(__import__('re').match(r'^[a-z]+(\.[a-z]+)+$', s.strip())),
    # URLs
    lambda s: s.strip().startswith(('http://', 'https://')),
    # All-uppercase constants
    lambda s: s.strip().isupper() and '_' in s,
]


def is_regex_pattern(s: str) -> bool:
    """Check if a string looks like a regex pattern (not UI text)."""
    # Common regex constructs
    regex_indicators = [
        r'^\^',            # starts with ^
        r'\$$',             # ends with $
        r'\\[dDwWsS]',    # \d \D \w \W \s \S
        r'\[0-9\]',       # character class
        r'\[a-z\]',        # character class
        r'\[A-Z\]',        # character class
        r'\\\.',          # escaped dot
        r'\.\*',           # .*
        r'\.\+',           # .+
        r'\*\?',           # *?
        r'\+\?',           # +?
    ]
    for indicator in regex_indicators:
        if re.search(indicator, s):
            return True
    return False


def is_skip_candidate(s: str) -> bool:
    """Check if a string literal is likely NOT a user-visible UI string."""
    for pattern in SKIP_PATTERNS:
        try:
            if pattern(s):
                return True
        except Exception:
            continue
    return False


def is_escape_fragment(s: str) -> bool:
    """Check if a string looks like it contains escape/formatting fragments."""
    if '\\n' in s or '\\t' in s or '\\r' in s:
        return True
    # Looks like a concatenation fragment
    if re.match(r'^[\s\n\t\r+]+$', s):
        return True
    return False


# Key binding API patterns - these contexts indicate a string is a keystroke spec
_KEY_BINDING_APIS = [
    'KeyBindingData(',
    'setKeyBindingData(',
    '.keyBinding(',
    'KeyStroke.getKeyStroke(',
    # Indirect key binding via constructors/placeholders
    'GfcActionPlaceholder(',
    'ACTION_NAME_',
]

# Known key names that appear in key binding contexts
_KEY_NAMES = {'Enter', 'Escape', 'Space', 'HOME', 'END', 'TAB',
              'DELETE', 'BACK_SPACE', 'INSERT', 'PAGE_UP', 'PAGE_DOWN'}


def is_key_binding_context(code_line: str) -> bool:
    """Check if the line is setting up a key binding (keystroke must NOT be translated)."""
    for api in _KEY_BINDING_APIS:
        if api in code_line:
            return True
    return False


def is_key_binding_value(code_line: str, value: str) -> bool:
    """Check if the value looks like a key binding specification."""
    # Patterns like "Control F", "Alt Up", "Ctrl Shift X"
    if re.match(r'^(Control|Alt|Shift|Meta|Ctrl)(\s+\w)+$', value):
        return True
    # Single key names used in keystroke context
    if value in _KEY_NAMES and ('KeyStroke' in code_line or 'keyBinding' in code_line
                                 or 'KeyBinding' in code_line):
        return True
    return False


def extract_strings_from_file(filepath: Path, start_id: int = 0) -> tuple[list[dict], int]:
    """
    Extract string literals from a Java source file using javalang AST.
    Returns (results, next_id) - list of dicts and the next available ID counter.
    """
    import javalang

    results = []
    next_id = start_id
    source_code = filepath.read_text(encoding="utf-8")
    lines = source_code.split("\n")

    try:
        tree = javalang.parse.parse(source_code)
    except javalang.parser.JavaSyntaxError as e:
        print(f"  [WARN] Parse error in {filepath}: {e}", file=sys.stderr)
        return [], next_id
    except Exception as e:
        print(f"  [WARN] Unexpected error parsing {filepath}: {e}", file=sys.stderr)
        return [], next_id

    # Walk the AST to find string Literal nodes
    for path, node in tree.filter(javalang.tree.Literal):
        value = node.value
        # Filter: only string literals (values wrapped in double quotes)
        if not (value.startswith('"') and value.endswith('"')):
            continue
        # Strip surrounding quotes
        value = value[1:-1]
        # Skip strings with embedded quotes (usually string templates, not pure UI)
        if '"' in value:
            continue
        # Skip strings with newline or tab escape sequences (usually code fragments)
        if is_escape_fragment(value):
            continue
        if is_skip_candidate(value):
            continue
        # Skip regex patterns (not UI text)
        if is_regex_pattern(value):
            continue

        # Determine line number from node position
        line_num = node.position.line if node.position else 0

        # Find enclosing class and method
        class_name = ""
        method_name = ""
        for ancestor in path:
            if isinstance(ancestor, javalang.tree.ClassDeclaration):
                class_name = ancestor.name
            elif isinstance(ancestor, javalang.tree.MethodDeclaration):
                method_name = ancestor.name
            elif isinstance(ancestor, javalang.tree.ConstructorDeclaration):
                method_name = "<init>"

        # Get surrounding lines for context
        start = max(0, line_num - 4)
        end = min(len(lines), line_num + 3)
        surrounding = lines[start:end]

        # The code line itself
        code_line = lines[line_num - 1].strip() if 1 <= line_num <= len(lines) else ""

        # Skip key binding strings (must not be translated)
        if is_key_binding_context(code_line) or is_key_binding_value(code_line, value):
            continue

        result_id = f"docking_widgets_{next_id:04d}"
        next_id += 1

        results.append({
            "id": result_id,
            "file": str(filepath),
            "line": line_num,
            "original": value,
            "context": {
                "class": class_name,
                "method": method_name,
                "code": code_line,
                "surrounding_lines": surrounding,
            },
        })

    return results, next_id


def extract_all(scan_dir: Path) -> list[dict]:
    """Scan all Java files in scan_dir and extract UI string candidates."""
    all_strings = []
    java_files = sorted([
        f for f in scan_dir.rglob("*.java")
        if "build/" not in str(f) and "test/" not in str(f) and "Test" not in str(f)
    ])

    if not java_files:
        print(f"⚠ No Java files found in {scan_dir}")
        return all_strings

    print(f"Scanning {len(java_files)} Java files in {scan_dir}...")
    global_id = 1
    for jf in java_files:
        file_strings, global_id = extract_strings_from_file(jf, global_id)
        all_strings.extend(file_strings)
        if file_strings:
            print(f"  {jf.name}: {len(file_strings)} strings")

    return all_strings


def main():
    repo_root = Path(__file__).resolve().parent.parent.parent
    output_path = repo_root / OUTPUT_FILE

    os.makedirs(output_path.parent, exist_ok=True)

    print(f"=== Stage 1: String Extraction ===")
    print(f"Output: {output_path}")

    all_strings = []
    for src_dir in SOURCE_DIRS:
        scan_path = repo_root / src_dir
        if not scan_path.exists():
            print(f"  [WARN] Directory not found: {scan_path}")
            continue
        strings = extract_all(scan_path)
        all_strings.extend(strings)

    # Deduplicate by (original, context.code)
    seen = set()
    unique = []
    for s in all_strings:
        key = (s["original"], s["context"]["code"])
        if key not in seen:
            seen.add(key)
            unique.append(s)

    print(f"\nTotal extracted: {len(all_strings)}")
    print(f"After dedup:    {len(unique)}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(unique, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"\n✓ Written to {output_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
