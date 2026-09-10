import os

import pytest
from app.repositories.crossing_repo import (
    explain_corridor_query,
    explain_nearby_query,
)
from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError


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


def test_01_nearby_spatial_query_uses_gist_index(db_engine):
    """
    수용 기준: PostGIS explain에서 spatial index(idx_crossing_geom) 사용 확인
    반경 기반 검색 시 && ST_Expand 조건을 통해 GiST 인덱스 스캔이 유도되는지 검증
    """
    with db_engine.connect() as conn:
        plan_rows = explain_nearby_query(
            db=conn,
            lat=35.1500,
            lon=126.8500,
            radius_m=100.0,
        )

    plan_text = "\n".join(plan_rows)
    print("\n[Nearby Explain Plan]:\n", plan_text)

    # GiST 공간 인덱스(idx_crossing_geom) 사용 여부 확인
    assert "idx_crossing_geom" in plan_text, (
        f"실행 계획에 'idx_crossing_geom' 공간 인덱스가 포함되어야 합니다:\n{plan_text}"
    )


def test_02_corridor_spatial_query_uses_gist_index(db_engine):
    """
    수용 기준: 경로 회랑 검색 시 PostGIS explain에서 spatial index 사용 확인
    """
    coords = [
        [126.8500, 35.1500],
        [126.8510, 35.1510],
    ]
    with db_engine.connect() as conn:
        plan_rows = explain_corridor_query(
            db=conn,
            coordinates=coords,
            buffer_m=30.0,
        )

    plan_text = "\n".join(plan_rows)
    print("\n[Corridor Explain Plan]:\n", plan_text)

    assert "idx_crossing_geom" in plan_text, (
        f"회랑 쿼리 실행 계획에 'idx_crossing_geom' 공간 인덱스가 포함되어야 합니다:\n{plan_text}"
    )
