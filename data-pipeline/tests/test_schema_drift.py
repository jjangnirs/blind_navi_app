"""Automated tests for ETL schema drift detection and volume anomaly alerts.

Verifies acceptance criteria:
- CSV with dropped/missing critical column fixture fails with SchemaDriftError.
- Drop in row volume exceeds threshold raises VolumeAnomalyError.
- Normal schema passes validation cleanly.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from safecross_pipeline.ingest import ingest_file
from safecross_pipeline.quality.schema_drift_detector import (
    SchemaDriftError,
    VolumeAnomalyError,
    assert_no_schema_drift,
    assert_no_volume_anomaly,
    evaluate_schema_drift,
)


def test_01_missing_coordinate_column_fails_schema_drift(tmp_path: Path):
    """Verifies that dropping latitude column causes SchemaDriftError during ingest."""
    corrupted_csv = tmp_path / "dropped_latitude_crosswalk.csv"
    # '위도' 열이 누락된 비정상 공공데이터 CSV
    corrupted_csv.write_text(
        "횡단보도관리번호,경도,보행자신호등유무,음향신호기설치여부\n"
        "CW-001,126.850000,Y,Y\n"
        "CW-002,126.851000,N,N\n",
        encoding="utf-8",
    )

    with pytest.raises(SchemaDriftError) as exc_info:
        ingest_file(
            source_type="crosswalk",
            file_path=str(corrupted_csv),
            retrieved_at="2026-09-09T10:00:00Z",
            db_engine=None,
        )

    assert "latitude" in exc_info.value.missing_fields
    assert "Schema drift detected" in str(exc_info.value)


def test_02_missing_id_column_fails_schema_drift():
    """Verifies that missing primary key / identifier column raises SchemaDriftError."""
    headers = ["위도", "경도", "음향신호기설치여부"]  # '횡단보도관리번호' 누락

    with pytest.raises(SchemaDriftError) as exc_info:
        assert_no_schema_drift(headers, source_type="crosswalk")

    assert "source_record_key" in exc_info.value.missing_fields


def test_03_valid_standard_headers_pass_schema_drift():
    """Verifies that complete standard headers pass validation without drift."""
    headers = [
        "횡단보도관리번호",
        "위도",
        "경도",
        "보행자신호등유무",
        "음향신호기설치여부",
        "소재지도로명주소",
    ]
    res = evaluate_schema_drift(headers, source_type="crosswalk")

    assert res.is_valid
    assert len(res.missing_mandatory_fields) == 0
    assert "source_record_key" in res.resolved_canonical_fields
    assert "latitude" in res.resolved_canonical_fields
    assert "longitude" in res.resolved_canonical_fields


def test_04_volume_anomaly_drop_raises_error():
    """Verifies that a >30% drop in records raises VolumeAnomalyError."""
    baseline = 1000
    current = 650  # 35% drop

    with pytest.raises(VolumeAnomalyError) as exc_info:
        assert_no_volume_anomaly(
            current_count=current, baseline_count=baseline, max_drop_ratio=0.30
        )

    assert exc_info.value.drop_ratio == pytest.approx(0.35, abs=0.01)
    assert "dropped by 35.0%" in str(exc_info.value)


def test_05_normal_volume_change_passes():
    """Verifies that normal fluctuations within threshold pass without error."""
    assert_no_volume_anomaly(
        current_count=950, baseline_count=1000, max_drop_ratio=0.30
    )
    assert_no_volume_anomaly(
        current_count=1100, baseline_count=1000, max_drop_ratio=0.30
    )
