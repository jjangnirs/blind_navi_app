import json
from datetime import datetime
from typing import Any

from sqlalchemy import text
from sqlalchemy.orm import Session

from app.domain.schemas import (
    ConflictDetails,
    CrossingItemResponse,
    LocationPoint,
    RawFacility,
    SourceMetadata,
    VerificationMetadata,
)

NEARBY_QUERY_SQL = text("""
SELECT
    c.id,
    ST_Y(c.geom::geometry) AS lat,
    ST_X(c.geom::geometry) AS lon,
    c.road_name,
    c.pedestrian_signal,
    COALESCE(fv.acoustic_signal, c.acoustic_signal) AS acoustic_signal,
    COALESCE(fv.tactile_paving, c.tactile_paving) AS tactile_paving,
    COALESCE(fv.curb_cut, c.curb_cut) AS curb_cut,
    COALESCE(fv.direction_bearing_deg, c.direction_bearing_deg) AS direction_bearing_deg,
    c.lane_count,
    c.green_seconds,
    c.red_seconds,
    c.data_reference_date,
    s.provider AS source_provider,
    c.source_record_key,
    c.valid_from AS ingested_at,
    c.acoustic_signal AS raw_acoustic_signal,
    c.tactile_paving AS raw_tactile_paving,
    c.curb_cut AS raw_curb_cut,
    c.direction_bearing_deg AS raw_direction_bearing_deg,
    fv.acoustic_signal AS fv_acoustic_signal,
    fv.tactile_paving AS fv_tactile_paving,
    fv.curb_cut AS fv_curb_cut,
    fv.verified_at AS field_verified_at,
    fv.verified_by AS field_verified_by,
    (fv.id IS NOT NULL) AS is_field_verified
FROM crossing c
JOIN source s ON s.id = c.source_id
LEFT JOIN LATERAL (
    SELECT id, acoustic_signal, tactile_paving, curb_cut, direction_bearing_deg, verified_at, verified_by
    FROM field_verification
    WHERE crossing_id = c.id
    ORDER BY verified_at DESC
    LIMIT 1
) fv ON TRUE
WHERE c.published = TRUE
  AND c.valid_to IS NULL
  AND c.geom && ST_Expand(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), :deg_buffer)
  AND ST_DWithin(c.geom::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radius_m)
ORDER BY ST_Distance(c.geom::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) ASC
LIMIT :limit;
""")

CORRIDOR_QUERY_SQL = text("""
SELECT
    c.id,
    ST_Y(c.geom::geometry) AS lat,
    ST_X(c.geom::geometry) AS lon,
    c.road_name,
    c.pedestrian_signal,
    COALESCE(fv.acoustic_signal, c.acoustic_signal) AS acoustic_signal,
    COALESCE(fv.tactile_paving, c.tactile_paving) AS tactile_paving,
    COALESCE(fv.curb_cut, c.curb_cut) AS curb_cut,
    COALESCE(fv.direction_bearing_deg, c.direction_bearing_deg) AS direction_bearing_deg,
    c.lane_count,
    c.green_seconds,
    c.red_seconds,
    c.data_reference_date,
    s.provider AS source_provider,
    c.source_record_key,
    c.valid_from AS ingested_at,
    c.acoustic_signal AS raw_acoustic_signal,
    c.tactile_paving AS raw_tactile_paving,
    c.curb_cut AS raw_curb_cut,
    c.direction_bearing_deg AS raw_direction_bearing_deg,
    fv.acoustic_signal AS fv_acoustic_signal,
    fv.tactile_paving AS fv_tactile_paving,
    fv.curb_cut AS fv_curb_cut,
    fv.verified_at AS field_verified_at,
    fv.verified_by AS field_verified_by,
    (fv.id IS NOT NULL) AS is_field_verified
FROM crossing c
JOIN source s ON s.id = c.source_id
LEFT JOIN LATERAL (
    SELECT id, acoustic_signal, tactile_paving, curb_cut, direction_bearing_deg, verified_at, verified_by
    FROM field_verification
    WHERE crossing_id = c.id
    ORDER BY verified_at DESC
    LIMIT 1
) fv ON TRUE
WHERE c.published = TRUE
  AND c.valid_to IS NULL
  AND c.geom && ST_Expand(ST_SetSRID(ST_GeomFromGeoJSON(:gj), 4326), :deg_buffer)
  AND ST_DWithin(c.geom::geography, ST_SetSRID(ST_GeomFromGeoJSON(:gj), 4326)::geography, :buffer_m)
ORDER BY ST_Distance(c.geom::geography, ST_SetSRID(ST_GeomFromGeoJSON(:gj), 4326)::geography) ASC
LIMIT :limit;
""")


def _row_to_item(row: dict[str, Any]) -> CrossingItemResponse:
    is_verified = bool(row["is_field_verified"])

    # 원천 vs 현장 검증 충돌 여부 감지
    conflict_acoustic = None
    conflict_tactile = None
    conflict_curb = None
    has_conflict = False

    if is_verified:
        raw_ac = row["raw_acoustic_signal"]
        fv_ac = row["fv_acoustic_signal"]
        if raw_ac is not None and fv_ac is not None and raw_ac != fv_ac:
            conflict_acoustic = True
            has_conflict = True

        raw_tp = row["raw_tactile_paving"]
        fv_tp = row["fv_tactile_paving"]
        if raw_tp is not None and fv_tp is not None and raw_tp != fv_tp:
            conflict_tactile = True
            has_conflict = True

        raw_cc = row["raw_curb_cut"]
        fv_cc = row["fv_curb_cut"]
        if raw_cc is not None and fv_cc is not None and raw_cc != fv_cc:
            conflict_curb = True
            has_conflict = True

    conflict_details = None
    if has_conflict:
        conflict_details = ConflictDetails(
            acousticSignal=conflict_acoustic,
            tactilePaving=conflict_tactile,
            curbCut=conflict_curb,
        )

    ref_date_str = (
        row["data_reference_date"].isoformat()
        if row.get("data_reference_date")
        else None
    )
    ingested_at_str = (
        row["ingested_at"].isoformat()
        if isinstance(row.get("ingested_at"), datetime)
        else str(row.get("ingested_at"))
        if row.get("ingested_at")
        else None
    )
    verified_at_str = (
        row["field_verified_at"].isoformat()
        if isinstance(row.get("field_verified_at"), datetime)
        else str(row.get("field_verified_at"))
        if row.get("field_verified_at")
        else None
    )

    bearing = (
        float(row["direction_bearing_deg"])
        if row.get("direction_bearing_deg") is not None
        else None
    )
    raw_bearing = (
        float(row["raw_direction_bearing_deg"])
        if row.get("raw_direction_bearing_deg") is not None
        else None
    )

    return CrossingItemResponse(
        id=str(row["id"]),
        location=LocationPoint(
            lat=float(row["lat"]),
            lon=float(row["lon"]),
        ),
        roadName=row.get("road_name"),
        pedestrianSignal=row.get("pedestrian_signal"),
        acousticSignal=row.get("acoustic_signal"),
        tactilePaving=row.get("tactile_paving"),
        curbCut=row.get("curb_cut"),
        directionBearingDeg=bearing,
        laneCount=row.get("lane_count"),
        greenSeconds=row.get("green_seconds"),
        redSeconds=row.get("red_seconds"),
        source=SourceMetadata(
            provider=row["source_provider"],
            sourceRecordId=row["source_record_key"],
            referenceDate=ref_date_str,
            ingestedAt=ingested_at_str,
        ),
        verification=VerificationMetadata(
            status="FIELD_VERIFIED" if is_verified else "UNVERIFIED",
            verifiedAt=verified_at_str,
            verifiedBy=row.get("field_verified_by"),
            hasSourceConflict=has_conflict,
            conflictDetails=conflict_details,
        ),
        rawFacility=RawFacility(
            acousticSignal=row.get("raw_acoustic_signal"),
            tactilePaving=row.get("raw_tactile_paving"),
            curbCut=row.get("raw_curb_cut"),
            directionBearingDeg=raw_bearing,
        ),
    )


def find_crossings_nearby(
    db: Session,
    lat: float,
    lon: float,
    radius_m: float,
    limit: int = 50,
) -> list[CrossingItemResponse]:
    """
    ST_DWithin 및 GiST 인덱스(&& ST_Expand)를 활용하여 중심점으로부터 radius_m 내의
    횡단보도 목록을 거리 순으로 반환합니다.
    """
    # 1도 ~ 88,000m 기준 넉넉한 deg_buffer 산출로 공간 인덱스 강제 적용
    deg_buffer = (radius_m / 80000.0) + 0.0001

    rows = (
        db.execute(
            NEARBY_QUERY_SQL,
            {
                "lat": lat,
                "lon": lon,
                "radius_m": radius_m,
                "deg_buffer": deg_buffer,
                "limit": limit,
            },
        )
        .mappings()
        .all()
    )

    return [_row_to_item(r) for r in rows]


def find_crossings_in_corridor(
    db: Session,
    coordinates: list[list[float]],
    buffer_m: float,
    limit: int = 50,
) -> list[CrossingItemResponse]:
    """
    LineString 형상으로부터 buffer_m 폭 내의 횡단보도 목록을 경로 접근 순으로 반환합니다.
    """
    geojson_geom = {
        "type": "LineString",
        "coordinates": coordinates,
    }
    gj_str = json.dumps(geojson_geom)
    deg_buffer = (buffer_m / 80000.0) + 0.0001

    rows = (
        db.execute(
            CORRIDOR_QUERY_SQL,
            {
                "gj": gj_str,
                "buffer_m": buffer_m,
                "deg_buffer": deg_buffer,
                "limit": limit,
            },
        )
        .mappings()
        .all()
    )

    return [_row_to_item(r) for r in rows]


def explain_nearby_query(
    db: Session,
    lat: float,
    lon: float,
    radius_m: float,
) -> list[str]:
    """PostGIS 실행 계획(EXPLAIN)을 확인합니다."""
    deg_buffer = (radius_m / 80000.0) + 0.0001
    sql = text(f"EXPLAIN {NEARBY_QUERY_SQL.text}")
    rows = db.execute(
        sql,
        {
            "lat": lat,
            "lon": lon,
            "radius_m": radius_m,
            "deg_buffer": deg_buffer,
            "limit": 50,
        },
    ).all()
    return [r[0] for r in rows]


def explain_corridor_query(
    db: Session,
    coordinates: list[list[float]],
    buffer_m: float,
) -> list[str]:
    """경로 회랑 쿼리의 PostGIS 실행 계획(EXPLAIN)을 확인합니다."""
    gj_str = json.dumps({"type": "LineString", "coordinates": coordinates})
    deg_buffer = (buffer_m / 80000.0) + 0.0001
    sql = text(f"EXPLAIN {CORRIDOR_QUERY_SQL.text}")
    rows = db.execute(
        sql,
        {
            "gj": gj_str,
            "buffer_m": buffer_m,
            "deg_buffer": deg_buffer,
            "limit": 50,
        },
    ).all()
    return [r[0] for r in rows]
