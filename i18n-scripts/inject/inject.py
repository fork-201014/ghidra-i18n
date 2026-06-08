#!/usr/bin/env python3
"""
Stage 3: Source Injection
Replaces hardcoded English UI strings in Java source files with their
Chinese translations. Uses line-number-based precise replacement with
safety validation.

Input:  i18n-scripts/translate/output/translated.json
Output: Modified Java source files in ghidra/ submodule
"""

import json
import sys
from pathlib import Path
from collections import defaultdict


# =============================================================================
# Configuration
# =============================================================================

INPUT_FILE = Path("i18n-scripts/translate/output/translated.json")

# Translations to skip (non-UI, errors, etc.)
SKIP_TYPES = {"skipped"}


def load_translations(repo_root: Path) -> list[dict]:
    """Load translated strings from JSON."""
    input_path = repo_root / INPUT_FILE
    if not input_path.exists():
        print(f"✗ Input file not found: {input_path}", file=sys.stderr)
        return []
    return json.loads(input_path.read_text(encoding="utf-8"))


def inject_translations(repo_root: Path, translations: list[dict]) -> dict:
    """
    Inject translations into source files.
    Returns stats: {files_modified, strings_injected, errors}.
    """
    stats = {
        "files_modified": 0,
        "strings_injected": 0,
        "skipped": 0,
        "errors": 0,
    }

    # Group translations by source file
    file_groups: dict[str, list[dict]] = defaultdict(list)
    for t in translations:
        if t.get("type") in SKIP_TYPES or not t.get("translated"):
            stats["skipped"] += 1
            continue
        file_groups[t["source_file"]].append(t)

    for filepath, entries in file_groups.items():
        source_path = repo_root / filepath
        if not source_path.exists():
            print(f"  [WARN] Source file not found: {filepath}")
            stats["errors"] += 1
            continue

        try:
            lines = source_path.read_text(encoding="utf-8").split("\n")
            modified = False

            # Sort by line number (descending) to avoid line-shift issues
            # when replacing multi-line content. For same-line replacements,
            # process from right to left.
            entries_sorted = sorted(entries, key=lambda e: e.get("source_line", 0), reverse=True)

            for entry in entries_sorted:
                line_num = entry.get("source_line", 0)
                original = entry.get("original", "")
                translated = entry.get("translated", "")

                if line_num < 1 or line_num > len(lines):
                    print(f"  [WARN] Invalid line {line_num} in {filepath}")
                    stats["errors"] += 1
                    continue

                idx = line_num - 1
                line = lines[idx]

                # Safety check: verify the original string is in this line
                if original not in line:
                    print(f"  [WARN] String '{original}' not found on line {line_num} "
                          f"of {filepath} (可能已被之前的替换修改或同一行有多个实例)")
                    # Try to find it in nearby lines (±2 lines)
                    found = False
                    for offset in range(-2, 3):
                        check_idx = idx + offset
                        if 0 <= check_idx < len(lines) and original in lines[check_idx]:
                            print(f"    Found at line {check_idx + 1} instead")
                            idx = check_idx
                            line = lines[idx]
                            found = True
                            break
                    if not found:
                        stats["errors"] += 1
                        continue

                # Safe replacement: replace as complete string literal with quotes.
                # Handle both escaped and unescaped versions of the original string
                # (AST parsing unescapes \" to ", so we need to check both forms).
                replaced = False

                # Try exact quote-delimited match first: "original"
                quoted_original = '"' + original + '"'
                quoted_translated = '"' + translated.replace('"', '\\"') + '"'

                if quoted_original in line:
                    new_line = line.replace(quoted_original, quoted_translated, 1)
                    replaced = True
                else:
                    # Try with common Java escaping: backslash-escape quotes in original
                    escaped_original = original.replace('"', '\\"')
                    quoted_escaped_orig = '"' + escaped_original + '"'
                    if quoted_escaped_orig in line:
                        new_line = line.replace(quoted_escaped_orig, quoted_translated, 1)
                        replaced = True
                    # Also try un-escaped match (the translated text has no embedded quotes typically)
                    elif translated.find('"') == -1 and original in line:
                        # Original appears unquoted (concatenation fragment), skip if suspicious
                        if ' + ' in line and len(original) < 5:
                            print(f"  [SKIP] '{original}' looks like concatenation fragment in {filepath}:{line_num}")
                            stats["skipped"] += 1
                            continue
                        new_line = line.replace(original, translated, 1)
                        replaced = True

                if not replaced:
                    print(f"  [WARN] Could not find safe replacement for '{original}' in {filepath}:{line_num}")
                    stats["errors"] += 1
                    continue
                if new_line != line:
                    lines[idx] = new_line
                    modified = True
                    stats["strings_injected"] += 1
                    print(f"  {filepath}:{line_num}: \"{original}\" → \"{translated}\"")
                else:
                    print(f"  [WARN] Replacement had no effect for '{original}' in {filepath}:{line_num}")

            if modified:
                source_path.write_text("\n".join(lines), encoding="utf-8")
                stats["files_modified"] += 1

        except Exception as e:
            print(f"  [ERROR] Failed to process {filepath}: {e}")
            stats["errors"] += 1

    return stats


def main():
    repo_root = Path(__file__).resolve().parent.parent.parent

    print(f"=== Stage 3: Source Injection ===")

    translations = load_translations(repo_root)
    if not translations:
        print("No translations to inject. Nothing to do.")
        return 0

    print(f"Loaded {len(translations)} translations from {INPUT_FILE}")

    stats = inject_translations(repo_root, translations)

    print(f"\nInjection complete:")
    print(f"  Files modified:   {stats['files_modified']}")
    print(f"  Strings injected: {stats['strings_injected']}")
    print(f"  Skipped (non-UI): {stats['skipped']}")
    print(f"  Errors:           {stats['errors']}")

    if stats["errors"] > 0:
        print("\n⚠ Some errors occurred. Review the warnings above.", file=sys.stderr)

    return 0 if stats["errors"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
