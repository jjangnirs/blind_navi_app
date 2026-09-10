import json
import os

import pytest
from safecross_pipeline.ingest import ingest_file
from safecross_pipeline.parsers.boolean_parser import (
    InvalidBooleanError,
    parse_tristate_boolean,
)
from safecross_pipeline.parsers.hasher import compute_file_sha256
from safecross_pipeline.quality.validator import ValidationError, validate_coordinates
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures")
CROSSWALK_FIXTURE = os.path.join(FIXTURES_DIR, "crosswalk_sample_utf8sig.csv")
TRAFFIC_LIGHT_FIXTURE = os.path.join(FIXTURES_DIR, "traffic_light_sample_cp949.csv")


@pytest.fixture(scope="session")
def db_engine():
    """Optional DB engine for integration tests if PostGIS is running."""
    db_url = os.getenv(
        "DATABASE_URL",
        "postgresql+psycopg://safecross:safecross_local_dev_only@localhost:5432/safecross",
    )
    engine = create_engine(db_url, pool_pre_ping=True)
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
    except (SQLAlchemyError, OSError) as e:
        pytest.skip(f"PostGIS database not reachable at {db_url}. Error: {e}")
    return engine


def test_01_parse_both_fixture_formats():
    """
    수용 기준 1: UTF-8-SIG 및 CP949 두 fixture 형식 정상 파싱 검증
    """
    # 1. UTF-8-SIG crosswalk
    cw_report = ingest_file("crosswalk", CROSSWALK_FIXTURE, "2026-09-08T12:00:00Z")
    assert cw_report.encoding == "utf-8-sig"
    assert cw_report.total_rows == 7
    assert cw_report.valid_count == 3
    assert cw_report.quarantined_count == 4

    # 2. CP949 traffic-light
    tl_report = ingest_file(
        "traffic-light", TRAFFIC_LIGHT_FIXTURE, "2026-09-08T12:00:00Z"
    )
    assert tl_report.encoding == "cp949"
    assert tl_report.total_rows == 5
    assert tl_report.valid_count == 3
    assert tl_report.quarantined_count == 2


def test_02_empty_values_remain_null_not_false():
    """
    수용 기준 2: 공란이 false로 바뀌지 않고 None(null)으로 엄격 보존 검증
    """
    # 1. boolean_parser 단위 검증
    assert parse_tristate_boolean("") is None, "공란은 None이어야 함"
    assert parse_tristate_boolean(" ") is None, "공백은 None이어야 함"
    assert parse_tristate_boolean(None) is None, "None은 None이어야 함"
    assert parse_tristate_boolean("N") is False, "'N'은 False여야 함"
    assert parse_tristate_boolean("Y") is True, "'Y'는 True여야 함"

    # 2. 파이프라인 인제스트 실행 결과 검증
    report = ingest_file("crosswalk", CROSSWALK_FIXTURE, "2026-09-08T12:00:00Z")
    # CW-GJ-003은 음향신호기설치여부가 공란('')인 정상 행 (valid_count에 포함)
    assert report.valid_count == 3


def test_03_swapped_and_out_of_bounds_coordinates_quarantined():
    """
    수용 기준 3: 위경도 뒤바뀜 및 범위 밖 좌표 격리 검증
    """
    # 1. 위경도 뒤바뀜 단위 검증: lat=126.85, lon=35.15
    with pytest.raises(ValidationError) as exc_info:
        validate_coordinates(126.8500, 35.1500)
    assert exc_info.value.reason == "COORDINATES_SWAPPED"

    # 2. 범위 밖 위도 단위 검증: lat=42.50
    with pytest.raises(ValidationError) as exc_info:
        validate_coordinates(42.5000, 127.0000)
    assert exc_info.value.reason == "COORDINATES_OUT_OF_BOUNDS"

    # 3. 인제스트 리포트 내 격리 사유 검증
    report = ingest_file("crosswalk", CROSSWALK_FIXTURE, "2026-09-08T12:00:00Z")
    reasons = {d["record_key"]: d["reason"] for d in report.quarantine_details}
    assert reasons["CW-GJ-005"] == "COORDINATES_SWAPPED"
    assert reasons["CW-GJ-006"] == "COORDINATES_OUT_OF_BOUNDS"


def test_04_unknown_boolean_quarantined():
    """
    미지의 불리언 값('UNKNOWN', '세모' 등) 격리 검증
    """
    with pytest.raises(InvalidBooleanError):
        parse_tristate_boolean("UNKNOWN")

    report = ingest_file("crosswalk", CROSSWALK_FIXTURE, "2026-09-08T12:00:00Z")
    reasons = {d["record_key"]: d["reason"] for d in report.quarantine_details}
    assert reasons["CW-GJ-004"] == "INVALID_BOOLEAN_VALUE"


def test_05_original_file_hash_immutable():
    """
    수용 기준 5: 원본 파일 해시 불변 검증
    """
    before_hash = compute_file_sha256(CROSSWALK_FIXTURE)
    ingest_file("crosswalk", CROSSWALK_FIXTURE, "2026-09-08T12:00:00Z")
    after_hash = compute_file_sha256(CROSSWALK_FIXTURE)

    assert before_hash == after_hash, (
        "인제스트 실행 후 원본 파일 해시가 절대 변경되면 안 됨"
    )


def test_06_json_report_generation(tmp_path):
    """
    정상/격리/중복/결측 보고서 JSON 생성 검증
    """
    report_file = tmp_path / "report.json"
    report = ingest_file("crosswalk", CROSSWALK_FIXTURE, "2026-09-08T12:00:00Z")
    json_str = report.to_json()

    with open(report_file, "w", encoding="utf-8") as f:
        f.write(json_str)

    # Re-read and assert valid JSON
    with open(report_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    assert data["source_type"] == "crosswalk"
    assert data["total_rows"] == 7
    assert data["valid_count"] == 3
    assert data["quarantined_count"] == 4
    assert data["duplicate_count"] == 1
    assert len(data["quarantine_details"]) == 4


def test_07_idempotent_ingestion_in_database(db_engine):
    """
    수용 기준 4 & 6: 동일 실행 재처리(2회 인제스트) 시 published 중복 없음 (Idempotency) 검증
    """
    # Clean previous records for test
    with db_engine.begin() as conn:
        conn.execute(
            text("DELETE FROM crossing WHERE source_record_key LIKE 'CW-GJ-%'")
        )
        conn.execute(
            text("DELETE FROM raw_record WHERE source_record_key LIKE 'CW-GJ-%'")
        )

    # 1st Ingestion
    ingest_file(
        "crosswalk", CROSSWALK_FIXTURE, "2026-09-08T12:00:00Z", db_engine=db_engine
    )

    with db_engine.connect() as conn:
        count_1 = conn.execute(
            text(
                "SELECT count(*) FROM crossing WHERE source_record_key LIKE 'CW-GJ-%' AND published = TRUE AND valid_to IS NULL"
            )
        ).scalar()
        assert count_1 == 3, "첫 번째 실행 시 3개의 유효 횡단보도가 적재되어야 함"

        # CW-GJ-003의 acoustic_signal이 NULL인지 검증 (공란이 false로 바뀌지 않음)
        cw3_acoustic = conn.execute(
            text(
                "SELECT acoustic_signal FROM crossing WHERE source_record_key = 'CW-GJ-003' AND valid_to IS NULL"
            )
        ).scalar()
        assert cw3_acoustic is None, (
            "CW-GJ-003의 공란 음향신호기는 DB에서도 NULL이어야 함"
        )

    # 2nd Ingestion (동일 파일 재실행)
    ingest_file(
        "crosswalk", CROSSWALK_FIXTURE, "2026-09-08T12:00:00Z", db_engine=db_engine
    )

    with db_engine.connect() as conn:
        count_2 = conn.execute(
            text(
                "SELECT count(*) FROM crossing WHERE source_record_key LIKE 'CW-GJ-%' AND published = TRUE AND valid_to IS NULL"
            )
        ).scalar()
        assert count_2 == 3, (
            "동일 파일 재실행 후에도 published 중복 없이 정확히 3개여야 함 (Idempotency)"
        )


def test_08_korean_boolean_variants():
    """
    잔여 위험 1 해결 검증: 한국 공공데이터 다빈도 표기(유/무, O/X, 설치/미설치, 있음/없음 등) 파싱 검증
    """
    # True variants
    assert parse_tristate_boolean("유") is True
    assert parse_tristate_boolean("O") is True
    assert parse_tristate_boolean("설치") is True
    assert parse_tristate_boolean("있음") is True
    assert parse_tristate_boolean("동작") is True
    assert parse_tristate_boolean("true") is True

    # False variants
    assert parse_tristate_boolean("무") is False
    assert parse_tristate_boolean("X") is False
    assert parse_tristate_boolean("미설치") is False
    assert parse_tristate_boolean("없음") is False
    assert parse_tristate_boolean("미동작") is False
    assert parse_tristate_boolean("false") is False

    # Null variants
    assert parse_tristate_boolean("-") is None
    assert parse_tristate_boolean(".") is None
    assert parse_tristate_boolean("미기재") is None
    assert parse_tristate_boolean("해당없음") is None

    # Unknown / ambiguous values must raise InvalidBooleanError for quarantine
    with pytest.raises(InvalidBooleanError):
        parse_tristate_boolean("점검필요")
    with pytest.raises(InvalidBooleanError):
        parse_tristate_boolean("파손")
    with pytest.raises(InvalidBooleanError):
        parse_tristate_boolean("확인불가")


def test_09_column_name_variants(tmp_path):
    """
    잔여 위험 2 해결 검증: 전국 지자체 표준데이터 열 이름 변종 수용성 검증
    """
    csv_file = tmp_path / "custom_columns.csv"
    # 전국 지자체 변종 열 이름 사용
    csv_content = (
        "횡단보도번호,소재지 도로명주소,소재지지번주소,LATITUDE,LONGITUDE,차선수,보행자신호등설치여부,시각장애인용음향신호기설치여부,연석단차턱낮춤여부,점자블록설치여부,기준일자\n"
        "CW-VAR-001,광주광역시 북구 첨단과기로 10,광주광역시 북구 오룡동 111,35.2200,126.8400,6,설치,설치,설치,설치,2026-06-30\n"
    )
    csv_file.write_text(csv_content, encoding="utf-8")

    report = ingest_file("crosswalk", str(csv_file), "2026-09-08T12:00:00Z")
    assert report.total_rows == 1
    assert report.valid_count == 1
    assert report.quarantined_count == 0


def test_10_lot_address_and_spatial_borough_fallback():
    """
    잔여 위험 3 해결 검증: 도로명주소 -> 지번주소 -> 좌표 공간 바운딩 박스 Fallback 및 광역시도 정규화
    """
    from safecross_pipeline.quality.administrative_alias import (
        normalize_region_name,
        resolve_administrative_division,
    )

    # 1. 광역 시·도 정규화 (원천명 보존)
    canonical, raw = normalize_region_name("서울시")
    assert canonical == "서울특별시"
    assert raw == "서울시"

    canonical, raw = normalize_region_name("강원도")
    assert canonical == "강원특별자치도"

    # 2. 도로명주소 기반 매핑
    div1 = resolve_administrative_division(
        road_address="광주광역시 동구 금남로 1",
        lot_address="광주광역시 동구 금남로1가 1",
    )
    assert div1["sido"] == "광주광역시"
    assert div1["sigungu"] == "동구"
    assert div1["method"] == "ROAD_ADDRESS"
    assert div1["is_estimated"] is False

    # 3. 지번주소만 있는 경우 Fallback
    div2 = resolve_administrative_division(
        road_address=None,
        lot_address="광주광역시 남구 백운동 200",
    )
    assert div2["sido"] == "광주광역시"
    assert div2["sigungu"] == "남구"
    assert div2["method"] == "LOT_ADDRESS"
    assert div2["is_estimated"] is False

    # 4. 주소가 모두 누락된 경우 좌표(서구 상무지구 부근) 기반 자치구 추정 Fallback
    div3 = resolve_administrative_division(
        road_address=None,
        lot_address=None,
        lat=35.1520,
        lon=126.8600,
    )
    assert div3["sido"] == "광주광역시"
    assert div3["sigungu"] == "서구"
    assert div3["method"] == "COORDINATES_FALLBACK"
    assert div3["is_estimated"] is True
