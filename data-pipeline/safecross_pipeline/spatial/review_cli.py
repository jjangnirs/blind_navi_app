import csv
import json
import os
import uuid
from typing import Any

from pydantic import BaseModel, Field
from pydantic import ValidationError as PydanticValidationError
from sqlalchemy import text


class ReviewItemSchema(BaseModel):
    link_id: str = Field(..., description="crossing_signal_link UUID")
    association_status: str = Field(..., description="'MANUAL_VERIFIED' or 'REJECTED'")
    comment: str | None = Field(default=None, description="운영자 검수 사유")


def export_review_queue(
    db_engine: Any,
    out_path: str,
    format: str = "csv",
    status_filter: str = "REVIEW_REQUIRED",
) -> int:
    """
    운영자용 검수 대상을 CSV 또는 GeoJSON 형식으로 export합니다.
    """
    where_clause = ""
    params: dict[str, Any] = {}
    if status_filter:
        where_clause = "WHERE l.association_status = :status"
        params["status"] = status_filter

    query = text(f"""
        SELECT 
            l.id AS link_id,
            l.crossing_id,
            c.source_record_key AS crossing_key,
            ST_Y(c.geom::geometry) AS crossing_lat,
            ST_X(c.geom::geometry) AS crossing_lon,
            c.road_name AS crossing_road,
            l.signal_device_id,
            s.source_record_key AS signal_key,
            ST_Y(s.geom::geometry) AS signal_lat,
            ST_X(s.geom::geometry) AS signal_lon,
            s.signal_type AS signal_type,
            l.approach_bearing_deg,
            l.distance_m,
            l.association_status,
            l.match_features,
            l.ai_allowed
        FROM crossing_signal_link l
        JOIN crossing c ON l.crossing_id = c.id
        JOIN signal_device s ON l.signal_device_id = s.id
        {where_clause}
        ORDER BY l.distance_m ASC;
    """)

    with db_engine.connect() as conn:
        rows = conn.execute(query, params).mappings().all()

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)

    if format.lower() == "geojson":
        features = []
        for r in rows:
            feat = {
                "type": "Feature",
                "geometry": {
                    "type": "LineString",
                    "coordinates": [
                        [float(r["crossing_lon"]), float(r["crossing_lat"])],
                        [float(r["signal_lon"]), float(r["signal_lat"])],
                    ],
                },
                "properties": {
                    "link_id": str(r["link_id"]),
                    "crossing_key": r["crossing_key"],
                    "crossing_road": r["crossing_road"],
                    "signal_key": r["signal_key"],
                    "signal_type": r["signal_type"],
                    "approach_bearing_deg": float(r["approach_bearing_deg"]),
                    "distance_m": float(r["distance_m"]) if r["distance_m"] else None,
                    "association_status": r["association_status"],
                    "ai_allowed": r["ai_allowed"],
                    "match_features": r["match_features"],
                },
            }
            features.append(feat)

        geojson_data = {
            "type": "FeatureCollection",
            "features": features,
        }
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(geojson_data, f, ensure_ascii=False, indent=2)

    else:  # CSV format
        with open(out_path, "w", encoding="utf-8-sig", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(
                [
                    "link_id",
                    "crossing_key",
                    "crossing_lat",
                    "crossing_lon",
                    "crossing_road",
                    "signal_key",
                    "signal_lat",
                    "signal_lon",
                    "signal_type",
                    "approach_bearing_deg",
                    "distance_m",
                    "association_status",
                    "ai_allowed",
                    "match_features_json",
                    "comment",
                ]
            )
            for r in rows:
                writer.writerow(
                    [
                        str(r["link_id"]),
                        r["crossing_key"],
                        r["crossing_lat"],
                        r["crossing_lon"],
                        r["crossing_road"] or "",
                        r["signal_key"],
                        r["signal_lat"],
                        r["signal_lon"],
                        r["signal_type"] or "",
                        r["approach_bearing_deg"],
                        r["distance_m"] or "",
                        r["association_status"],
                        r["ai_allowed"],
                        json.dumps(r["match_features"], ensure_ascii=False),
                        "",  # 운영자 작성란
                    ]
                )

    return len(rows)


def import_review_results(
    db_engine: Any,
    file_path: str,
    operator_id: str,
    role: str,
) -> dict[str, Any]:
    """
    운영자 검수 결과를 검증 후 DB에 반영하고 audit_event에 기록합니다.
    - 위조/잘못된 import 방지: 역할 검사(role in admin, operator)
    - 스키마 검증: Pydantic ReviewItemSchema
    - MANUAL_VERIFIED 승인 시 ai_allowed = true 활성화
    - audit_event 원자적 기록
    """
    if role.lower() not in ("admin", "operator"):
        raise PermissionError(
            f"허용되지 않은 역할입니다: '{role}' (admin 또는 operator 권한 필요)"
        )

    if not os.path.exists(file_path):
        raise FileNotFoundError(f"검수 결과 파일을 찾을 수 없습니다: {file_path}")

    # CSV 또는 JSON 파싱
    items_to_apply: list[ReviewItemSchema] = []
    if file_path.endswith((".json", ".geojson")):
        with open(file_path, "r", encoding="utf-8") as f:
            data = json.load(f)
            # GeoJSON인 경우
            if isinstance(data, dict) and data.get("type") == "FeatureCollection":
                for feat in data.get("features", []):
                    props = feat.get("properties", {})
                    items_to_apply.append(
                        ReviewItemSchema(
                            link_id=str(props.get("link_id")),
                            association_status=props.get("association_status", ""),
                            comment=props.get("comment"),
                        )
                    )
            elif isinstance(data, list):
                for obj in data:
                    items_to_apply.append(ReviewItemSchema(**obj))
    else:
        # CSV 파싱
        with open(file_path, "r", encoding="utf-8-sig", errors="replace") as f:
            reader = csv.DictReader(f)
            for row in reader:
                link_id = row.get("link_id", "").strip()
                status = row.get("association_status", "").strip()
                comment = row.get("comment", "").strip() or None
                if not link_id or not status:
                    continue
                try:
                    item = ReviewItemSchema(
                        link_id=link_id,
                        association_status=status,
                        comment=comment,
                    )
                    items_to_apply.append(item)
                except PydanticValidationError as e:
                    raise ValueError(
                        f"검수 데이터 스키마 오류 (link_id={link_id}): {e}"
                    ) from e

    applied_count = 0
    with db_engine.begin() as conn:
        for item in items_to_apply:
            if item.association_status not in ("MANUAL_VERIFIED", "REJECTED"):
                raise ValueError(
                    f"유효하지 않은 검수 상태: '{item.association_status}' (MANUAL_VERIFIED 또는 REJECTED 필요)"
                )

            # 기존 레코드 조회
            old_row = (
                conn.execute(
                    text(
                        "SELECT association_status, ai_allowed FROM crossing_signal_link WHERE id = :id"
                    ),
                    {"id": item.link_id},
                )
                .mappings()
                .first()
            )

            if not old_row:
                raise ValueError(
                    f"존재하지 않는 link_id: {item.link_id} (위조 또는 잘못된 식별자)"
                )

            old_status = old_row["association_status"]
            old_ai_allowed = old_row["ai_allowed"]

            # MANUAL_VERIFIED 승인 시 ai_allowed = true 활성화, REJECTED 시 false
            new_ai_allowed = item.association_status == "MANUAL_VERIFIED"

            # 업데이트 수행
            conn.execute(
                text("""
                    UPDATE crossing_signal_link
                    SET association_status = :status,
                        ai_allowed = :ai_allowed,
                        verified_by = :operator_id,
                        verified_at = NOW()
                    WHERE id = :id
                """),
                {
                    "status": item.association_status,
                    "ai_allowed": new_ai_allowed,
                    "operator_id": operator_id,
                    "id": item.link_id,
                },
            )

            # audit_event 기록 (SR-NF-026 감사 로그)
            action_name = (
                "APPROVE_REVIEW"
                if item.association_status == "MANUAL_VERIFIED"
                else "REJECT_REVIEW"
            )
            conn.execute(
                text("""
                    INSERT INTO audit_event (
                        id, entity_type, entity_id, action, actor,
                        before_state, after_state, reason
                    ) VALUES (
                        :id, 'crossing_signal_link', :entity_id, :action, :actor,
                        CAST(:before_state AS jsonb), CAST(:after_state AS jsonb), :reason
                    )
                """),
                {
                    "id": str(uuid.uuid4()),
                    "entity_id": item.link_id,
                    "action": action_name,
                    "actor": operator_id,
                    "before_state": json.dumps(
                        {"association_status": old_status, "ai_allowed": old_ai_allowed}
                    ),
                    "after_state": json.dumps(
                        {
                            "association_status": item.association_status,
                            "ai_allowed": new_ai_allowed,
                        }
                    ),
                    "reason": item.comment or f"운영자 {operator_id} 검수 처리",
                },
            )

            applied_count += 1

    return {
        "applied_count": applied_count,
        "operator_id": operator_id,
        "role": role,
    }
