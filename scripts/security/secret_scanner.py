"""High-precision secret and credential scanner for Safe Cross KR.

Scans codebase, configuration files, and assets for hardcoded secrets, API keys,
private keys, and tokens. Masks secret values in logs to prevent secret leakage in CI output.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

# High-confidence secret detection regex patterns
SECRET_PATTERNS: dict[str, re.Pattern[str]] = {
    "AWS Access Key": re.compile(
        r"(?:A3T[A-Z0-9]|AKIA|AGPA|AIDA|AROA|AIPA|ANPA|ANVA|ASIA)[A-Z0-9]{16}"
    ),
    "Google API / Secret Key": re.compile(r"AIza[0-9A-Za-z\\-_]{35}"),
    "TMAP API Key Pattern": re.compile(
        r"(?i)(?:tmap[_-]?app[_-]?key|tmap[_-]?key)\s*[:=]\s*['\"]([0-9a-zA-Z]{20,45})['\"]"
    ),
    "Generic Private Key": re.compile(
        r"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"
    ),
    "Generic Secret / Token Assignment": re.compile(
        r"(?i)(?:secret|token|password|api[_-]?key|client[_-]?secret)\s*[:=]\s*['\"]([a-zA-Z0-9_\-.~!@#$%^&*]{16,})['\"]"
    ),
    "JSON Web Token (JWT)": re.compile(
        r"eyJ[a-zA-Z0-9_-]{10,}\.eyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}"
    ),
    "Test / Fake Secret Fixture Marker": re.compile(r"FAKESECRET_[A-Z0-9]{16,}"),
}

# Directories and file patterns to exclude
DEFAULT_IGNORE_DIRS = {
    ".git",
    ".venv",
    "venv",
    "node_modules",
    "__pycache__",
    ".pytest_cache",
    ".ruff_cache",
    ".gradle",
    "build",
    "dist",
    "tests",
}

DEFAULT_IGNORE_FILES = {
    ".gitignore",
    "poetry.lock",
    "package-lock.json",
    "Cargo.lock",
}


@dataclass
class SecretFinding:
    """Represents a detected secret finding."""

    file_path: str
    line_number: int
    rule_name: str
    masked_line: str


def mask_secret(text: str, match_span: tuple[int, int]) -> str:
    """Masks the detected secret within a line so CI logs remain leak-free."""
    start, end = match_span
    secret_val = text[start:end]
    if len(secret_val) <= 6:
        masked = "***"
    else:
        masked = secret_val[:3] + "***REDACTED***" + secret_val[-3:]
    return text[:start] + masked + text[end:]


def scan_file(file_path: Path) -> list[SecretFinding]:
    """Scans a single file for secret patterns."""
    findings: list[SecretFinding] = []
    try:
        with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
            for line_idx, line in enumerate(f, start=1):
                clean_line = line.strip()
                if not clean_line or clean_line.startswith(("#", "//")):
                    # Check comments too, but skip empty lines
                    pass

                for rule_name, pattern in SECRET_PATTERNS.items():
                    match = pattern.search(line)
                    if match:
                        masked_line = mask_secret(line.rstrip("\r\n"), match.span())
                        findings.append(
                            SecretFinding(
                                file_path=str(file_path),
                                line_number=line_idx,
                                rule_name=rule_name,
                                masked_line=masked_line,
                            )
                        )
    except (OSError, UnicodeDecodeError):
        # Ignore unreadable files
        pass
    return findings


def scan_directory(
    target_dir: Path,
    ignore_dirs: set[str] | None = None,
    ignore_files: set[str] | None = None,
) -> list[SecretFinding]:
    """Recursively scans directory for secrets."""
    dirs_to_ignore = ignore_dirs or DEFAULT_IGNORE_DIRS
    files_to_ignore = ignore_files or DEFAULT_IGNORE_FILES

    all_findings: list[SecretFinding] = []

    for path in target_dir.rglob("*"):
        if path.is_dir():
            continue

        # Skip ignored directory components
        if any(ignored in path.parts for ignored in dirs_to_ignore):
            continue

        if path.name in files_to_ignore:
            continue

        findings = scan_file(path)
        all_findings.extend(findings)

    return all_findings


def main() -> int:
    parser = argparse.ArgumentParser(description="Safe Cross KR Secret Scanner")
    parser.add_argument("--path", type=str, default=".", help="Root directory to scan")
    parser.add_argument(
        "--quiet", action="store_true", help="Suppress non-error output"
    )
    args = parser.parse_args()

    root_path = Path(args.path).resolve()
    if not args.quiet:
        print(f"[SECRET-SCAN] Scanning directory: {root_path}")

    findings = scan_directory(root_path)

    if findings:
        print(
            f"\n[SECRET-SCAN FAIL] Detected {len(findings)} potential secret(s):",
            file=sys.stderr,
        )
        for f in findings:
            print(
                f"  - {f.file_path}:{f.line_number} [{f.rule_name}] -> {f.masked_line}",
                file=sys.stderr,
            )
        return 1

    if not args.quiet:
        print("[SECRET-SCAN PASS] No hardcoded secrets detected.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
