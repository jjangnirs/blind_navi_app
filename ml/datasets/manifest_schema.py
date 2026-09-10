"""Dataset Manifest Schema and Validation Models.

Defines the contract for dataset metadata manifests (source, license, consent,
site, device, time, weather, labels, hash, split).
Raw image/video media files are kept locally and never committed to Git.
"""

from datetime import datetime
from enum import Enum

from pydantic import BaseModel, Field, field_validator


class WeatherCondition(str, Enum):
    CLEAR = "CLEAR"
    RAIN = "RAIN"
    SNOW = "SNOW"
    OVERCAST = "OVERCAST"


class LightingCondition(str, Enum):
    DAYLIGHT = "DAYLIGHT"
    DUSK = "DUSK"
    NIGHT = "NIGHT"
    STRONG_BACKLIGHT = "STRONG_BACKLIGHT"


class SplitType(str, Enum):
    TRAIN = "TRAIN"
    VAL = "VAL"
    TEST = "TEST"


class StandardLabel(str, Enum):
    PEDESTRIAN_SIGNAL_RED = "PEDESTRIAN_SIGNAL_RED"
    PEDESTRIAN_SIGNAL_GREEN = "PEDESTRIAN_SIGNAL_GREEN"
    PEDESTRIAN_SIGNAL_FLASHING_GREEN = "PEDESTRIAN_SIGNAL_FLASHING_GREEN"
    PEDESTRIAN_SIGNAL_OFF = "PEDESTRIAN_SIGNAL_OFF"
    VEHICLE_SIGNAL = "VEHICLE_SIGNAL"
    BICYCLE_SIGNAL = "BICYCLE_SIGNAL"
    AMBIGUOUS_SIGNAL = "AMBIGUOUS_SIGNAL"
    HARD_NEGATIVE_LIGHT = "HARD_NEGATIVE_LIGHT"
    CROSSWALK_MASK = "CROSSWALK_MASK"
    CROSSWALK_ENTRANCE = "CROSSWALK_ENTRANCE"
    CROSSWALK_DIRECTION = "CROSSWALK_DIRECTION"
    TARGET_SIGNAL_LINK = "TARGET_SIGNAL_LINK"


class BoundingBox(BaseModel):
    left: float = Field(..., ge=0.0, le=1.0)
    top: float = Field(..., ge=0.0, le=1.0)
    right: float = Field(..., ge=0.0, le=1.0)
    bottom: float = Field(..., ge=0.0, le=1.0)
    label: StandardLabel
    track_id: str | None = None


class CrosswalkAnnotation(BaseModel):
    has_crosswalk: bool
    entrance_point: list[float] | None = None  # [x, y] in [0, 1]
    direction_degrees: float | None = None  # 0 ~ 360
    mask_rle: str | None = None  # Run-length encoded mask or polygon vertices


class FrameManifestItem(BaseModel):
    """Manifest record for a single frame or sequence item."""

    sample_id: str = Field(..., description="Unique sample ID e.g. SEQ01_F001")
    sequence_id: str = Field(..., description="Sequence or video clip identifier")
    frame_index: int = Field(..., ge=0)
    source: str = Field(..., description="Data source provider or survey team")
    license: str = Field(..., description="Explicit license string")
    consent: bool = Field(..., description="Human research/collection consent verified")
    site_id: str = Field(..., description="Intersection or road site identifier")
    device_id: str = Field(..., description="Capture device model")
    timestamp: datetime = Field(..., description="Capture timestamp")
    weather: WeatherCondition
    lighting: LightingCondition
    split: SplitType
    sha256_hash: str = Field(
        ..., min_length=64, max_length=64, description="SHA-256 checksum of raw frame"
    )
    labels: list[StandardLabel] = Field(default_factory=list)
    boxes: list[BoundingBox] = Field(default_factory=list)
    crosswalk: CrosswalkAnnotation | None = None
    is_hard_negative: bool = Field(default=False)

    @field_validator("consent")
    @classmethod
    def validate_consent(cls, v: bool) -> bool:
        if not v:
            raise ValueError(
                "Data cannot be used without verified consent (consent=True required)."
            )
        return v


class DatasetManifest(BaseModel):
    """Collection of frame manifest items representing a full dataset split."""

    dataset_name: str
    version: str
    created_at: datetime
    description: str
    items: list[FrameManifestItem]

    @property
    def total_items(self) -> int:
        return len(self.items)

    @property
    def site_ids(self) -> list[str]:
        return sorted({item.site_id for item in self.items})
