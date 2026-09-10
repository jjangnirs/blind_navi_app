"""Schema drift and data volume anomaly detector for Safe Cross KR ETL pipeline.

Monitors incoming open data CSVs for missing mandatory columns (schema drift)
and catastrophic drops/surges in record count (volume anomaly).
"""

from __future__ import annotations

from dataclasses import dataclass

from safecross_pipeline.parsers.column_mapping import (
    CROSSWALK_COLUMN_MAP,
    TRAFFIC_LIGHT_COLUMN_MAP,
)

# Core canonical fields that MUST be resolved for valid ingestion
MANDATORY_CANONICAL_FIELDS: set[str] = {
    "source_record_key",
    "latitude",
    "longitude",
}


class SchemaDriftError(Exception):
    """Raised when critical columns are missing or column semantics drifted."""

    def __init__(self, message: str, missing_fields: list[str]):
        super().__init__(message)
        self.missing_fields = missing_fields


class VolumeAnomalyError(Exception):
    """Raised when record count deviates abnormally from baseline."""

    def __init__(
        self, message: str, current_count: int, baseline_count: int, drop_ratio: float
    ):
        super().__init__(message)
        self.current_count = current_count
        self.baseline_count = baseline_count
        self.drop_ratio = drop_ratio


@dataclass
class SchemaValidationResult:
    """Result of schema drift evaluation."""

    is_valid: bool
    resolved_canonical_fields: set[str]
    missing_mandatory_fields: list[str]
    unmapped_columns: list[str]


def evaluate_schema_drift(
    headers: list[str],
    source_type: str = "crosswalk",
    mandatory_fields: set[str] | None = None,
) -> SchemaValidationResult:
    """Evaluates CSV column headers against expected canonical field mappings."""
    required = mandatory_fields or MANDATORY_CANONICAL_FIELDS
    column_map = (
        CROSSWALK_COLUMN_MAP if source_type == "crosswalk" else TRAFFIC_LIGHT_COLUMN_MAP
    )

    resolved_canonical: set[str] = set()
    unmapped: list[str] = []

    for h in headers:
        clean_header = h.strip().replace("\ufeff", "")
        if clean_header in column_map:
            resolved_canonical.add(column_map[clean_header])
        else:
            unmapped.append(clean_header)

    missing_mandatory = sorted(required - resolved_canonical)

    return SchemaValidationResult(
        is_valid=len(missing_mandatory) == 0,
        resolved_canonical_fields=resolved_canonical,
        missing_mandatory_fields=missing_mandatory,
        unmapped_columns=unmapped,
    )


def assert_no_schema_drift(
    headers: list[str],
    source_type: str = "crosswalk",
    mandatory_fields: set[str] | None = None,
) -> None:
    """Asserts that headers resolve all mandatory canonical fields, raising SchemaDriftError on failure."""
    res = evaluate_schema_drift(headers, source_type, mandatory_fields)
    if not res.is_valid:
        raise SchemaDriftError(
            f"Schema drift detected in {source_type} data: missing mandatory canonical fields {res.missing_mandatory_fields}",
            missing_fields=res.missing_mandatory_fields,
        )


def assert_no_volume_anomaly(
    current_count: int,
    baseline_count: int,
    max_drop_ratio: float = 0.30,
) -> None:
    """Checks for unexpected data loss between ingestion batches (e.g. >30% drop)."""
    if baseline_count <= 0:
        return  # First ingestion or no baseline available

    if current_count < baseline_count * (1.0 - max_drop_ratio):
        actual_drop_ratio = (baseline_count - current_count) / baseline_count
        raise VolumeAnomalyError(
            f"Data volume anomaly: current count ({current_count}) dropped by "
            f"{actual_drop_ratio:.1%} from baseline ({baseline_count}), exceeding threshold {max_drop_ratio:.1%}",
            current_count=current_count,
            baseline_count=baseline_count,
            drop_ratio=actual_drop_ratio,
        )
