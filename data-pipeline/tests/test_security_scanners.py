"""Automated tests for security and safety phrase scanners.

Verifies acceptance criteria:
1. Intentionally injected fake secret fixture is detected by the scanner.
2. Injected prohibited safety phrase fixture is detected by the scanner.
3. Secret values are properly masked in logs to avoid CI leakage.
"""

from __future__ import annotations

import sys
from pathlib import Path

# Ensure workspace root is in python path
ROOT_DIR = Path(__file__).resolve().parent.parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from scripts.security.safety_phrase_scanner import scan_file_for_phrases, scan_paths
from scripts.security.sbom_generator import generate_sbom
from scripts.security.secret_scanner import mask_secret, scan_file


def test_01_fake_secret_fixture_detected(tmp_path: Path):
    """Verifies that an injected fake secret fixture is strictly detected."""
    secret_file = tmp_path / "fake_credentials.env"
    secret_file.write_text(
        "TMAP_APP_KEY=tmap_live_99998888777766665555444433332222\n"
        "AWS_KEY=AKIAIOSFODNN7EXAMPLE\n"
        "TEST_MARKER=FAKESECRET_9999888877776666\n",
        encoding="utf-8",
    )

    findings = scan_file(secret_file)
    assert len(findings) >= 2, (
        f"Expected at least 2 secrets detected, got {len(findings)}"
    )

    rule_names = {f.rule_name for f in findings}
    assert (
        "AWS Access Key" in rule_names
        or "TMAP API Key Pattern" in rule_names
        or "Test / Fake Secret Fixture Marker" in rule_names
    )

    # Verify secret value is masked in findings
    for finding in findings:
        assert "REDACTED" in finding.masked_line or "***" in finding.masked_line
        assert "AKIAIOSFODNN7EXAMPLE" not in finding.masked_line


def test_02_clean_file_produces_zero_secret_findings(tmp_path: Path):
    """Verifies that clean code produces zero secret findings."""
    clean_file = tmp_path / "clean_config.py"
    clean_file.write_text(
        "import os\n"
        "DATABASE_URL = os.environ.get('DATABASE_URL')\n"
        "APP_VERSION = '0.2.0'\n",
        encoding="utf-8",
    )

    findings = scan_file(clean_file)
    assert len(findings) == 0


def test_03_prohibited_safety_phrase_fixture_detected(tmp_path: Path):
    """Verifies that prohibited safety phrase fixtures in string resources are detected."""
    bad_strings_file = tmp_path / "strings.xml"
    bad_strings_file.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <string name="crossing_safe">이 길은 안전합니다</string>\n'
        '    <string name="cross_now">지금 건너세요</string>\n'
        '    <string name="green_certain">100% 녹색입니다</string>\n'
        '</resources>\n',
        encoding="utf-8",
    )

    findings = scan_file_for_phrases(bad_strings_file)
    assert len(findings) == 3

    detected_phrases = {f.phrase for f in findings}
    assert "안전합니다" in detected_phrases
    assert "지금 건너세요" in detected_phrases
    assert "100% 녹색" in detected_phrases


def test_04_production_code_clean_from_prohibited_phrases():
    """Verifies that actual repository production code contains 0 prohibited safety claims."""
    repo_root = Path(__file__).resolve().parent.parent.parent
    findings = scan_paths(repo_root)
    assert len(findings) == 0, (
        f"Found prohibited safety phrases in production: {findings}"
    )


def test_05_secret_masking_function():
    """Verifies that mask_secret properly conceals sensitive tokens."""
    line = "TMAP_APP_KEY='sk_live_1234567890abcdef1234'"
    start = line.index("sk_live_1234567890abcdef1234")
    end = start + len("sk_live_1234567890abcdef1234")
    masked = mask_secret(line, (start, end))

    assert "sk_***REDACTED***234" in masked
    assert "1234567890abcdef" not in masked


def test_06_sbom_generator_generates_components():
    """Verifies that SBOM generator collects dependencies and produces valid schema."""
    repo_root = Path(__file__).resolve().parent.parent.parent
    sbom = generate_sbom(repo_root)

    assert "components" in sbom
    assert sbom["totalComponents"] > 0
    assert sbom["bomFormat"] == "CycloneDX-SafeCross"
