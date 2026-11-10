#!/usr/bin/env python3
"""Check that version numbers stay in sync.

Single sources of truth:
- Kotlin version: [versions].kotlin in gradle/libs.versions.toml
- App version: izuki.versionCode / izuki.versionName in gradle.properties

READMEs are checked only for the Kotlin version shown in their tech-stack section.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parent.parent


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def fail(message: str) -> NoReturn:
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    catalog = read("gradle/libs.versions.toml")
    match = re.search(r'(?m)^kotlin\s*=\s*"([^"]+)"\s*$', catalog)
    if not match:
        fail("missing [versions].kotlin in libs.versions.toml")
    assert match is not None
    kotlin_version = match.group(1)

    readmes = {
        "README.md": [
            re.compile(r"badge/Kotlin-([0-9.]+)-purple\.svg"),
            re.compile(r"Kotlin ([0-9.]+) ·"),
        ],
        "README_EN.md": [
            re.compile(r"badge/Kotlin-([0-9.]+)-purple\.svg"),
            re.compile(r"Kotlin ([0-9.]+) ·"),
        ],
    }
    for name, patterns in readmes.items():
        content = read(name)
        for pattern in patterns:
            found = pattern.search(content)
            if not found:
                fail(f"missing Kotlin version display in {name}")
            assert found is not None
            if found.group(1) != kotlin_version:
                fail(
                    f"{name} shows Kotlin {found.group(1)}, "
                    f"but libs.versions.toml has {kotlin_version}",
                )

    properties = read("gradle.properties")
    version_name = re.search(r"(?m)^izuki\.versionName=(.+)$", properties)
    version_code = re.search(r"(?m)^izuki\.versionCode=(\d+)$", properties)
    if not version_name or not version_code:
        fail("gradle.properties is missing izuki.versionName or izuki.versionCode")
    assert version_name is not None and version_code is not None

    build = read("app/build.gradle.kts")
    if 'providers.gradleProperty("izuki.versionName")' not in build:
        fail(
            "app/build.gradle.kts does not read izuki.versionName from gradle.properties"
        )
    if 'providers.gradleProperty("izuki.versionCode")' not in build:
        fail(
            "app/build.gradle.kts does not read izuki.versionCode from gradle.properties"
        )
    if re.search(r"(?m)^\s*versionCode\s*=\s*\d+", build):
        fail("app/build.gradle.kts still hardcodes versionCode")
    if re.search(r'(?m)^\s*versionName\s*=\s*"[^"]+"', build):
        fail("app/build.gradle.kts still hardcodes versionName")

    print(
        f"Versions OK: Kotlin {kotlin_version}, app {version_name.group(1).strip()} "
        f"({version_code.group(1)})",
    )


if __name__ == "__main__":
    main()
