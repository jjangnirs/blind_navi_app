import os
import uuid

import pytest
from app.core.middleware import InMemoryRateLimiter
from app.main import app
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError

client = TestClient(app)

BASE_LAT = 35.1600
BASE_LON = 126.8600


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


@pytest.fixture(scope="session", autouse=True)
def seed_test_crossings(db_engine):
    """
    주변 시설 API 테스트를 위한 독립된 PostGIS 시드 데이터를 준비합니다.
    - cw_near_01: 35.1600, 126.8600 (기준점 0m), 현장검증 완료, 원천 충돌(curb_cut)
    - cw_near_02: 35.1607, 126.8600 (~77m), 미검증, 명시적 false 및 null 포함
    - cw_far_01:  35.1620, 126.8600 (~222m), published=True
    - cw_unpub:   35.1600, 126.8600, published=False (제외 대상)
    - cw_expired: 35.1600, 126.8600, valid_to=NOW() (제외 대상)
    """
    src_id = str(uuid.uuid4())
    c1_id = str(uuid.uuid4())
    c2_id = str(uuid.uuid4())
    c3_id = str(uuid.uuid4())
    c_unpub_id = str(uuid.uuid4())
    c_exp_id = str(uuid.uuid4())
    fv_id = str(uuid.uuid4())

    with db_engine.begin() as conn:
        conn.execute(
            text("DELETE FROM crossing WHERE source_record_key LIKE 'CW-API-%'")
        )
        conn.execute(
            text(
                "INSERT INTO source (id, name, provider) VALUES (:id, :name, '광주광역시')"
            ),
            {"id": src_id, "name": f"src_api_test_{src_id[:8]}"},
        )

        # 1. 기준점 횡단보도 (현장검증 완료, 충돌 있음: raw curb_cut=true, fv curb_cut=false)
        conn.execute(
            text("""
            INSERT INTO crossing (
                id, source_id, source_record_key, geom, road_name,
                pedestrian_signal, acoustic_signal, tactile_paving, curb_cut,
                direction_bearing_deg, lane_count, green_seconds, red_seconds,
                data_reference_date, published, valid_from
            ) VALUES (
                :id, :src_id, 'CW-API-01', ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), '금남로',
                true, NULL, true, true,
                90.0, 4, 30, 90,
                '2026-06-30', true, NOW()
            )
            """),
            {"id": c1_id, "src_id": src_id, "lat": BASE_LAT, "lon": BASE_LON},
        )

        # 1-1. 현장 검증값 삽입 (acoustic_signal=true, curb_cut=false 로 수정)
        conn.execute(
            text("""
            INSERT INTO field_verification (
                id, crossing_id, verified_by, verified_at,
                acoustic_signal, tactile_paving, curb_cut, direction_bearing_deg
            ) VALUES (
                :id, :c_id, 'op_safety_inspector', NOW(),
                true, true, false, 95.0
            )
            """),
            {"id": fv_id, "c_id": c1_id},
        )

        # 2. 77m 위치 횡단보도 (미검증, acoustic_signal=false, tactile_paving=NULL)
        conn.execute(
            text("""
            INSERT INTO crossing (
                id, source_id, source_record_key, geom, road_name,
                pedestrian_signal, acoustic_signal, tactile_paving, curb_cut,
                direction_bearing_deg, lane_count, data_reference_date, published, valid_from
            ) VALUES (
                :id, :src_id, 'CW-API-02', ST_SetSRID(ST_MakePoint(:lon, :lat + 0.0007), 4326), '금남로',
                false, false, NULL, false,
                0.0, 2, '2026-06-30', true, NOW()
            )
            """),
            {"id": c2_id, "src_id": src_id, "lat": BASE_LAT, "lon": BASE_LON},
        )

        # 3. 222m 위치 횡단보도
        conn.execute(
            text("""
            INSERT INTO crossing (
                id, source_id, source_record_key, geom, road_name,
                pedestrian_signal, acoustic_signal, tactile_paving, curb_cut,
                published, valid_from
            ) VALUES (
                :id, :src_id, 'CW-API-03', ST_SetSRID(ST_MakePoint(:lon, :lat + 0.0020), 4326), '상무대로',
                true, true, true, true,
                true, NOW()
            )
            """),
            {"id": c3_id, "src_id": src_id, "lat": BASE_LAT, "lon": BASE_LON},
        )

        # 4. 미발행(published=false) 횡단보도 (검색 제외)
        conn.execute(
            text("""
            INSERT INTO crossing (
                id, source_id, source_record_key, geom, road_name, published, valid_from
            ) VALUES (
                :id, :src_id, 'CW-API-UNPUB', ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), '비공개로',
                false, NOW()
            )
            """),
            {"id": c_unpub_id, "src_id": src_id, "lat": BASE_LAT, "lon": BASE_LON},
        )

        # 5. 만료(valid_to IS NOT NULL) 횡단보도 (검색 제외)
        conn.execute(
            text("""
            INSERT INTO crossing (
                id, source_id, source_record_key, geom, road_name, published, valid_from, valid_to
            ) VALUES (
                :id, :src_id, 'CW-API-EXP', ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), '만료로',
                true, NOW() - INTERVAL '1 day', NOW()
            )
            """),
            {"id": c_exp_id, "src_id": src_id, "lat": BASE_LAT, "lon": BASE_LON},
        )


def test_01_nearby_radius_filtering():
    """
    수용 기준: 반경(10m~500m)에 따른 공간 필터링 검증
    """
    # 1. 50m 반경 조회: 기준점(c1)만 반환되어야 함
    res_50m = client.get(
        f"/v1/crossings/nearby?lat={BASE_LAT}&lon={BASE_LON}&radiusM=50"
    )
    assert res_50m.status_code == 200
    data_50m = res_50m.json()
    assert len(data_50m["items"]) == 1
    assert data_50m["items"][0]["source"]["sourceRecordId"] == "CW-API-01"

    # 2. 100m 반경 조회: c1과 c2(77m) 2건 반환
    res_100m = client.get(
        f"/v1/crossings/nearby?lat={BASE_LAT}&lon={BASE_LON}&radiusM=100"
    )
    assert res_100m.status_code == 200
    data_100m = res_100m.json()
    assert len(data_100m["items"]) == 2
    keys_100m = [item["source"]["sourceRecordId"] for item in data_100m["items"]]
    assert "CW-API-01" in keys_100m
    assert "CW-API-02" in keys_100m
    assert "CW-API-UNPUB" not in keys_100m
    assert "CW-API-EXP" not in keys_100m

    # 3. 300m 반경 조회: c1, c2, c3 3건 반환
    res_300m = client.get(
        f"/v1/crossings/nearby?lat={BASE_LAT}&lon={BASE_LON}&radiusM=300"
    )
    assert res_300m.status_code == 200
    data_300m = res_300m.json()
    assert len(data_300m["items"]) >= 3


def test_02_tri_state_boolean_null_and_false_distinction():
    """
    수용 기준: null과 false가 JSON에서 구분됨 (SR-F-022)
    미기재(null)와 명시적 없음(false)이 혼동되지 않고 정확히 직렬화되는지 검증
    """
    res = client.get(f"/v1/crossings/nearby?lat={BASE_LAT}&lon={BASE_LON}&radiusM=100")
    assert res.status_code == 200
    raw_text = res.text
    data = res.json()

    c2_item = next(
        it for it in data["items"] if it["source"]["sourceRecordId"] == "CW-API-02"
    )

    # c2의 acousticSignal은 명시적 false
    assert c2_item["acousticSignal"] is False
    # c2의 tactilePaving은 미기재 null
    assert c2_item["tactilePaving"] is None
    # rawFacility의 acousticSignal도 명시적 false
    assert c2_item["rawFacility"]["acousticSignal"] is False

    # 원문 JSON 텍스트 검증
    assert '"acousticSignal":false' in raw_text.replace(" ", "")
    assert '"tactilePaving":null' in raw_text.replace(" ", "")


def test_03_field_verification_projection_and_conflict_flag():
    """
    수용 기준: field verification projection과 원천 충돌 플래그를 명시
    미검증/오래된 값이 검증된 값처럼 표시되지 않음
    """
    res = client.get(f"/v1/crossings/nearby?lat={BASE_LAT}&lon={BASE_LON}&radiusM=50")
    assert res.status_code == 200
    item = res.json()["items"][0]

    # c1은 현장 검증 완료
    assert item["verification"]["status"] == "FIELD_VERIFIED"
    assert item["verification"]["verifiedBy"] == "op_safety_inspector"
    assert item["verification"]["verifiedAt"] is not None

    # 투영값: 현장 검증값 우선 (acoustic: raw None -> fv True, curb: raw True -> fv False)
    assert item["acousticSignal"] is True
    assert item["curbCut"] is False
    assert item["directionBearingDeg"] == 95.0

    # 원본 보존값 확인
    assert item["rawFacility"]["acousticSignal"] is None
    assert item["rawFacility"]["curbCut"] is True
    assert item["rawFacility"]["directionBearingDeg"] == 90.0

    # 원천 충돌 감지 플래그 확인
    assert item["verification"]["hasSourceConflict"] is True
    assert item["verification"]["conflictDetails"]["curbCut"] is True


def test_04_korea_bounding_box_and_parameter_validation():
    """
    수용 기준: lat/lon 대한민국 범위, radius 10~500m 검증 및 400 에러 envelope
    """
    # 1. 위도 범위 벗어남 (lat < 32.0)
    res_lat_low = client.get("/v1/crossings/nearby?lat=30.0&lon=126.85&radiusM=100")
    assert res_lat_low.status_code == 400
    err_lat = res_lat_low.json()["error"]
    assert err_lat["code"] == "VALIDATION_ERROR"
    assert err_lat["correlationId"] is not None

    # 2. 경도 범위 벗어남 (lon > 132.0)
    res_lon_high = client.get("/v1/crossings/nearby?lat=35.15&lon=135.0&radiusM=100")
    assert res_lon_high.status_code == 400
    assert res_lon_high.json()["error"]["code"] == "VALIDATION_ERROR"

    # 3. 반경 < 10m
    res_rad_low = client.get("/v1/crossings/nearby?lat=35.15&lon=126.85&radiusM=5")
    assert res_rad_low.status_code == 400

    # 4. 반경 > 500m
    res_rad_high = client.get("/v1/crossings/nearby?lat=35.15&lon=126.85&radiusM=600")
    assert res_rad_high.status_code == 400


def test_05_corridor_get_and_post_queries():
    """
    수용 기준: 경로 회랑(corridor) 조회 (GET & POST) 정상 동작 및 버퍼링 필터링
    """
    # 1. c1(35.1600, 126.8600)과 c2(35.1607, 126.8600)를 지나는 경로
    path_str = f"{BASE_LON},{BASE_LAT - 0.0005};{BASE_LON},{BASE_LAT + 0.0010}"

    # GET 요청
    res_get = client.get(f"/v1/crossings/corridor?path={path_str}&bufferM=30")
    assert res_get.status_code == 200
    data_get = res_get.json()
    assert len(data_get["items"]) >= 2

    # POST 요청 (동일한 LineString 본문)
    payload = {
        "type": "LineString",
        "coordinates": [
            [BASE_LON, BASE_LAT - 0.0005],
            [BASE_LON, BASE_LAT + 0.0010],
        ],
        "bufferM": 30.0,
    }
    res_post = client.post("/v1/crossings/corridor", json=payload)
    assert res_post.status_code == 200
    data_post = res_post.json()
    assert len(data_post["items"]) == len(data_get["items"])

    # 2. 멀리 떨어진 경로: 검색 결과 0건
    far_path = "126.8700,35.1800;126.8710,35.1810"
    res_far = client.get(f"/v1/crossings/corridor?path={far_path}&bufferM=30")
    assert res_far.status_code == 200
    assert len(res_far.json()["items"]) == 0


def test_06_corridor_dos_and_geometry_limits():
    """
    수용 기준: SQL injection / 과도한 geometry 방어
    - 정점 수 상한(500개 초과 시 차단)
    - 총 경로 길이 상한(10km 초과 시 차단)
    - 악의적 SQL injection 구문 차단
    """
    # 1. 단일 정점 (최소 2개 미충족)
    res_single = client.get(
        f"/v1/crossings/corridor?path={BASE_LON},{BASE_LAT}&bufferM=30"
    )
    assert res_single.status_code == 400

    # 2. 501개 정점 (500개 초과 과도한 geometry 방어)
    excessive_coords = [[BASE_LON + i * 0.00001, BASE_LAT] for i in range(502)]
    payload_excess = {
        "type": "LineString",
        "coordinates": excessive_coords,
        "bufferM": 30.0,
    }
    res_excess = client.post("/v1/crossings/corridor", json=payload_excess)
    assert res_excess.status_code == 400
    assert "500" in str(res_excess.json()["error"])

    # 3. 10km 초과 경로 (과도한 길이 방어)
    # 위도 0.11도 ~ 12.2km
    long_coords = [
        [BASE_LON, BASE_LAT],
        [BASE_LON, BASE_LAT + 0.1100],
    ]
    payload_long = {
        "type": "LineString",
        "coordinates": long_coords,
        "bufferM": 30.0,
    }
    res_long = client.post("/v1/crossings/corridor", json=payload_long)
    assert res_long.status_code == 400
    assert "10000" in str(res_long.json()["error"])

    # 4. SQL Injection 문자열 차단
    sql_inj = f"{BASE_LON},{BASE_LAT};{BASE_LON},{BASE_LAT + 0.001}' OR '1'='1"
    res_inj = client.get(f"/v1/crossings/corridor?path={sql_inj}")
    assert res_inj.status_code == 400
    assert res_inj.json()["error"]["code"] == "BAD_REQUEST"


def test_07_correlation_id_and_common_error_envelope():
    """
    수용 기준: correlationId 전파 및 공통 오류 envelope 포맷 검증
    """
    # 1. 커스텀 correlationId 전송 시 응답 헤더에 그대로 반환
    custom_cid = "safe-cross-client-cid-999"
    res = client.get(
        f"/v1/crossings/nearby?lat={BASE_LAT}&lon={BASE_LON}&radiusM=100",
        headers={"X-Correlation-ID": custom_cid},
    )
    assert res.status_code == 200
    assert res.headers.get("X-Correlation-ID") == custom_cid

    # 2. 미전송 시 서버에서 UUID 생성
    res_no_cid = client.get(
        f"/v1/crossings/nearby?lat={BASE_LAT}&lon={BASE_LON}&radiusM=100"
    )
    assert res_no_cid.status_code == 200
    generated_cid = res_no_cid.headers.get("X-Correlation-ID")
    assert generated_cid is not None
    assert len(generated_cid) == 36  # UUID 형식

    # 3. 404 에러 시 공통 오류 envelope 검증
    res_404 = client.get("/v1/crossings/non-existent-endpoint")
    assert res_404.status_code == 404
    err_body = res_404.json()["error"]
    assert err_body["code"] == "NOT_FOUND"
    assert err_body["messageKey"] == "error.not_found"
    assert err_body["correlationId"] is not None


def test_08_rate_limiting_enforcement():
    """
    수용 기준: rate limit (처리율 제한) 429 Too Many Requests 검증
    """
    test_limiter = InMemoryRateLimiter(max_requests_per_minute=3)
    test_key = "192.168.1.100"
    assert test_limiter.is_allowed(test_key)[0] is True
    assert test_limiter.is_allowed(test_key)[0] is True
    assert test_limiter.is_allowed(test_key)[0] is True

    # 4번째 요청 차단
    allowed, retry_after = test_limiter.is_allowed(test_key)
    assert allowed is False
    assert retry_after > 0
