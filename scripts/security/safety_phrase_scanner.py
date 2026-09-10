"""Safety phrase scanner for Safe Cross KR.

Verifies that production code, string resources, and backend localization files
do NOT contain prohibited safety claims (e.g. "안전합니다", "지금 건너세요", "차가 없습니다", "100% 녹색",
"장애인 안전 경로", "안심 경로").
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

# Prohibited Korean safety claims and overpromising terms
PROHIBITED_PHRASES: list[str] = [
    "안전합니다",
    "지금 건너세요",
    "차가 없습니다",
    "100% 녹색",
    "장애인 안전 경로",
    "안심 경로",
    "완벽한 안전",
    "사고 없음",
    "건너도 좋습니다",
    "완전 안전",
]

# Production code & resource directories that must be strictly audited
DEFAULT_SCAN_DIRS: list[str] = [
    "android-app/app/src/main",
    "backend/app",
    "data-pipeline/safecross_pipeline",
    "ml",
]

# Excluded paths (e.g., test fixtures that intentionally test the scanner, test assertions)
EXCLUDED_PATTERNS: list[str] = [
    "test",
    "tests",
    "fixtures",
    "SafetyProhibitedPhrasesTest",
    "safety_phrase_scanner",
    "test_security_scanners",
]


@dataclass
class PhraseFinding:
    """Represents a prohibited phrase finding."""

    file_path: str
    line_number: int
    phrase: str
    snippet: str


def scan_file_for_phrases(
    file_path: Path, phrases: list[str] | None = None
) -> list[PhraseFinding]:
    """Scans a single file for prohibited phrases."""
    target_phrases = phrases or PROHIBITED_PHRASES
    findings: list[PhraseFinding] = []

    try:
        with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
            for line_idx, line in enumerate(f, start=1):
                clean_line = line.strip()
                if not clean_line:
                    continue

                for phrase in target_phrases:
                    if phrase in clean_line:
                        findings.append(
                            PhraseFinding(
                                file_path=str(file_path),
                                line_number=line_idx,
                                phrase=phrase,
                                snippet=clean_line[:120],
                            )
                        )
    except (OSError, UnicodeDecodeError):
        pass

    return findings


def scan_paths(
    base_dir: Path,
    relative_dirs: list[str] | None = None,
    excluded_patterns: list[str] | None = None,
) -> list[PhraseFinding]:
    """Scans designated directories for prohibited safety phrases."""
    scan_targets = relative_dirs or DEFAULT_SCAN_DIRS
    exclusions = (
        excluded_patterns if excluded_patterns is not None else EXCLUDED_PATTERNS
    )
    all_findings: list[PhraseFinding] = []

    for rel_dir in scan_targets:
        target_path = base_dir / rel_dir
        if not target_path.exists():
            continue

        if target_path.is_file():
            if not any(excl in target_path.name for excl in exclusions):
                all_findings.extend(scan_file_for_phrases(target_path))
            continue

        for path in target_path.rglob("*"):
            if path.is_dir():
                continue

            # Skip excluded files or directories
            if any(excl in str(path) for excl in exclusions):
                continue

            # Target only code, XML, JSON, YAML files
            if path.suffix.lower() not in {
                ".kt",
                ".java",
                ".xml",
                ".py",
                ".json",
                ".yaml",
                ".yml",
                ".sql",
            }:
                continue

            findings = scan_file_for_phrases(path)
            all_findings.extend(findings)

    return all_findings


def main() -> int:
    parser = argparse.ArgumentParser(description="Safe Cross KR Safety Phrase Scanner")
    parser.add_argument(
        "--root", type=str, default=".", help="Workspace root directory"
    )
    parser.add_argument(
        "--quiet", action="store_true", help="Suppress non-error output"
    )
    args = parser.parse_args()

    root_path = Path(args.root).resolve()
    if not args.quiet:
        print(f"[SAFETY-SCAN] Scanning production resources under: {root_path}")

    findings = scan_paths(root_path)

    if findings:
        print(
            f"\n[SAFETY-SCAN FAIL] Detected {len(findings)} prohibited safety phrase(s):",
            file=sys.stderr,
        )
        for f in findings:
            print(
                f"  - {f.file_path}:{f.line_number} [PHRASE: '{f.phrase}'] -> {f.snippet}",
                file=sys.stderr,
            )
        return 1

    if not args.quiet:
        print(
            "[SAFETY-SCAN PASS] No prohibited safety claims found in production resources."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
