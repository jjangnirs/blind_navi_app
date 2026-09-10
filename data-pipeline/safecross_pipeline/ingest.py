import csv
import json
import os
import uuid
from datetime import datetime, timezone
from typing import Any

from safecross_pipeline.parsers.boolean_parser import (
    InvalidBooleanError,
    parse_tristate_boolean,
)
from safecross_pipeline.parsers.column_mapping import (
    CROSSWALK_COLUMN_MAP,
    TRAFFIC_LIGHT_COLUMN_MAP,
)
from safecross_pipeline.parsers.encoding import detect_encoding
from safecross_pipeline.parsers.hasher import compute_file_sha256
from safecross_pipeline.quality.administrative_alias import (
    resolve_administrative_division,
)
from safecross_pipeline.quality.report import IngestionReport
from safecross_pipeline.quality.schema_drift_detector import assert_no_schema_drift
from safecross_pipeline.quality.validator import (
    ValidationError,
    validate_coordinates,
    validate_date,
    validate_source_record_key,
)


def ingest_file(
    source_type: str,
    file_path: str,
    retrieved_at: str,
    db_engine: Any | None = None,
) -> IngestionReport:
    """
    공공데이터 CSV 파일을 raw -> staging -> canonical 로 안전하게 인제스트합니다.
    - 원본 파일 해시 보존 검증 (처리 전후 해시 일치)
    - 삼항 논리(True/False/None) 적용 및 공란의 null 보존
    - 좌표 범위 및 뒤바뀜 검증/격리
    - 멱등적(idempotent) DB 적재
    """
    if source_type not in ("crosswalk", "traffic-light"):
        raise ValueError(
            f"지원하지 않는 source_type입니다: {source_type} (crosswalk 또는 traffic-light 필요)"
        )

    if not os.path.exists(file_path):
        raise FileNotFoundError(f"파일을 찾을 수 없습니다: {file_path}")

    # 1. 처리 전 원본 파일 SHA-256 계산
    initial_sha256 = compute_file_sha256(file_path)

    # 2. 인코딩 감지
    encoding = detect_encoding(file_path)

    report = IngestionReport(
        source_type=source_type,
        file_path=os.path.abspath(file_path),
        file_sha256=initial_sha256,
        encoding=encoding,
    )

    column_map = (
        CROSSWALK_COLUMN_MAP if source_type == "crosswalk" else TRAFFIC_LIGHT_COLUMN_MAP
    )

    canonical_records = []
    seen_keys = set()

    # 3. CSV 파싱 및 행별 검증
    with open(file_path, "r", encoding=encoding, errors="replace") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames:
            assert_no_schema_drift(list(reader.fieldnames), source_type=source_type)
        for row_idx, row in enumerate(reader, start=1):
            report.total_rows += 1
            raw_record = dict(row)

            # Mapping
            mapped: dict[str, Any] = {}
            for col_name, val in row.items():
                if col_name in column_map:
                    mapped[column_map[col_name]] = val.strip() if val else ""

            # Validate key
            try:
                record_key = validate_source_record_key(mapped.get("source_record_key"))
            except ValidationError as e:
                report.add_quarantine(row_idx, "", e.reason, e.details, raw_record)
                continue

            # Duplicate check within the same file
            if record_key in seen_keys:
                report.duplicate_count += 1
                report.add_quarantine(
                    row_idx,
                    record_key,
                    "DUPLICATE_KEY_IN_FILE",
                    f"동일 파일 내 중복된 관리번호: {record_key}",
                    raw_record,
                )
                continue
            seen_keys.add(record_key)

            # Validate coordinates
            try:
                lat, lon = validate_coordinates(
                    mapped.get("latitude"), mapped.get("longitude")
                )
            except ValidationError as e:
                report.add_quarantine(
                    row_idx, record_key, e.reason, e.details, raw_record
                )
                continue

            # Validate date
            try:
                ref_date = validate_date(mapped.get("data_reference_date"))
            except ValidationError as e:
                report.add_quarantine(
                    row_idx, record_key, e.reason, e.details, raw_record
                )
                continue

            # Parse boolean fields strictly (공란은 None으로 보존, 알 수 없는 값은 격리)
            boolean_parse_failed = False
            parsed_booleans = {}
            bool_fields = (
                [
                    "pedestrian_signal",
                    "acoustic_signal",
                    "tactile_paving",
                    "curb_cut",
                    "traffic_island",
                    "button_signal",
                ]
                if source_type == "crosswalk"
                else ["button_signal", "countdown_timer", "acoustic_signal"]
            )

            for bf in bool_fields:
                if bf in mapped:
                    try:
                        parsed_booleans[bf] = parse_tristate_boolean(mapped.get(bf))
                    except InvalidBooleanError as e:
                        report.add_quarantine(
                            row_idx,
                            record_key,
                            "INVALID_BOOLEAN_VALUE",
                            str(e),
                            raw_record,
                        )
                        boolean_parse_failed = True
                        break

            if boolean_parse_failed:
                continue

            # Resolve administrative division (도로명주소 -> 지번주소 -> 좌표 추정)
            admin_div = resolve_administrative_division(
                road_address=mapped.get("road_name"),
                lot_address=mapped.get("lot_address"),
                lat=lat,
                lon=lon,
            )

            # Canonical record assembled
            canonical = {
                "source_record_key": record_key,
                "latitude": lat,
                "longitude": lon,
                "road_name": mapped.get("road_name")
                or mapped.get("lot_address")
                or None,
                "lot_address": mapped.get("lot_address") or None,
                "administrative_division": admin_div,
                "data_reference_date": ref_date,
                "raw_payload": raw_record,
                **parsed_booleans,
            }

            if source_type == "crosswalk":
                canonical["crossing_type"] = mapped.get("crossing_type")
                canonical["lane_count"] = (
                    int(mapped["lane_count"])
                    if mapped.get("lane_count", "").isdigit()
                    else None
                )
            else:
                canonical["signal_type"] = mapped.get("signal_type")

            canonical_records.append(canonical)
            report.valid_count += 1

    # 4. DB 적재 (db_engine이 전달된 경우 멱등하게 적재)
    if db_engine is not None and canonical_records:
        _persist_to_database(source_type, initial_sha256, canonical_records, db_engine)

    # 5. 처리 후 원본 파일 SHA-256 재계산 및 불변성 단언
    post_sha256 = compute_file_sha256(file_path)
    if initial_sha256 != post_sha256:
        raise RuntimeError(
            f"CRITICAL: 원천 파일 내용이 처리 도중 변경되었습니다! (이전: {initial_sha256}, 이후: {post_sha256})"
        )

    return report


def _persist_to_database(
    source_type: str, file_sha256: str, records: list[dict[str, Any]], db_engine: Any
) -> None:
    from sqlalchemy import text

    source_name = f"gwangju_{source_type}_std"

    with db_engine.begin() as conn:
        # Get or create source
        source_id = conn.execute(
            text("SELECT id FROM source WHERE name = :name"), {"name": source_name}
        ).scalar()

        if not source_id:
            source_id = str(uuid.uuid4())
            conn.execute(
                text(
                    "INSERT INTO source (id, name, provider) VALUES (:id, :name, '광주광역시')"
                ),
                {"id": source_id, "name": source_name},
            )

        # Ingest canonical records idempotently (SCD Type 2)
        for rec in records:
            key = rec["source_record_key"]
            raw_json = json.dumps(rec["raw_payload"], ensure_ascii=False)
            raw_sha = file_sha256

            # Store raw record
            conn.execute(
                text(
                    """
                    INSERT INTO raw_record (id, source_id, source_record_key, payload_json, payload_sha256)
                    VALUES (:id, :source_id, :key, CAST(:payload AS jsonb), :sha)
                    """
                ),
                {
                    "id": str(uuid.uuid4()),
                    "source_id": source_id,
                    "key": key,
                    "payload": raw_json,
                    "sha": raw_sha,
                },
            )

            # Check existing active record
            table_name = "crossing" if source_type == "crosswalk" else "signal_device"
            existing = conn.execute(
                text(
                    f"""
                    SELECT id, acoustic_signal, data_reference_date
                    FROM {table_name}
                    WHERE source_id = :source_id AND source_record_key = :key AND valid_to IS NULL
                    """
                ),
                {"source_id": source_id, "key": key},
            ).fetchone()

            if existing:
                # If existing record has same reference date, do not duplicate (Idempotency)
                if existing[2] == rec["data_reference_date"]:
                    continue

                # If reference date is newer, expire old record (SCD-2)
                now_utc = datetime.now(timezone.utc)
                conn.execute(
                    text(
                        f"""
                        UPDATE {table_name}
                        SET valid_to = :now_utc
                        WHERE id = :id
                        """
                    ),
                    {"now_utc": now_utc, "id": existing[0]},
                )

            # Insert new active record
            new_id = str(uuid.uuid4())
            if source_type == "crosswalk":
                conn.execute(
                    text(
                        """
                        INSERT INTO crossing (
                            id, source_id, source_record_key, geom, road_name,
                            pedestrian_signal, acoustic_signal, tactile_paving, curb_cut,
                            traffic_island, lane_count, data_reference_date,
                            quality_status, published, valid_from
                        ) VALUES (
                            :id, :source_id, :key, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), :road_name,
                            :ped_signal, :ac_signal, :tac_paving, :curb_cut,
                            :traffic_island, :lane_count, :ref_date,
                            'VALIDATED', true, now()
                        )
                        """
                    ),
                    {
                        "id": new_id,
                        "source_id": source_id,
                        "key": key,
                        "lon": rec["longitude"],
                        "lat": rec["latitude"],
                        "road_name": rec.get("road_name"),
                        "ped_signal": rec.get("pedestrian_signal"),
                        "ac_signal": rec.get("acoustic_signal"),
                        "tac_paving": rec.get("tactile_paving"),
                        "curb_cut": rec.get("curb_cut"),
                        "traffic_island": rec.get("traffic_island"),
                        "lane_count": rec.get("lane_count"),
                        "ref_date": rec.get("data_reference_date"),
                    },
                )
            else:
                conn.execute(
                    text(
                        """
                        INSERT INTO signal_device (
                            id, source_id, source_record_key, geom, signal_type,
                            acoustic_signal, button_signal, countdown_timer,
                            data_reference_date, quality_status, published, valid_from
                        ) VALUES (
                            :id, :source_id, :key, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), :sig_type,
                            :ac_signal, :btn_signal, :timer,
                            :ref_date, 'VALIDATED', true, now()
                        )
                        """
                    ),
                    {
                        "id": new_id,
                        "source_id": source_id,
                        "key": key,
                        "lon": rec["longitude"],
                        "lat": rec["latitude"],
                        "sig_type": rec.get("signal_type"),
                        "ac_signal": rec.get("acoustic_signal"),
                        "btn_signal": rec.get("button_signal"),
                        "timer": rec.get("countdown_timer"),
                        "ref_date": rec.get("data_reference_date"),
                    },
                )
