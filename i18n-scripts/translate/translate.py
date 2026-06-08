#!/usr/bin/env python3
"""
Stage 2: AI Translation
Sends extracted strings to DeepSeek API for UI classification and translation.
Uses a translation memory database to avoid re-translating unchanged strings.

Input:  i18n-scripts/extract/output/strings.json
Output: i18n-scripts/translate/output/translated.json
Side:   i18n-scripts/translate/output/translations_db.json (translation memory)
"""

import json
import os
import sys
import time
from pathlib import Path

from openai import OpenAI


# =============================================================================
# Configuration
# =============================================================================

INPUT_FILE = Path("i18n-scripts/extract/output/strings.json")
OUTPUT_DIR = Path("i18n-scripts/translate/output")
OUTPUT_FILE = OUTPUT_DIR / "translated.json"
MEMORY_FILE = OUTPUT_DIR / "translations_db.json"

# DeepSeek API settings
DEEPSEEK_BASE_URL = "https://api.deepseek.com"
DEEPSEEK_MODEL = "deepseek-v4-flash"

# Batch size for API calls
BATCH_SIZE = 30

# Rate limiting
DELAY_BETWEEN_BATCHES = 1.0  # seconds

# Translation prompt
SYSTEM_PROMPT = """你是一名专业的软件本地化翻译专家。
请将以下 Ghidra 逆向工程工具的 Java Swing UI 字符串翻译为简体中文。

翻译规则：
1. 保持技术术语一致性（如 "disassemble" → "反汇编"，"listing" → "列表视图"）
2. 保留所有格式化占位符（如 %s, %d, {0}, {1}）
3. 保留所有 HTML 标签（如 <html>, <b>, <br>, <font>）
4. 保留快捷键标记（如 &File → 文件(&F), _Open）
5. 对于日志/调试/内部标识符等非 UI 字符串，返回 {"skip": true}
6. 按钮文字保持简洁（中文通常 2-4 字）
7. 菜单项保留层级语义
8. 名称类文本（品牌名、协议名等）保留原文不翻译

请严格按照以下 JSON 格式返回结果（不要包含 markdown 代码块标记）：
[
  {"id": "docking_widgets_0001", "translated": "取消", "type": "ui_button"},
  {"id": "docking_widgets_0002", "skip": true, "reason": "debug_log"}
]
"""


def load_input(repo_root: Path) -> list[dict]:
    """Load extracted strings from JSON."""
    input_path = repo_root / INPUT_FILE
    if not input_path.exists():
        print(f"✗ Input file not found: {input_path}", file=sys.stderr)
        return []
    return json.loads(input_path.read_text(encoding="utf-8"))


def load_memory(repo_root: Path) -> dict[str, dict]:
    """Load translation memory database."""
    mem_path = repo_root / MEMORY_FILE
    if mem_path.exists():
        return json.loads(mem_path.read_text(encoding="utf-8"))
    return {}


def save_memory(repo_root: Path, memory: dict[str, dict]):
    """Save translation memory database."""
    mem_path = repo_root / MEMORY_FILE
    mem_path.parent.mkdir(parents=True, exist_ok=True)
    mem_path.write_text(json.dumps(memory, ensure_ascii=False, indent=2), encoding="utf-8")


def filter_new_strings(strings: list[dict], memory: dict[str, dict]) -> tuple[list[dict], list[dict]]:
    """
    Separate strings into new (need translation) and cached (from memory).
    Memory key: original string text.
    Returns (new_strings, cached_results).
    """
    new_strings = []
    cached_results = []

    for s in strings:
        key = s["original"]
        if key in memory:
            cached_results.append({
                "id": s["id"],
                "original": s["original"],
                "translated": memory[key],
                "type": "cached",
                "source_file": s["file"],
                "source_line": s["line"],
            })
        else:
            new_strings.append(s)

    return new_strings, cached_results


def translate_batch(client: OpenAI, batch: list[dict]) -> list[dict]:
    """Send a batch of strings to DeepSeek for translation."""
    # Build user prompt with context
    items = []
    for s in batch:
        code = s["context"]["code"]
        items.append(f'ID: {s["id"]}\n原文: {s["original"]}\n代码: {code}')

    user_prompt = "翻译以下 Java UI 字符串，附带源代码上下文：\n\n" + "\n\n".join(items)

    try:
        response = client.chat.completions.create(
            model=DEEPSEEK_MODEL,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.1,
            max_tokens=4096,
        )

        content = response.choices[0].message.content.strip()
        # Remove markdown code fence if present
        if content.startswith("```"):
            content = content.split("\n", 1)[1]
            if content.endswith("```"):
                content = content[:-3]
        return json.loads(content)

    except Exception as e:
        print(f"  [ERROR] API call failed: {e}", file=sys.stderr)
        return []


def process_translations(
    new_strings: list[dict],
    cached_results: list[dict],
    memory: dict[str, dict],
    client: OpenAI,
) -> list[dict]:
    """Process all translations: cached + API-translated."""
    results = list(cached_results)

    total = len(new_strings)
    for i in range(0, total, BATCH_SIZE):
        batch = new_strings[i : i + BATCH_SIZE]
        print(f"  Batch {i // BATCH_SIZE + 1}/{(total + BATCH_SIZE - 1) // BATCH_SIZE} "
              f"({len(batch)} strings)...")

        raw_results = translate_batch(client, batch)

        for item in raw_results:
            sid = item.get("id", "")
            # Find original string
            original = next((s["original"] for s in batch if s["id"] == sid), "")

            if item.get("skip"):
                # Skip non-UI strings
                results.append({
                    "id": sid,
                    "original": original,
                    "translated": None,
                    "type": "skipped",
                    "reason": item.get("reason", ""),
                    "source_file": next((s["file"] for s in batch if s["id"] == sid), ""),
                    "source_line": next((s["line"] for s in batch if s["id"] == sid), 0),
                })
            else:
                translated = item.get("translated", "")
                item_type = item.get("type", "machine")
                results.append({
                    "id": sid,
                    "original": original,
                    "translated": translated,
                    "type": item_type,
                    "source_file": next((s["file"] for s in batch if s["id"] == sid), ""),
                    "source_line": next((s["line"] for s in batch if s["id"] == sid), 0),
                })
                # Update memory
                if original and translated:
                    memory[original] = translated

        time.sleep(DELAY_BETWEEN_BATCHES)

    return results


def main():
    repo_root = Path(__file__).resolve().parent.parent.parent

    print(f"=== Stage 2: AI Translation ===")

    # Check API key
    api_key = os.environ.get("DEEPSEEK_API_KEY")
    if not api_key:
        print("✗ DEEPSEEK_API_KEY environment variable not set!", file=sys.stderr)
        return 1

    client = OpenAI(api_key=api_key, base_url=DEEPSEEK_BASE_URL)

    # Load input
    strings = load_input(repo_root)
    if not strings:
        print("No strings to translate. Nothing to do.")
        return 0

    print(f"Loaded {len(strings)} strings from {INPUT_FILE}")

    # Load memory
    memory = load_memory(repo_root)
    print(f"Translation memory: {len(memory)} entries")

    # Filter already-translated
    new_strings, cached_results = filter_new_strings(strings, memory)
    print(f"New strings to translate: {len(new_strings)}")
    print(f"Cached from memory:      {len(cached_results)}")

    if not new_strings:
        print("All strings already in translation memory!")

    # Translate
    results = process_translations(new_strings, cached_results, memory, client)

    # Save results
    output_path = repo_root / OUTPUT_FILE
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"\n✓ Written {len(results)} translations to {OUTPUT_FILE}")

    # Save memory
    save_memory(repo_root, memory)
    print(f"✓ Translation memory updated ({len(memory)} entries)")

    # Summary
    translated_count = sum(1 for r in results if r.get("translated"))
    skipped_count = sum(1 for r in results if r.get("type") == "skipped")
    print(f"\nSummary: {translated_count} translated, {skipped_count} skipped, "
          f"{len(cached_results)} from cache")

    return 0


if __name__ == "__main__":
    sys.exit(main())
