import json
import uuid
from dataclasses import dataclass, field
from typing import Any

from sqlalchemy import text

from safecross_pipeline.spatial.features import compute_match_features
from safecross_pipeline.spatial.osm_geometry import (
    enrich_crossings_with_osm,
    match_osm_crossing_bearing,
)


@dataclass
class CrossingRecord:
    id: str
    source_record_key: str
    lat: float
    lon: float
    road_name: str | None
    direction_bearing_deg: float | None = None


@dataclass
class SignalRecord:
    id: str
    source_record_key: str
    lat: float
    lon: float
    road_name: str | None
    signal_type: str | None = None


@dataclass
class LinkCandidate:
    crossing_id: str
    signal_device_id: str
    approach_bearing_deg: float
    distance_m: float
    match_features: dict[str, Any]
    association_status: str  # 'AUTO_MATCHED' | 'REVIEW_REQUIRED' | 'REJECTED'
    ai_allowed: bool = False
    provider_intersection_id: str | None = None
    provider_movement_id: str | None = None


@dataclass
class SpatialJoinSummary:
    total_crossings: int = 0
    total_signals: int = 0
    total_candidate_pairs: int = 0
    auto_matched_count: int = 0
    review_required_count: int = 0
    rejected_count: int = 0
    ai_allowed_count: int = 0  # 초기 자동 실행 시 0이어야 함 (안전 규칙)
    links: list[LinkCandidate] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "total_crossings": self.total_crossings,
            "total_signals": self.total_signals,
            "total_candidate_pairs": self.total_candidate_pairs,
            "auto_matched_count": self.auto_matched_count,
            "review_required_count": self.review_required_count,
            "rejected_count": self.rejected_count,
            "ai_allowed_count": self.ai_allowed_count,
        }


def classify_link(
    features: dict[str, Any],
    candidate_count_for_direction: int,
) -> tuple[str, bool]:
    """
    프롬프트 4 안전 규칙에 따라 후보 링크를 분류합니다:
    1. 가장 가까운 점이라도 방향이 다르면(bearing_diff_deg > 45°) 절대 자동 확정 불가.
    2. 복수 후보(candidate_count > 1)는 무조건 REVIEW_REQUIRED.
    3. 도로명 불일치(MISMATCH)이거나 거리가 15m 초과이면 REVIEW_REQUIRED.
    4. 자동 확정(AUTO_MATCHED)되더라도 현장 검증 전까지 ai_allowed는 무조건 False.
    """
    dist_m = features["distance_m"]
    bearing_diff = features["bearing_diff_deg"]
    road_match = features["road_match"]

    # 1. 방향 충돌 (90도 초과 시 명백히 반대/무관 방향 신호)
    if bearing_diff > 90.0:
        return "REJECTED", False

    # 2. 복수 후보는 안전을 위해 무조건 검수 큐로 격리
    if candidate_count_for_direction > 1:
        return "REVIEW_REQUIRED", False

    # 3. 도로명 불일치 또는 각도 오차가 큰 경우 (35도 초과)
    if road_match == "MISMATCH" or bearing_diff > 35.0 or dist_m > 15.0:
        return "REVIEW_REQUIRED", False

    # 4. 단일 후보이고, 거리 15m 이하, 각도 오차 35도 이하, 도로명 일치 시 자동 매칭
    # 주의: AUTO_MATCHED라도 field_verified가 아니면 ai_allowed는 무조건 False!
    return "AUTO_MATCHED", False


def run_spatial_join(
    crossings: list[CrossingRecord],
    signals: list[SignalRecord],
    max_radius_m: float = 30.0,
    osm_features: list[dict[str, Any]] | None = None,
) -> SpatialJoinSummary:
    """
    인메모리 또는 DB에서 조회된 횡단보도와 신호기 목록을 바탕으로
    방향별(정방향/역방향) 공간 조인을 수행하고 보수적 분류를 적용합니다.
    OSM 형상이 제공될 경우 누락된 횡단보도 방위각을 정밀 보강합니다.
    """
    # OSM 선형 형상 연계 (대각선 횡단보도 정밀 각도 보강)
    if osm_features:
        crossings = enrich_crossings_with_osm(crossings, osm_features)

    summary = SpatialJoinSummary(
        total_crossings=len(crossings),
        total_signals=len(signals),
    )

    for crossing in crossings:
        # OSM 스냅 메타데이터 추출
        osm_bearing, osm_dist = (None, None)
        if osm_features:
            osm_bearing, osm_dist = match_osm_crossing_bearing(
                crossing.lat, crossing.lon, osm_features
            )

        # 횡단보도의 방향: 지정/보정된 각도가 없으면 기본 0도(남->북) 및 180도(북->남)
        base_bearing = (
            crossing.direction_bearing_deg
            if crossing.direction_bearing_deg is not None
            else 0.0
        )
        # 서로 반대 방향의 같은 횡단보도는 별도 링크로 유지 (수용 기준 5)
        directions = [
            round(base_bearing % 360.0, 1),
            round((base_bearing + 180.0) % 360.0, 1),
        ]

        for direction in directions:
            # 1. 반경 max_radius_m 내 후보 탐색
            direction_candidates: list[dict[str, Any]] = []
            for signal in signals:
                feat = compute_match_features(
                    crossing_lat=crossing.lat,
                    crossing_lon=crossing.lon,
                    signal_lat=signal.lat,
                    signal_lon=signal.lon,
                    approach_bearing_deg=direction,
                    crossing_road=crossing.road_name,
                    signal_road=signal.road_name,
                    crossing_key=crossing.source_record_key,
                    signal_key=signal.source_record_key,
                )

                # OSM 형상 메타데이터 주입
                feat["osm_bearing_derived"] = osm_bearing is not None
                feat["osm_snap_distance_m"] = osm_dist

                if feat["distance_m"] <= max_radius_m:
                    direction_candidates.append(
                        {
                            "signal": signal,
                            "features": feat,
                        }
                    )

            candidate_count = len(direction_candidates)

            # 2. 후보 분류
            for cand in direction_candidates:
                summary.total_candidate_pairs += 1
                signal = cand["signal"]
                feat = cand["features"]

                status, ai_allowed = classify_link(feat, candidate_count)

                if status == "AUTO_MATCHED":
                    summary.auto_matched_count += 1
                elif status == "REVIEW_REQUIRED":
                    summary.review_required_count += 1
                else:
                    summary.rejected_count += 1

                if ai_allowed:
                    summary.ai_allowed_count += 1

                summary.links.append(
                    LinkCandidate(
                        crossing_id=crossing.id,
                        signal_device_id=signal.id,
                        approach_bearing_deg=direction,
                        distance_m=feat["distance_m"],
                        match_features=feat,
                        association_status=status,
                        ai_allowed=ai_allowed,
                    )
                )

    return summary


def persist_links_to_db(links: list[LinkCandidate], db_engine: Any) -> int:
    """
    계산된 공간 조인 링크 목록을 DB `crossing_signal_link` 테이블에 upsert합니다.
    """
    upsert_sql = text("""
        INSERT INTO crossing_signal_link (
            id, crossing_id, signal_device_id, approach_bearing_deg,
            distance_m, match_features, association_status, ai_allowed
        ) VALUES (
            :id, :crossing_id, :signal_device_id, :approach_bearing_deg,
            :distance_m, CAST(:match_features AS jsonb), :association_status, :ai_allowed
        )
        ON CONFLICT (crossing_id, approach_bearing_deg, signal_device_id)
        DO UPDATE SET
            distance_m = EXCLUDED.distance_m,
            match_features = EXCLUDED.match_features,
            association_status = CASE
                -- 이미 운영자가 수동 검수한 건은 덮어쓰지 않고 보존
                WHEN crossing_signal_link.association_status = 'MANUAL_VERIFIED' THEN crossing_signal_link.association_status
                ELSE EXCLUDED.association_status
            END,
            ai_allowed = CASE
                WHEN crossing_signal_link.association_status = 'MANUAL_VERIFIED' THEN crossing_signal_link.ai_allowed
                ELSE EXCLUDED.ai_allowed
            END;
    """)

    with db_engine.begin() as conn:
        for link in links:
            conn.execute(
                upsert_sql,
                {
                    "id": str(uuid.uuid4()),
                    "crossing_id": link.crossing_id,
                    "signal_device_id": link.signal_device_id,
                    "approach_bearing_deg": link.approach_bearing_deg,
                    "distance_m": link.distance_m,
                    "match_features": json.dumps(
                        link.match_features, ensure_ascii=False
                    ),
                    "association_status": link.association_status,
                    "ai_allowed": link.ai_allowed,
                },
            )

    return len(links)
