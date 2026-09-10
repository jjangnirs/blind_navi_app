import json
from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass
class IngestionReport:
    source_type: str
    file_path: str
    file_sha256: str
    encoding: str
    total_rows: int = 0
    valid_count: int = 0
    quarantined_count: int = 0
    duplicate_count: int = 0
    missing_fields_count: int = 0
    quarantine_details: list[dict[str, Any]] = field(default_factory=list)

    def add_quarantine(
        self,
        row_index: int,
        record_key: str,
        reason: str,
        details: str,
        raw_row: dict[str, Any],
    ) -> None:
        self.quarantined_count += 1
        self.quarantine_details.append(
            {
                "row_index": row_index,
                "record_key": record_key,
                "reason": reason,
                "details": details,
                "raw_row": raw_row,
            }
        )

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def to_json(self, indent: int = 2) -> str:
        return json.dumps(
            self.to_dict(), ensure_ascii=False, indent=indent, default=str
        )
