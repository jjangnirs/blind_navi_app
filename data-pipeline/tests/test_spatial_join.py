import json
import os
import uuid

import pytest
from safecross_pipeline.spatial.features import (
    calculate_distance_m,
    compute_match_features,
)
from safecross_pipeline.spatial.review_cli import (
    export_review_queue,
    import_review_results,
)
from safecross_pipeline.spatial.spatial_join import (
    CrossingRecord,
    SignalRecord,
    run_spatial_join,
)
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError
from tests.fixtures.spatial_fixtures import (
    get_four_corner_intersection_scenario,
    get_parallel_road_scenario,
)


@pytest.fixture(scope="session")
def db_engine():
    db_url = os.getenv(
        "DATABASE_URL",
        "postgresql+psycopg://safecross:safecross_local_dev_only@localhost:5432/safecross",
    )
    engine = create_engine(db_url, pool_pre_ping=True)
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
    except (SQLAlchemyError, OSError) as e:
        pytest.skip(f"PostGIS database not reachable at {db_url}: {e}")
    return engine


def test_01_many_to_many_candidates_and_tiers():
    """
    수용 기준 1: 10m, 20m, 30m 거리의 다대다 후보 계산 및 거리 구간 티어링 검증
    """
    # 35.1500, 126.8500 기준
    lat0, lon0 = 35.1500, 126.8500

    # 1. 거리 계산
    dist_8m = calculate_distance_m(lat0, lon0, lat0 + 0.00007, lon0)
    assert 7.0 <= dist_8m <= 9.0

    feat_8m = compute_match_features(lat0, lon0, lat0 + 0.00007, lon0, 0.0)
    assert feat_8m["distance_tier"] == "10m"

    feat_15m = compute_match_features(lat0, lon0, lat0 + 0.00014, lon0, 0.0)
    assert feat_15m["distance_tier"] == "20m"

    feat_25m = compute_match_features(lat0, lon0, lat0 + 0.00023, lon0, 0.0)
    assert feat_25m["distance_tier"] == "30m"

    feat_40m = compute_match_features(lat0, lon0, lat0 + 0.00036, lon0, 0.0)
    assert feat_40m["distance_tier"] == ">30m"


def test_02_nearest_different_bearing_not_auto_confirmed():
    """
    수용 기준 2: 가장 가까운 점이라는 이유만으로 방향이 다른 신호를 확정하지 않음
    (평행 도로 및 직교 방향 시나리오 회귀 검증)
    """
    # 평행 도로 시나리오: 거리는 8m로 매우 가깝지만 도로명이 다름 (상무대로8번길 vs 상무대로)
    crossings, signals = get_parallel_road_scenario()
    summary = run_spatial_join(crossings, signals, max_radius_m=30.0)

    assert summary.total_candidate_pairs >= 1
    # 도로명이 불일치하므로 절대 AUTO_MATCHED가 될 수 없음
    for link in summary.links:
        assert link.association_status != "AUTO_MATCHED", (
            "도로명이 다르면 단 8m 거리라도 자동 확정 금지"
        )
        assert link.association_status in ("REVIEW_REQUIRED", "REJECTED")
        assert link.ai_allowed is False, "검수 전 ai_allowed는 무조건 False"


def test_03_multiple_candidates_marked_review_required():
    """
    수용 기준 3: 복수 후보는 무조건 REVIEW_REQUIRED 검수 큐로 보냄
    """
    # 단일 횡단보도에 15m 내 2개의 신호기 후보가 존재하는 경우
    crossing = CrossingRecord(
        id="cw-multi-01",
        source_record_key="CW-MULTI-01",
        lat=35.1500,
        lon=126.8500,
        road_name="금남로",
        direction_bearing_deg=90.0,
    )
    signals = [
        SignalRecord(
            id="tl-multi-01",
            source_record_key="TL-MULTI-01",
            lat=35.1500,
            lon=126.85010,
            road_name="금남로",
        ),
        SignalRecord(
            id="tl-multi-02",
            source_record_key="TL-MULTI-02",
            lat=35.1500,
            lon=126.85012,
            road_name="금남로",
        ),
    ]

    summary = run_spatial_join([crossing], signals, max_radius_m=30.0)
    # 동쪽 90도 방향 횡단에 대해 2개의 신호기가 반경 내에 있으므로 복수 후보 발생
    links_90 = [l for l in summary.links if l.approach_bearing_deg == 90.0]
    assert len(links_90) == 2
    for l in links_90:
        assert l.association_status == "REVIEW_REQUIRED", (
            "복수 후보는 안전을 위해 무조건 REVIEW_REQUIRED"
        )
        assert l.ai_allowed is False


def test_04_auto_matched_not_in_ai_allowlist_without_verification():
    """
    수용 기준 4: 자동 확정 조건(AUTO_MATCHED)이라도 field_verified가 아니면 AI allowlist(ai_allowed)에 넣지 않음
    """
    crossing = CrossingRecord(
        id="cw-single-01",
        source_record_key="CW-SINGLE-01",
        lat=35.1500,
        lon=126.8500,
        road_name="금남로",
        direction_bearing_deg=90.0,
    )
    # 90도 방향에 유일한 신호기 1개만 10m 거리에 정확히 위치
    signal = SignalRecord(
        id="tl-single-01",
        source_record_key="TL-SINGLE-01",
        lat=35.1500,
        lon=126.85010,
        road_name="금남로",
    )

    summary = run_spatial_join([crossing], [signal], max_radius_m=30.0)
    link_90 = next(l for l in summary.links if l.approach_bearing_deg == 90.0)

    # 유일 후보, 도로명 일치, 방향 일치이므로 AUTO_MATCHED 부여
    assert link_90.association_status == "AUTO_MATCHED"
    # 그러나 현장 검증(field_verified) 없는 자동 매칭이므로 ai_allowed는 무조건 False여야 함 (핵심 안전 규칙)
    assert link_90.ai_allowed is False, (
        "현장 검증 전 공공데이터 자동 연계는 절대 ai_allowed=True 금지"
    )


def test_05_bidirectional_links_maintained_separately():
    """
    수용 기준 5: 서로 반대 방향의 같은 횡단보도는 별도 링크로 유지
    (4-모서리 교차로 및 교통섬 시나리오 회귀 검증)
    """
    crossings, signals = get_four_corner_intersection_scenario()
    summary = run_spatial_join(crossings, signals, max_radius_m=30.0)

    # 북측 횡단보도(CW-CORNER-N)의 링크 확인
    cw_n_links = [l for l in summary.links if l.crossing_id == "cw-north-01"]
    bearings = {l.approach_bearing_deg for l in cw_n_links}

    # 90도(정방향: 동쪽 진행)와 270도(역방향: 서쪽 진행) 각각 독립된 링크 생성 확인
    assert 90.0 in bearings
    assert 270.0 in bearings

    # 동쪽으로 건널 때(90도)는 동쪽 끝 신호기(TL-CORNER-NE)와 매칭
    link_eastbound = next(
        l
        for l in cw_n_links
        if l.approach_bearing_deg == 90.0 and l.signal_device_id == "tl-ne-01"
    )
    assert link_eastbound.match_features["bearing_diff_deg"] <= 15.0

    # 서쪽으로 건널 때(270도)는 서쪽 끝 신호기(TL-CORNER-NW)와 매칭
    link_westbound = next(
        l
        for l in cw_n_links
        if l.approach_bearing_deg == 270.0 and l.signal_device_id == "tl-nw-01"
    )
    assert link_westbound.match_features["bearing_diff_deg"] <= 15.0


def test_06_review_export_and_import_with_role_check(db_engine, tmp_path):
    """
    수용 기준 6: 운영자 검수 CSV/GeoJSON export 및 위조 방지 스키마/역할 검사(role check)
    """
    # 1. Fixture DB 세팅
    c_id = str(uuid.uuid4())
    s_id = str(uuid.uuid4())
    l_id = str(uuid.uuid4())

    with db_engine.begin() as conn:
        src_id = conn.execute(text("SELECT id FROM source LIMIT 1")).scalar()
        if not src_id:
            src_id = str(uuid.uuid4())
            conn.execute(
                text(
                    "INSERT INTO source (id, name, provider) VALUES (:id, 'src_test', '지자체')"
                ),
                {"id": src_id},
            )

        # Insert test crossing & signal
        conn.execute(
            text("""
            INSERT INTO crossing (id, source_id, source_record_key, geom, road_name, published, valid_from)
            VALUES (:id, :s_id, 'CW-EXP-01', ST_SetSRID(ST_MakePoint(126.85, 35.15), 4326), '상무중앙로', true, NOW())
        """),
            {"id": c_id, "s_id": src_id},
        )

        conn.execute(
            text("""
            INSERT INTO signal_device (id, source_id, source_record_key, geom, signal_type, published, valid_from)
            VALUES (:id, :s_id, 'TL-EXP-01', ST_SetSRID(ST_MakePoint(126.8501, 35.15), 4326), '보행등', true, NOW())
        """),
            {"id": s_id, "s_id": src_id},
        )

        # Insert REVIEW_REQUIRED link
        conn.execute(
            text("""
            INSERT INTO crossing_signal_link (
                id, crossing_id, signal_device_id, approach_bearing_deg,
                distance_m, association_status, ai_allowed, match_features
            ) VALUES (
                :id, :c_id, :s_id, 90.0, 9.5, 'REVIEW_REQUIRED', false, '{"reason": "test"}'::jsonb
            )
        """),
            {"id": l_id, "c_id": c_id, "s_id": s_id},
        )

    # 2. Export 검수 (CSV & GeoJSON)
    csv_file = str(tmp_path / "review_queue.csv")
    geojson_file = str(tmp_path / "review_queue.geojson")

    csv_count = export_review_queue(
        db_engine, csv_file, format="csv", status_filter="REVIEW_REQUIRED"
    )
    assert csv_count >= 1
    assert os.path.exists(csv_file)

    geojson_count = export_review_queue(
        db_engine, geojson_file, format="geojson", status_filter="REVIEW_REQUIRED"
    )
    assert geojson_count >= 1
    assert os.path.exists(geojson_file)

    # 3. 비인가 역할(unauthorized role) 차단 검증
    with pytest.raises(PermissionError) as exc_info:
        import_review_results(db_engine, csv_file, operator_id="attacker", role="guest")
    assert "허용되지 않은 역할" in str(exc_info.value)

    with pytest.raises(PermissionError):
        import_review_results(
            db_engine, csv_file, operator_id="user_normal", role="user"
        )


def test_07_audit_event_and_allowlist_on_manual_verification(db_engine, tmp_path):
    """
    수용 기준 7: 검수 승인(MANUAL_VERIFIED) 시 ai_allowed 활성화 및 audit_event 감사 로그 기록 검증
    """
    c_id = str(uuid.uuid4())
    s_id = str(uuid.uuid4())
    l_id = str(uuid.uuid4())
    src_id = str(uuid.uuid4())

    with db_engine.begin() as conn:
        conn.execute(
            text(
                "INSERT INTO source (id, name, provider) VALUES (:id, :name, '광주광역시')"
            ),
            {"id": src_id, "name": f"src_{c_id[:8]}"},
        )
        conn.execute(
            text("""
            INSERT INTO crossing (id, source_id, source_record_key, geom, road_name, published, valid_from)
            VALUES (:id, :s_id, 'CW-AUDIT-01', ST_SetSRID(ST_MakePoint(126.85, 35.15), 4326), '빛고을대로', true, NOW())
        """),
            {"id": c_id, "s_id": src_id},
        )

        conn.execute(
            text("""
            INSERT INTO signal_device (id, source_id, source_record_key, geom, signal_type, published, valid_from)
            VALUES (:id, :s_id, 'TL-AUDIT-01', ST_SetSRID(ST_MakePoint(126.8501, 35.15), 4326), '보행등', true, NOW())
        """),
            {"id": s_id, "s_id": src_id},
        )

        conn.execute(
            text("""
            INSERT INTO crossing_signal_link (
                id, crossing_id, signal_device_id, approach_bearing_deg,
                distance_m, association_status, ai_allowed, match_features
            ) VALUES (
                :id, :c_id, :s_id, 90.0, 8.0, 'REVIEW_REQUIRED', false, '{"test": true}'::jsonb
            )
        """),
            {"id": l_id, "c_id": c_id, "s_id": s_id},
        )

    # 검수 승인 CSV 작성
    review_csv = tmp_path / "review_approval.csv"
    review_content = (
        "link_id,association_status,comment\n"
        f"{l_id},MANUAL_VERIFIED,현장 영상 대조 결과 일치 확인\n"
    )
    review_csv.write_text(review_content, encoding="utf-8-sig")

    # 운영자 권한으로 import 실행
    res = import_review_results(
        db_engine=db_engine,
        file_path=str(review_csv),
        operator_id="op_safety_01",
        role="operator",
    )
    assert res["applied_count"] == 1

    # DB 검증
    with db_engine.connect() as conn:
        link_row = (
            conn.execute(
                text(
                    "SELECT association_status, ai_allowed, verified_by FROM crossing_signal_link WHERE id = :id"
                ),
                {"id": l_id},
            )
            .mappings()
            .first()
        )

        assert link_row["association_status"] == "MANUAL_VERIFIED"
        assert link_row["ai_allowed"] is True, (
            "운영자 검수 승인 시 ai_allowed가 True로 활성화되어야 함"
        )
        assert link_row["verified_by"] == "op_safety_01"

        # audit_event 검증
        audit_row = (
            conn.execute(
                text(
                    "SELECT actor, entity_type, action, before_state, after_state, reason FROM audit_event WHERE entity_id = :id"
                ),
                {"id": l_id},
            )
            .mappings()
            .first()
        )

        assert audit_row is not None
        assert audit_row["actor"] == "op_safety_01"
        assert audit_row["entity_type"] == "crossing_signal_link"
        assert audit_row["action"] == "APPROVE_REVIEW"
        assert audit_row["before_state"]["association_status"] == "REVIEW_REQUIRED"
        assert audit_row["after_state"]["association_status"] == "MANUAL_VERIFIED"
        assert audit_row["after_state"]["ai_allowed"] is True
        assert "현장 영상 대조" in audit_row["reason"]


def test_08_diagonal_crosswalk_without_osm_marked_review_required():
    """
    수용 기준 8: OSM 형상 없이 단일 Point만 있는 대각선 횡단보도는
    기본 각도(0도)와 대각선 신호기(약 40도) 간 각도 차이가 커서(>=35도)
    보수적으로 REVIEW_REQUIRED 격리됨을 검증
    """
    crossing = CrossingRecord(
        id="cw-diag-no-osm",
        source_record_key="CW-DIAG-01",
        lat=35.1500,
        lon=126.8500,
        road_name="상무중앙로",
        # direction_bearing_deg 미지정 -> 기본 0.0도 및 180.0도 적용
    )
    # 대각선 방향(북동쪽, 약 14.4m) 위치의 신호기
    signal = SignalRecord(
        id="tl-diag-01",
        source_record_key="TL-DIAG-01",
        lat=35.150100,
        lon=126.850100,
        road_name="상무중앙로",
    )
    summary = run_spatial_join(
        [crossing], [signal], max_radius_m=20.0, osm_features=None
    )
    assert summary.auto_matched_count == 0
    assert summary.review_required_count >= 1
    for link in summary.links:
        assert link.association_status in ("REVIEW_REQUIRED", "REJECTED")
        assert link.ai_allowed is False, "미검증 링크는 ai_allowed가 항상 False여야 함"


def test_09_diagonal_crosswalk_with_osm_bearing_auto_matched():
    """
    수용 기준 9: OSM LineString 형상 연계 시 대각선 횡단보도의 진행 방위각이
    정밀 보정되어 신호기와 각도 일치(<=35도)로 AUTO_MATCHED 승격 검증
    (단, 현장 검증 전 ai_allowed는 여전히 False로 안전 유지)
    """
    fixture_path = os.path.join(
        os.path.dirname(__file__), "fixtures", "osm_crosswalk_sample.geojson"
    )
    with open(fixture_path, "r", encoding="utf-8") as f:
        osm_data = json.load(f)

    crossing = CrossingRecord(
        id="cw-diag-with-osm",
        source_record_key="CW-DIAG-01",
        lat=35.1500,
        lon=126.8500,
        road_name="상무중앙로",
        # direction_bearing_deg는 미지정 상태이나 OSM 선형 형상을 통해 주입됨
    )
    # 신호기를 OSM LineString의 진행 방향 끝점(북동쪽)에 배치
    signal = SignalRecord(
        id="tl-diag-01",
        source_record_key="TL-DIAG-01",
        lat=35.150100,
        lon=126.850100,
        road_name="상무중앙로",
    )
    summary = run_spatial_join(
        [crossing],
        [signal],
        max_radius_m=20.0,
        osm_features=osm_data["features"],
    )

    # 40도 대각선 방향 링크 중 AUTO_MATCHED가 존재해야 함
    auto_links = [l for l in summary.links if l.association_status == "AUTO_MATCHED"]
    assert len(auto_links) == 1, (
        "OSM 정밀 방위각 보정으로 1개 링크가 AUTO_MATCHED로 승격되어야 함"
    )
    matched = auto_links[0]
    assert matched.match_features["osm_bearing_derived"] is True
    assert matched.match_features["osm_snap_distance_m"] < 5.0
    assert matched.match_features["bearing_diff_deg"] <= 5.0
    assert matched.ai_allowed is False, (
        "현장 검증 전 자동 매칭이라도 ai_allowed는 무조건 False여야 함"
    )
