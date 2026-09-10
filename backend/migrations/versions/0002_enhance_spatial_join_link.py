"""Enhance spatial join link for Safe Cross KR

Revision ID: 0002_enhance_spatial_join_link
Revises: 0001_initial_postgis_schema
Create Date: 2026-09-08 21:30:00.000000

요구사항 추적:
- SR-F-025, SR-F-028~029: 횡단보도-신호기 공간 연계, approach bearing 및 ai_allowed 격리
- DQ-006~011: 거리·도로명·방위각 근거 feature 보존, 복수/충돌 후보 검수 큐(REVIEW_REQUIRED) 격리
- TRD.md 7장: 공간조인 다대다 후보 및 운영자 검수 스키마
"""

from collections.abc import Sequence

import geoalchemy2
import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "0002_enhance_spatial_join_link"
down_revision: str | None = "0001_initial_postgis_schema"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # 1. Add distance_m column
    op.add_column(
        "crossing_signal_link",
        sa.Column("distance_m", sa.Numeric(precision=6, scale=2), nullable=True),
    )

    # 2. Add match_features JSONB column
    op.add_column(
        "crossing_signal_link",
        sa.Column(
            "match_features",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
    )

    # 3. Add ai_allowed boolean column (Default false for safety)
    op.add_column(
        "crossing_signal_link",
        sa.Column(
            "ai_allowed",
            sa.Boolean(),
            server_default=sa.text("false"),
            nullable=False,
        ),
    )

    # 4. Add crossing_start_geom & crossing_end_geom columns
    op.add_column(
        "crossing_signal_link",
        sa.Column(
            "crossing_start_geom",
            geoalchemy2.types.Geometry(
                geometry_type="POINT", srid=4326, spatial_index=False
            ),
            nullable=True,
        ),
    )
    op.add_column(
        "crossing_signal_link",
        sa.Column(
            "crossing_end_geom",
            geoalchemy2.types.Geometry(
                geometry_type="POINT", srid=4326, spatial_index=False
            ),
            nullable=True,
        ),
    )

    # 5. Update association_status check constraint to include AUTO_MATCHED and REVIEW_REQUIRED
    op.drop_constraint(
        "ck_crossing_signal_link_status", "crossing_signal_link", type_="check"
    )
    op.create_check_constraint(
        "ck_crossing_signal_link_status",
        "crossing_signal_link",
        "association_status IN ('CANDIDATE', 'AUTO_MATCHED', 'REVIEW_REQUIRED', 'MANUAL_VERIFIED', 'REJECTED')",
    )


def downgrade() -> None:
    # 1. Normalize AUTO_MATCHED or REVIEW_REQUIRED records to CANDIDATE to prevent CheckViolation
    op.execute(
        "UPDATE crossing_signal_link SET association_status = 'CANDIDATE' "
        "WHERE association_status NOT IN ('CANDIDATE', 'MANUAL_VERIFIED', 'REJECTED');"
    )

    # 2. Revert check constraint
    op.drop_constraint(
        "ck_crossing_signal_link_status", "crossing_signal_link", type_="check"
    )
    op.create_check_constraint(
        "ck_crossing_signal_link_status",
        "crossing_signal_link",
        "association_status IN ('CANDIDATE', 'MANUAL_VERIFIED', 'REJECTED')",
    )

    # 3. Drop added columns
    op.drop_column("crossing_signal_link", "crossing_end_geom")
    op.drop_column("crossing_signal_link", "crossing_start_geom")
    op.drop_column("crossing_signal_link", "ai_allowed")
    op.drop_column("crossing_signal_link", "match_features")
    op.drop_column("crossing_signal_link", "distance_m")
