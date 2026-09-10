import uuid

from geoalchemy2 import Geometry
from sqlalchemy import (
    CHAR,
    Boolean,
    CheckConstraint,
    Column,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import ARRAY, JSONB, UUID

from app.core.database import Base


class Source(Base):
    __tablename__ = "source"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(Text, nullable=False, unique=True)
    provider = Column(Text, nullable=False)
    source_url = Column(Text, nullable=True)
    created_at = Column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class RawRecord(Base):
    __tablename__ = "raw_record"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    source_id = Column(
        UUID(as_uuid=True), ForeignKey("source.id", ondelete="CASCADE"), nullable=False
    )
    source_record_key = Column(Text, nullable=False)
    payload_json = Column(JSONB, nullable=False)
    payload_sha256 = Column(CHAR(64), nullable=False)
    ingested_at = Column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class Crossing(Base):
    __tablename__ = "crossing"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    source_id = Column(UUID(as_uuid=True), ForeignKey("source.id"), nullable=False)
    source_record_key = Column(Text, nullable=False)
    geom = Column(Geometry(geometry_type="POINT", srid=4326), nullable=False)
    road_name = Column(Text, nullable=True)

    # Tri-state booleans (True, False, None=미기재)
    pedestrian_signal = Column(Boolean, nullable=True)
    acoustic_signal = Column(Boolean, nullable=True)
    tactile_paving = Column(Boolean, nullable=True)
    curb_cut = Column(Boolean, nullable=True)
    traffic_island = Column(Boolean, nullable=True)

    lane_count = Column(Integer, nullable=True)
    green_seconds = Column(Integer, nullable=True)
    red_seconds = Column(Integer, nullable=True)
    direction_bearing_deg = Column(Numeric, nullable=True)
    data_reference_date = Column(Date, nullable=True)

    quality_status = Column(Text, default="RAW_INGESTED", nullable=False)
    field_verified_at = Column(DateTime(timezone=True), nullable=True)
    published = Column(Boolean, default=False, nullable=False)

    # SCD Type 2 유효기간 이력 관리
    valid_from = Column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    valid_to = Column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        UniqueConstraint(
            "source_id",
            "source_record_key",
            "valid_from",
            name="uq_crossing_source_key_valid",
        ),
        CheckConstraint(
            "quality_status IN ('RAW_INGESTED', 'VALIDATED', 'SUSPICIOUS', 'QUARANTINED', 'VERIFIED')",
            name="ck_crossing_quality_status",
        ),
    )


class SignalDevice(Base):
    __tablename__ = "signal_device"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    source_id = Column(UUID(as_uuid=True), ForeignKey("source.id"), nullable=False)
    source_record_key = Column(Text, nullable=False)
    geom = Column(Geometry(geometry_type="POINT", srid=4326), nullable=False)
    signal_type = Column(Text, nullable=True)

    acoustic_signal = Column(Boolean, nullable=True)
    button_signal = Column(Boolean, nullable=True)
    countdown_timer = Column(Boolean, nullable=True)
    data_reference_date = Column(Date, nullable=True)

    quality_status = Column(Text, default="RAW_INGESTED", nullable=False)
    published = Column(Boolean, default=False, nullable=False)
    valid_from = Column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    valid_to = Column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        UniqueConstraint(
            "source_id",
            "source_record_key",
            "valid_from",
            name="uq_signal_device_source_key_valid",
        ),
        CheckConstraint(
            "quality_status IN ('RAW_INGESTED', 'VALIDATED', 'SUSPICIOUS', 'QUARANTINED', 'VERIFIED')",
            name="ck_signal_device_quality_status",
        ),
    )


class CrossingSignalLink(Base):
    __tablename__ = "crossing_signal_link"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    crossing_id = Column(
        UUID(as_uuid=True),
        ForeignKey("crossing.id", ondelete="CASCADE"),
        nullable=False,
    )
    signal_device_id = Column(
        UUID(as_uuid=True),
        ForeignKey("signal_device.id", ondelete="CASCADE"),
        nullable=False,
    )
    approach_bearing_deg = Column(Numeric, nullable=False)

    distance_m = Column(Numeric(precision=6, scale=2), nullable=True)
    match_features = Column(JSONB, default=dict, nullable=False)
    ai_allowed = Column(Boolean, default=False, nullable=False)

    crossing_start_geom = Column(
        Geometry(geometry_type="POINT", srid=4326, spatial_index=False), nullable=True
    )
    crossing_end_geom = Column(
        Geometry(geometry_type="POINT", srid=4326, spatial_index=False), nullable=True
    )

    provider_intersection_id = Column(Text, nullable=True)
    provider_movement_id = Column(Text, nullable=True)
    association_status = Column(Text, default="CANDIDATE", nullable=False)
    evidence_uri = Column(Text, nullable=True)
    verified_by = Column(Text, nullable=True)
    verified_at = Column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        UniqueConstraint(
            "crossing_id",
            "approach_bearing_deg",
            "signal_device_id",
            name="uq_crossing_signal_link",
        ),
        CheckConstraint(
            "association_status IN ('CANDIDATE', 'AUTO_MATCHED', 'REVIEW_REQUIRED', 'MANUAL_VERIFIED', 'REJECTED')",
            name="ck_crossing_signal_link_status",
        ),
    )


class FieldVerification(Base):
    __tablename__ = "field_verification"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    crossing_id = Column(
        UUID(as_uuid=True),
        ForeignKey("crossing.id", ondelete="CASCADE"),
        nullable=False,
    )
    verified_by = Column(Text, nullable=False)
    verified_at = Column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    acoustic_signal = Column(Boolean, nullable=True)
    tactile_paving = Column(Boolean, nullable=True)
    curb_cut = Column(Boolean, nullable=True)
    direction_bearing_deg = Column(Numeric, nullable=True)
    notes = Column(Text, nullable=True)
    evidence_uris = Column(ARRAY(Text), nullable=True)


class UserReport(Base):
    __tablename__ = "user_report"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    crossing_id = Column(
        UUID(as_uuid=True),
        ForeignKey("crossing.id", ondelete="SET NULL"),
        nullable=True,
    )
    report_type = Column(Text, nullable=False)
    description = Column(Text, nullable=True)
    status = Column(Text, default="PENDING", nullable=False)
    created_at = Column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    resolved_at = Column(DateTime(timezone=True), nullable=True)
    resolved_by = Column(Text, nullable=True)

    __table_args__ = (
        CheckConstraint(
            "report_type IN ('MISSING_FACILITY', 'BROKEN_ACOUSTIC', 'LOCATION_ERROR', 'ATTRIBUTE_ERROR', 'FALSE_GREEN_SUSPECT', 'OTHER')",
            name="ck_user_report_type",
        ),
        CheckConstraint(
            "status IN ('PENDING', 'INVESTIGATING', 'RESOLVED', 'REJECTED')",
            name="ck_user_report_status",
        ),
    )


class AuditEvent(Base):
    __tablename__ = "audit_event"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    entity_type = Column(Text, nullable=False)
    entity_id = Column(Text, nullable=False)
    action = Column(Text, nullable=False)
    actor = Column(Text, nullable=False)
    before_state = Column(JSONB, nullable=True)
    after_state = Column(JSONB, nullable=True)
    reason = Column(Text, nullable=True)
    created_at = Column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
