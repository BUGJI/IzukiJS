#!/usr/bin/env python3
"""Check relative Markdown links and anchors.

Scans the repository's Markdown files for relative links, verifies that the
target file exists, and when a fragment is present, verifies that a heading
with the corresponding GitHub-style anchor exists in the target file.

External links (http/https/mailto/...) are ignored. Fenced code blocks and
inline code spans are skipped so shell examples are not treated as links.

This intentionally does not fetch the network and does not validate external
URLs; CI runs it alongside scripts/check_versions.py.
"""

from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent

SCAN_FILES = [
    ROOT / "README.md",
    ROOT / "README_EN.md",
    ROOT / "CONTRIBUTING.md",
    ROOT / "AGENTS.md",
    ROOT / "CHANGELOG.md",
]

SKIP_DIRS = {".git", ".gradle", ".idea", ".kotlin", "build", "node_modules"}

FENCE = re.compile(r"^\s*(```|~~~)")
ATX_HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*$")
LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
LINK_DEFINITION = re.compile(r"^\s*\[[^\]]+\]:\s*(\S+)")
INLINE_CODE = re.compile(r"`[^`\n]*`")
TRAILING_TITLE = re.compile(r"""\s+["'].*["']\s*$""")


def markdown_files() -> list[Path]:
    files = [path for path in SCAN_FILES if path.exists()]
    docs = ROOT / "docs"
    if docs.exists():
        files.extend(sorted(docs.rglob("*.md")))
    github = ROOT / ".github"
    if github.exists():
        files.extend(sorted(github.rglob("*.md")))
    result: list[Path] = []
    for path in files:
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path not in result:
            result.append(path)
    return result


def strip_code_blocks(text: str) -> list[tuple[int, str]]:
    """Return (line_number, line) pairs outside fenced code blocks."""
    lines: list[tuple[int, str]] = []
    in_fence = False
    for number, line in enumerate(text.splitlines(), start=1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        lines.append((number, line))
    return lines


def slugify(heading: str) -> str:
    """Approximate github-slugger for the characters used in this repository."""
    text = heading.strip().lower()
    out: list[str] = []
    for char in text:
        category = unicodedata.category(char)
        if char == " ":
            out.append("-")
            continue
        if category[0] in {"C", "P", "S"}:
            continue
        out.append(char)
    return "".join(out)


def heading_anchors(path: Path) -> set[str]:
    anchors: set[str] = set()
    occurrences: dict[str, int] = {}
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return anchors
    for _, line in strip_code_blocks(text):
        match = ATX_HEADING.match(line)
        if not match:
            continue
        base = slugify(match.group(2))
        if base in occurrences:
            occurrences[base] += 1
            anchors.add(f"{base}-{occurrences[base]}")
        else:
            occurrences[base] = 0
            anchors.add(base)
    return anchors


def normalize_target(raw: str) -> str:
    target = raw.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1].strip()
    target = TRAILING_TITLE.sub("", target)
    return unquote(target)


def is_external(target: str) -> bool:
    lowered = target.lower()
    return lowered.startswith(
        ("http://", "https://", "mailto:", "tel:", "data:", "javascript:")
    ) or lowered.startswith("//")


def check_file(path: Path, anchors_cache: dict[Path, set[str]]) -> list[str]:
    errors: list[str] = []
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        return [f"{path.relative_to(ROOT)}: 无法读取：{error}"]

    for number, line in strip_code_blocks(text):
        line = INLINE_CODE.sub("", line)
        targets = [match.group(1) for match in LINK.finditer(line)]
        definition = LINK_DEFINITION.match(line)
        if definition:
            targets.append(definition.group(1))

        for raw in targets:
            target = normalize_target(raw)
            if not target or is_external(target):
                continue

            file_part, _, fragment = target.partition("#")
            file_part = file_part.strip()
            fragment = fragment.strip()

            target_path = path if not file_part else (path.parent / file_part).resolve()
            try:
                target_path.relative_to(ROOT)
            except ValueError:
                errors.append(
                    f"{path.relative_to(ROOT)}:{number}: 链接目标在仓库外：{raw}"
                )
                continue

            if not target_path.exists():
                errors.append(
                    f"{path.relative_to(ROOT)}:{number}: 链接目标不存在：{raw}"
                )
                continue

            if fragment and target_path.suffix.lower() == ".md":
                anchors = anchors_cache.setdefault(
                    target_path, heading_anchors(target_path)
                )
                if fragment not in anchors:
                    errors.append(
                        f"{path.relative_to(ROOT)}:{number}: 锚点不存在：{raw} "
                        f"（目标文件中没有 #{fragment}）"
                    )
    return errors


def main() -> None:
    errors: list[str] = []
    anchors_cache: dict[Path, set[str]] = {}
    files = markdown_files()
    for path in files:
        errors.extend(check_file(path, anchors_cache))

    if errors:
        for error in errors:
            print(f"::error::{error}", file=sys.stderr)
        print(f"文档检查失败：{len(errors)} 个问题", file=sys.stderr)
        sys.exit(1)

    print(f"文档链接 OK：检查了 {len(files)} 个 Markdown 文件")


if __name__ == "__main__":
    main()
