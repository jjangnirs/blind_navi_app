"""Initial PostGIS schema for Safe Cross KR

Revision ID: 0001_initial_postgis_schema
Revises:
Create Date: 2026-09-08 20:15:00.000000

요구사항 추적:
- SR-F-020~027: 공간 조회(ST_DWithin), 삼항 논리 boolean(null vs false), 이력 보존(SCD Type 2)
- SR-F-028~029: 방향별 crossing_signal_link 분리 및 수동 검수 상태
- SR-F-092~094: 현장 검증 원천값 보존, user_report, audit_event
- SR-NF-026: 운영자 감사 로그
- TRD.md 6장: 데이터 모델 설계
"""

from collections.abc import Sequence

import geoalchemy2
import sqlalchemy as sa
from alembic import op

# revision identifiers, used by Alembic.
revision: str = "0001_initial_postgis_schema"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # 1. PostGIS Extension
    op.execute("CREATE EXTENSION IF NOT EXISTS postgis;")

    # 2. source (공공데이터 및 공급자 원천)
    op.create_table(
        "source",
        sa.Column(
            "id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False
        ),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("provider", sa.Text(), nullable=False),
        sa.Column("source_url", sa.Text(), nullable=True),
        sa.Column(
            "created_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("name", name="uq_source_name"),
    )

    # 3. raw_record (원천 데이터 불변 저장 및 SHA-256 감사)
    op.create_table(
        "raw_record",
        sa.Column(
            "id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False
        ),
        sa.Column("source_id", sa.UUID(), nullable=False),
        sa.Column("source_record_key", sa.Text(), nullable=False),
        sa.Column("payload_json", sa.dialects.postgresql.JSONB(), nullable=False),
        sa.Column("payload_sha256", sa.CHAR(64), nullable=False),
        sa.Column(
            "ingested_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["source_id"], ["source.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "idx_raw_record_source_key", "raw_record", ["source_id", "source_record_key"]
    )

    # 4. crossing (횡단보도 정적 시설 엔터티)
    op.create_table(
        "crossing",
        sa.Column(
            "id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False
        ),
        sa.Column("source_id", sa.UUID(), nullable=False),
        sa.Column("source_record_key", sa.Text(), nullable=False),
        sa.Column(
            "geom",
            geoalchemy2.Geometry(
                geometry_type="POINT",
                srid=4326,
                spatial_index=False,
                from_text="ST_GeomFromEWKT",
                name="geometry",
            ),
            nullable=False,
        ),
        sa.Column("road_name", sa.Text(), nullable=True),
        # Tri-state boolean (True / False / NULL=미기재)
        sa.Column("pedestrian_signal", sa.Boolean(), nullable=True),
        sa.Column("acoustic_signal", sa.Boolean(), nullable=True),
        sa.Column("tactile_paving", sa.Boolean(), nullable=True),
        sa.Column("curb_cut", sa.Boolean(), nullable=True),
        sa.Column("traffic_island", sa.Boolean(), nullable=True),
        sa.Column("lane_count", sa.Integer(), nullable=True),
        sa.Column("green_seconds", sa.Integer(), nullable=True),
        sa.Column("red_seconds", sa.Integer(), nullable=True),
        sa.Column("direction_bearing_deg", sa.Numeric(), nullable=True),
        sa.Column("data_reference_date", sa.Date(), nullable=True),
        sa.Column(
            "quality_status", sa.Text(), server_default="RAW_INGESTED", nullable=False
        ),
        sa.Column("field_verified_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column(
            "published", sa.Boolean(), server_default=sa.text("false"), nullable=False
        ),
        # SCD Type 2 유효기간 이력 관리
        sa.Column(
            "valid_from",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("valid_to", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["source_id"], ["source.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "source_id",
            "source_record_key",
            "valid_from",
            name="uq_crossing_source_key_valid",
        ),
        sa.CheckConstraint(
            "quality_status IN ('RAW_INGESTED', 'VALIDATED', 'SUSPICIOUS', 'QUARANTINED', 'VERIFIED')",
            name="ck_crossing_quality_status",
        ),
    )
    # GiST 공간 인덱스 생성
    op.create_index("idx_crossing_geom", "crossing", ["geom"], postgresql_using="gist")
    op.create_index(
        "idx_crossing_active",
        "crossing",
        ["source_id", "source_record_key"],
        postgresql_where=sa.text("valid_to IS NULL"),
    )

    # 5. signal_device (신호등 및 음향신호기 장치)
    op.create_table(
        "signal_device",
        sa.Column(
            "id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False
        ),
        sa.Column("source_id", sa.UUID(), nullable=False),
        sa.Column("source_record_key", sa.Text(), nullable=False),
        sa.Column(
            "geom",
            geoalchemy2.Geometry(
                geometry_type="POINT",
                srid=4326,
                spatial_index=False,
                from_text="ST_GeomFromEWKT",
                name="geometry",
            ),
            nullable=False,
        ),
        sa.Column("signal_type", sa.Text(), nullable=True),
        sa.Column("acoustic_signal", sa.Boolean(), nullable=True),
        sa.Column("button_signal", sa.Boolean(), nullable=True),
        sa.Column("countdown_timer", sa.Boolean(), nullable=True),
        sa.Column("data_reference_date", sa.Date(), nullable=True),
        sa.Column(
            "quality_status", sa.Text(), server_default="RAW_INGESTED", nullable=False
        ),
        sa.Column(
            "published", sa.Boolean(), server_default=sa.text("false"), nullable=False
        ),
        sa.Column(
            "valid_from",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("valid_to", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["source_id"], ["source.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "source_id",
            "source_record_key",
            "valid_from",
            name="uq_signal_device_source_key_valid",
        ),
        sa.CheckConstraint(
            "quality_status IN ('RAW_INGESTED', 'VALIDATED', 'SUSPICIOUS', 'QUARANTINED', 'VERIFIED')",
            name="ck_signal_device_quality_status",
        ),
    )
    op.create_index(
        "idx_signal_device_geom", "signal_device", ["geom"], postgresql_using="gist"
    )

    # 6. crossing_signal_link (방향별 횡단보도-신호 연결표)
    op.create_table(
        "crossing_signal_link",
        sa.Column(
            "id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False
        ),
        sa.Column("crossing_id", sa.UUID(), nullable=False),
        sa.Column("signal_device_id", sa.UUID(), nullable=False),
        sa.Column("approach_bearing_deg", sa.Numeric(), nullable=False),
        sa.Column("provider_intersection_id", sa.Text(), nullable=True),
        sa.Column("provider_movement_id", sa.Text(), nullable=True),
        sa.Column(
            "association_status", sa.Text(), server_default="CANDIDATE", nullable=False
        ),
        sa.Column("evidence_uri", sa.Text(), nullable=True),
        sa.Column("verified_by", sa.Text(), nullable=True),
        sa.Column("verified_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["crossing_id"], ["crossing.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["signal_device_id"], ["signal_device.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "crossing_id",
            "approach_bearing_deg",
            "signal_device_id",
            name="uq_crossing_signal_link",
        ),
        sa.CheckConstraint(
            "association_status IN ('CANDIDATE', 'MANUAL_VERIFIED', 'REJECTED')",
            name="ck_crossing_signal_link_status",
        ),
    )

    # 7. field_verification (현장 검증값 - 원천값을 덮어쓰지 않고 별도 보존)
    op.create_table(
        "field_verification",
        sa.Column(
            "id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False
        ),
        sa.Column("crossing_id", sa.UUID(), nullable=False),
        sa.Column("verified_by", sa.Text(), nullable=False),
        sa.Column(
            "verified_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("acoustic_signal", sa.Boolean(), nullable=True),
        sa.Column("tactile_paving", sa.Boolean(), nullable=True),
        sa.Column("curb_cut", sa.Boolean(), nullable=True),
        sa.Column("direction_bearing_deg", sa.Numeric(), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column(
            "evidence_uris", sa.dialects.postgresql.ARRAY(sa.Text()), nullable=True
        ),
        sa.ForeignKeyConstraint(["crossing_id"], ["crossing.id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "idx_field_verification_crossing",
        "field_verification",
        ["crossing_id", "verified_at"],
    )

    # 8. v_active_crossing_display (투영 뷰 - 현장 검증값 우선 투영, 원천값 및 이력 보존)
    op.execute(
        """
        CREATE VIEW v_active_crossing_display AS
        SELECT
            c.id,
            c.source_id,
            c.source_record_key,
            c.geom,
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
            c.quality_status,
            c.published,
            c.valid_from,
            -- 원천값 보존 필드
            c.acoustic_signal AS raw_acoustic_signal,
            c.tactile_paving AS raw_tactile_paving,
            c.curb_cut AS raw_curb_cut,
            -- 현장 검증 메타데이터
            fv.verified_at AS field_verified_at,
            fv.verified_by AS field_verified_by,
            (fv.id IS NOT NULL) AS is_field_verified
        FROM crossing c
        LEFT JOIN LATERAL (
            SELECT *
            FROM field_verification
            WHERE crossing_id = c.id
            ORDER BY verified_at DESC
            LIMIT 1
        ) fv ON TRUE
        WHERE c.published = TRUE
          AND c.valid_to IS NULL;
        """
    )

    # 9. user_report (사용자 시설 오류 신고)
    op.create_table(
        "user_report",
        sa.Column(
            "id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False
        ),
        sa.Column("crossing_id", sa.UUID(), nullable=True),
        sa.Column("report_type", sa.Text(), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("status", sa.Text(), server_default="PENDING", nullable=False),
        sa.Column(
            "created_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.Column("resolved_at", sa.TIMESTAMP(timezone=True), nullable=True),
        sa.Column("resolved_by", sa.Text(), nullable=True),
        sa.ForeignKeyConstraint(["crossing_id"], ["crossing.id"], ondelete="SET NULL"),
        sa.PrimaryKeyConstraint("id"),
        sa.CheckConstraint(
            "report_type IN ('MISSING_FACILITY', 'BROKEN_ACOUSTIC', 'LOCATION_ERROR', 'ATTRIBUTE_ERROR', 'FALSE_GREEN_SUSPECT', 'OTHER')",
            name="ck_user_report_type",
        ),
        sa.CheckConstraint(
            "status IN ('PENDING', 'INVESTIGATING', 'RESOLVED', 'REJECTED')",
            name="ck_user_report_status",
        ),
    )

    # 10. audit_event (운영자 감사 로그)
    op.create_table(
        "audit_event",
        sa.Column(
            "id", sa.UUID(), server_default=sa.text("gen_random_uuid()"), nullable=False
        ),
        sa.Column("entity_type", sa.Text(), nullable=False),
        sa.Column("entity_id", sa.Text(), nullable=False),
        sa.Column("action", sa.Text(), nullable=False),
        sa.Column("actor", sa.Text(), nullable=False),
        sa.Column("before_state", sa.dialects.postgresql.JSONB(), nullable=True),
        sa.Column("after_state", sa.dialects.postgresql.JSONB(), nullable=True),
        sa.Column("reason", sa.Text(), nullable=True),
        sa.Column(
            "created_at",
            sa.TIMESTAMP(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "idx_audit_event_entity", "audit_event", ["entity_type", "entity_id"]
    )


def downgrade() -> None:
    """
    [WARNING: CRITICAL DATA LOSS RISK]
    본 downgrade를 실행하면 모든 공간 시설(crossing), 신호 장치(signal_device),
    현장 검증 내역(field_verification), 사용자 신고(user_report) 및 감사 이력(audit_event)이
    영구적으로 삭제됩니다. 운영 환경에서는 데이터 백업 없이 실행하지 마십시오.
    """
    op.execute("DROP VIEW IF EXISTS v_active_crossing_display;")
    op.drop_table("audit_event")
    op.drop_table("user_report")
    op.drop_table("field_verification")
    op.drop_table("crossing_signal_link")
    op.drop_table("signal_device")
    op.drop_table("crossing")
    op.drop_table("raw_record")
    op.drop_table("source")
