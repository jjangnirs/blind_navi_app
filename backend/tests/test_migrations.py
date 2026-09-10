import os
import uuid
from datetime import datetime, timezone

import pytest
from alembic import command
from alembic.config import Config
from app.core.database import get_database_url
from sqlalchemy import create_engine, text
from sqlalchemy.exc import IntegrityError, SQLAlchemyError


@pytest.fixture(scope="session")
def db_engine():
    """Create engine and verify connection to PostGIS database."""
    url = get_database_url()
    engine = create_engine(url, pool_pre_ping=True)
    try:
        with engine.connect() as conn:
            # Check PostGIS extension
            result = conn.execute(text("SELECT 1")).scalar()
            assert result == 1
    except (SQLAlchemyError, OSError) as e:
        pytest.skip(f"PostGIS database not reachable at {url}. Error: {e}")
    return engine


@pytest.fixture(scope="session")
def alembic_config():
    """Load alembic configuration for migrations."""
    ini_path = os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "alembic.ini")
    )
    cfg = Config(ini_path)
    cfg.set_main_option(
        "script_location",
        os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "migrations")),
    )
    return cfg


def test_01_migration_upgrade_and_downgrade(db_engine, alembic_config):
    """
    수용 기준 1: 빈 DB upgrade head 성공 및 downgrade 검증
    """
    # 1. Downgrade to base first (clean slate)
    command.downgrade(alembic_config, "base")

    # Check tables do not exist
    with db_engine.connect() as conn:
        res = conn.execute(text("SELECT to_regclass('public.crossing');")).scalar()
        assert res is None

    # 2. Upgrade to head
    command.upgrade(alembic_config, "head")

    # Check tables exist
    with db_engine.connect() as conn:
        assert (
            conn.execute(text("SELECT to_regclass('public.source');")).scalar()
            is not None
        )
        assert (
            conn.execute(text("SELECT to_regclass('public.raw_record');")).scalar()
            is not None
        )
        assert (
            conn.execute(text("SELECT to_regclass('public.crossing');")).scalar()
            is not None
        )
        assert (
            conn.execute(text("SELECT to_regclass('public.signal_device');")).scalar()
            is not None
        )
        assert (
            conn.execute(
                text("SELECT to_regclass('public.crossing_signal_link');")
            ).scalar()
            is not None
        )
        assert (
            conn.execute(
                text("SELECT to_regclass('public.field_verification');")
            ).scalar()
            is not None
        )
        assert (
            conn.execute(
                text("SELECT to_regclass('public.v_active_crossing_display');")
            ).scalar()
            is not None
        )
        assert (
            conn.execute(text("SELECT to_regclass('public.user_report');")).scalar()
            is not None
        )
        assert (
            conn.execute(text("SELECT to_regclass('public.audit_event');")).scalar()
            is not None
        )


def test_02_seed_fixtures_and_tristate_boolean(db_engine):
    """
    수용 기준 2 & 4: seed fixture 삽입 성공 및 null/false/true 삼항 논리 구분
    """
    with db_engine.begin() as conn:
        # 1. Insert Source
        source_id = str(uuid.uuid4())
        conn.execute(
            text(
                """
                INSERT INTO source (id, name, provider, source_url)
                VALUES (:id, 'gwangju_std_crosswalk', '광주광역시', 'https://www.data.go.kr')
                """
            ),
            {"id": source_id},
        )

        # 2. Insert Raw Record
        raw_id = str(uuid.uuid4())
        conn.execute(
            text(
                """
                INSERT INTO raw_record (id, source_id, source_record_key, payload_json, payload_sha256)
                VALUES (:id, :source_id, 'CW-001', '{"lat": 35.1500, "lon": 126.8500}'::jsonb, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
                """
            ),
            {"id": raw_id, "source_id": source_id},
        )

        # 3. Insert Crossing with acoustic_signal = NULL (미기재)
        cw1_id = str(uuid.uuid4())
        conn.execute(
            text(
                """
                INSERT INTO crossing (
                    id, source_id, source_record_key, geom, road_name,
                    pedestrian_signal, acoustic_signal, tactile_paving, curb_cut,
                    published, quality_status, valid_from
                ) VALUES (
                    :id, :source_id, 'CW-001', ST_SetSRID(ST_MakePoint(126.8500, 35.1500), 4326), '상무대로',
                    true, NULL, true, true,
                    true, 'VALIDATED', now()
                )
                """
            ),
            {"id": cw1_id, "source_id": source_id},
        )

        # 4. Insert Crossing with acoustic_signal = FALSE (명시적 없음)
        cw2_id = str(uuid.uuid4())
        conn.execute(
            text(
                """
                INSERT INTO crossing (
                    id, source_id, source_record_key, geom, road_name,
                    pedestrian_signal, acoustic_signal, tactile_paving, curb_cut,
                    published, quality_status, valid_from
                ) VALUES (
                    :id, :source_id, 'CW-002', ST_SetSRID(ST_MakePoint(126.8505, 35.1503), 4326), '시청로',
                    true, false, true, false,
                    true, 'VALIDATED', now()
                )
                """
            ),
            {"id": cw2_id, "source_id": source_id},
        )

        # 5. Insert Crossing with acoustic_signal = TRUE (있음)
        cw3_id = str(uuid.uuid4())
        conn.execute(
            text(
                """
                INSERT INTO crossing (
                    id, source_id, source_record_key, geom, road_name,
                    pedestrian_signal, acoustic_signal, tactile_paving, curb_cut,
                    published, quality_status, valid_from
                ) VALUES (
                    :id, :source_id, 'CW-003', ST_SetSRID(ST_MakePoint(126.8520, 35.1510), 4326), '치평로',
                    true, true, true, true,
                    true, 'VALIDATED', now()
                )
                """
            ),
            {"id": cw3_id, "source_id": source_id},
        )

    # Verify tri-state values in DB
    with db_engine.connect() as conn:
        row1 = conn.execute(
            text(
                "SELECT acoustic_signal FROM crossing WHERE source_record_key = 'CW-001'"
            )
        ).scalar()
        row2 = conn.execute(
            text(
                "SELECT acoustic_signal FROM crossing WHERE source_record_key = 'CW-002'"
            )
        ).scalar()
        row3 = conn.execute(
            text(
                "SELECT acoustic_signal FROM crossing WHERE source_record_key = 'CW-003'"
            )
        ).scalar()

        assert row1 is None, "미기재 값은 NULL이어야 함"
        assert row2 is False, "명시적 없는 시설은 False여야 함"
        assert row3 is True, "설치된 시설은 True여야 함"


def test_03_st_dwithin_100m_spatial_query(db_engine):
    """
    수용 기준 3: ST_DWithin 100m 조회가 예상 레코드만 반환 (반경 내 45m 레코드 포함, 160m 레코드 제외)
    """
    # 기준점: 위도 35.1500, 경도 126.8500
    # CW-001: 126.8500, 35.1500 (거리 0m) -> 포함되어야 함
    # CW-002: 126.8505, 35.1503 (거리 약 55m) -> 포함되어야 함
    # CW-003: 126.8520, 35.1510 (거리 약 210m) -> 100m 쿼리에서 반드시 제외되어야 함
    query = text(
        """
        SELECT source_record_key,
               ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) AS dist_m
        FROM crossing
        WHERE published = TRUE
          AND valid_to IS NULL
          AND ST_DWithin(
                geom::geography,
                ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                :radius_m
              )
        ORDER BY dist_m ASC;
        """
    )

    with db_engine.connect() as conn:
        results = conn.execute(
            query, {"lon": 126.8500, "lat": 35.1500, "radius_m": 100}
        ).fetchall()
        keys = [r[0] for r in results]

        assert "CW-001" in keys, "0m 지점 횡단보도는 반경 100m 내에 포함되어야 함"
        assert "CW-002" in keys, "55m 지점 횡단보도는 반경 100m 내에 포함되어야 함"
        assert "CW-003" not in keys, (
            "210m 지점 횡단보도는 반경 100m 내에서 제외되어야 함"
        )


def test_04_scd_type2_history_preservation(db_engine):
    """
    수용 기준 5: 같은 원천키 새 기준일 적재 시 이전 이력 보존 (SCD Type 2)
    """
    with db_engine.begin() as conn:
        # Find source_id
        source_id = conn.execute(
            text("SELECT id FROM source WHERE name = 'gwangju_std_crosswalk'")
        ).scalar()

        # 1. 기존 CW-001 만료 처리 (valid_to = now())
        expire_time = datetime.now(timezone.utc)
        conn.execute(
            text(
                """
                UPDATE crossing
                SET valid_to = :expire_time
                WHERE source_id = :source_id
                  AND source_record_key = 'CW-001'
                  AND valid_to IS NULL
                """
            ),
            {"expire_time": expire_time, "source_id": source_id},
        )

        # 2. 새 기준일 데이터 삽입 (예: 차로수가 4차로에서 6차로로 변경됨)
        new_cw_id = str(uuid.uuid4())
        conn.execute(
            text(
                """
                INSERT INTO crossing (
                    id, source_id, source_record_key, geom, road_name,
                    pedestrian_signal, acoustic_signal, lane_count,
                    published, quality_status, valid_from
                ) VALUES (
                    :id, :source_id, 'CW-001', ST_SetSRID(ST_MakePoint(126.8500, 35.1500), 4326), '상무대로',
                    true, true, 6,
                    true, 'VALIDATED', :new_valid_from
                )
                """
            ),
            {"id": new_cw_id, "source_id": source_id, "new_valid_from": expire_time},
        )

    # 3. 검증: 동일한 source_record_key = 'CW-001'로 2개의 레코드가 존재해야 함
    with db_engine.connect() as conn:
        rows = conn.execute(
            text(
                """
                SELECT id, lane_count, valid_to IS NULL AS is_current
                FROM crossing
                WHERE source_record_key = 'CW-001'
                ORDER BY valid_from ASC
                """
            )
        ).fetchall()

        assert len(rows) == 2, "이전 버전과 새 버전 2개가 모두 보존되어야 함"
        assert rows[0][2] is False, (
            "첫 번째 레코드는 만료(valid_to IS NOT NULL) 상태여야 함"
        )
        assert rows[1][2] is True, (
            "두 번째 레코드는 현재 유효(valid_to IS NULL) 상태여야 함"
        )
        assert rows[1][1] == 6, "새 레코드의 변경된 속성(lane_count=6)이 반영되어야 함"


def test_05_constraints_and_validation(db_engine):
    """
    제약조건 무결성 검증: 잘못된 quality_status 또는 report_type 삽입 시 에러 발생
    """
    with db_engine.connect() as conn:
        source_id = conn.execute(text("SELECT id FROM source LIMIT 1")).scalar()

        # 1. Invalid quality_status
        with pytest.raises(IntegrityError), db_engine.begin() as trans_conn:
            trans_conn.execute(
                text(
                    """
                        INSERT INTO crossing (
                            source_id, source_record_key, geom, quality_status, published
                        ) VALUES (
                            :source_id, 'CW-INVALID', ST_SetSRID(ST_MakePoint(126.85, 35.15), 4326), 'INVALID_STATE', true
                        )
                        """
                ),
                {"source_id": source_id},
            )

        # 2. Invalid report_type
        with pytest.raises(IntegrityError), db_engine.begin() as trans_conn:
            trans_conn.execute(
                text(
                    """
                        INSERT INTO user_report (report_type, description)
                        VALUES ('MALICIOUS_TYPE', 'Invalid type test')
                        """
                )
            )


def test_06_field_verification_and_projection_view(db_engine):
    """
    요구사항 7 & SR-F-092: 현장 검증값이 원천값을 덮어쓰지 않고 투영 뷰에서 현장값 우선 투영
    """
    with db_engine.begin() as conn:
        # CW-002는 원천에서 acoustic_signal = false 임
        cw2 = conn.execute(
            text(
                "SELECT id, acoustic_signal FROM crossing WHERE source_record_key = 'CW-002' AND valid_to IS NULL"
            )
        ).fetchone()
        cw2_id = cw2[0]
        assert cw2[1] is False, "원천값은 false"

        # 현장 조사자가 점검하여 실제 음향신호기가 있음을 확인(true)하고 등록
        fv_id = str(uuid.uuid4())
        conn.execute(
            text(
                """
                INSERT INTO field_verification (
                    id, crossing_id, verified_by, acoustic_signal, notes
                ) VALUES (
                    :id, :crossing_id, '조사원A', true, '현장 확인 결과 음향신호기 정상 작동 확인'
                )
                """
            ),
            {"id": fv_id, "crossing_id": cw2_id},
        )

    # 검증 1: 원천 테이블 crossing의 값은 여전히 false로 불변 보존되어야 함
    with db_engine.connect() as conn:
        raw_val = conn.execute(
            text("SELECT acoustic_signal FROM crossing WHERE id = :id"), {"id": cw2_id}
        ).scalar()
        assert raw_val is False, (
            "현장 검증이 등록되어도 원천 crossing 테이블은 덮어써지지 않아야 함"
        )

        # 검증 2: 투영 뷰(v_active_crossing_display)에서는 현장값 true 및 원천값 false가 동시에 투영되어야 함
        view_row = conn.execute(
            text(
                """
                SELECT acoustic_signal, raw_acoustic_signal, is_field_verified, field_verified_by
                FROM v_active_crossing_display
                WHERE id = :id
                """
            ),
            {"id": cw2_id},
        ).fetchone()

        assert view_row[0] is True, "투영 뷰는 현장 검증값(true)을 우선 투영해야 함"
        assert view_row[1] is False, (
            "원천값(false)이 raw_acoustic_signal에 보존되어야 함"
        )
        assert view_row[2] is True, "is_field_verified 플래그가 True여야 함"
        assert view_row[3] == "조사원A", "검증자 정보가 포함되어야 함"
