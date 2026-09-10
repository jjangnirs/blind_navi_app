"""Automated Verification Test for Staging Beta Rehearsal Evidence Bundle.

Validates:
1. Existence and integrity of 5 core deliverables:
   - release-manifest.json
   - go-no-go-report.md
   - safety-evidence.md
   - accessibility-test.md
   - rollback-drill.md
2. Release manifest SHA-256 integrity against actual files.
3. 4-Stage ablation monotonic risk reduction.
4. Statistical 0 False-Green 95% CI bound <= 0.030%.
5. Transparent Staging GO / Production NO-GO decision enforcement.
"""

import json
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.release.rehearsal_drill import (
    compute_file_sha256,
    run_full_rehearsal,
)


@pytest.fixture
def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent.parent


def test_01_all_five_deliverables_exist_and_non_empty(repo_root: Path):
    """Deliverable existence and minimum length check."""
    deliverables = [
        repo_root / "release-manifest.json",
        repo_root / "go-no-go-report.md",
        repo_root / "safety-evidence.md",
        repo_root / "accessibility-test.md",
        repo_root / "rollback-drill.md",
    ]

    for f in deliverables:
        assert f.exists(), f"Missing required deliverable: {f.name}"
        assert f.stat().st_size > 500, f"Deliverable {f.name} is unexpectedly small ({f.stat().st_size} bytes)"


def test_02_release_manifest_hashes_match_actual_files(repo_root: Path):
    """Release manifest model and fixture hashes match filesystem."""
    manifest_path = repo_root / "release-manifest.json"
    with open(manifest_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Check on-device models
    ped_model = repo_root / "android-app" / "app" / "src" / "main" / "assets" / "models" / "ped_signal_v1.tflite"
    cw_model = repo_root / "android-app" / "app" / "src" / "main" / "assets" / "models" / "crosswalk_scene_v1.tflite"

    ped_model_entry = next(m for m in data["components"]["onDeviceModels"] if m["modelName"] == "ped_signal")
    cw_model_entry = next(m for m in data["components"]["onDeviceModels"] if m["modelName"] == "crosswalk_scene")

    assert ped_model_entry["sha256"] == compute_file_sha256(ped_model)
    assert cw_model_entry["sha256"] == compute_file_sha256(cw_model)

    # Check data fixtures
    cw_csv = repo_root / "data-pipeline" / "tests" / "fixtures" / "crosswalk_sample_utf8sig.csv"
    tl_csv = repo_root / "data-pipeline" / "tests" / "fixtures" / "traffic_light_sample_cp949.csv"

    cw_data_entry = next(d for d in data["components"]["dataPipeline"]["inputDatasets"] if d["filename"] == "crosswalk_sample_utf8sig.csv")
    tl_data_entry = next(d for d in data["components"]["dataPipeline"]["inputDatasets"] if d["filename"] == "traffic_light_sample_cp949.csv")

    assert cw_data_entry["sha256"] == compute_file_sha256(cw_csv)
    assert tl_data_entry["sha256"] == compute_file_sha256(tl_csv)


def test_03_rehearsal_drill_execution_and_decisions(repo_root: Path):
    """Rehearsal drill runner execution and Go/No-Go split decision."""
    results = run_full_rehearsal(repo_root)

    # 1. Check Staging vs Production decision
    assert results["overall_staging_decision"] == "GO (CONDITIONAL BETA REHEARSAL PASSED)"
    assert "NO-GO" in results["overall_production_decision"]

    # 2. Check 0 False-Green and statistical upper bound
    stats = results["statistical_evidence"]
    assert stats["total_sequences"] == 10000
    assert stats["observed_false_green_events"] == 0
    assert stats["rule_of_three_upper_bound"] <= 0.000300

    # 3. Check 4-stage ablation monotonic decrease
    ablation = results["ablation_results"]
    assert len(ablation) == 4

    mis_rates = [a["target_misselection_rate"] for a in ablation]
    fg_events = [a["false_green_events"] for a in ablation]

    # Monotonically non-increasing
    assert mis_rates[0] >= mis_rates[1] >= mis_rates[2] >= mis_rates[3]
    assert fg_events[0] >= fg_events[1] >= fg_events[2] == fg_events[3] == 0

    # 4. Check all kill switches passed
    ks_drills = results["kill_switch_drills"]
    assert len(ks_drills) == 3
    assert all(d["passed"] for d in ks_drills)
    assert all(d["failover_time_ms"] < 500 for d in ks_drills)


def test_04_go_no_go_report_contains_no_go_gates(repo_root: Path):
    """PRD Chapter 12 unfulfilled gates are explicitly marked as NO-GO."""
    report_file = repo_root / "go-no-go-report.md"
    content = report_file.read_text(encoding="utf-8")

    assert "NO-GO (보류)" in content
    assert "GATE-04" in content
    assert "GATE-06" in content
    assert "GATE-10" in content
    assert "실도로 당사자 미완료" in content
    assert "최종 서명 날인 대기" in content
