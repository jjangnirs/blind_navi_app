"""Synthetic Manifest Generator for Local Testing & Reproducibility.

Generates metadata manifests without storing raw image/video binaries in Git.
Used to verify the training, evaluation, ablation, and export pipeline.
"""

import hashlib
from datetime import datetime, timezone

from ml.datasets.manifest_schema import (
    BoundingBox,
    CrosswalkAnnotation,
    DatasetManifest,
    FrameManifestItem,
    LightingCondition,
    SplitType,
    StandardLabel,
    WeatherCondition,
)


def generate_synthetic_manifest(
    dataset_name: str = "safecross_pilot_dataset",
    num_sequences_per_site: int = 4,
    frames_per_sequence: int = 10,
) -> DatasetManifest:
    """Generate a clean, leak-free dataset manifest across 10 distinct intersection sites.

    Split allocation:
    - Sites 1~6: TRAIN (60%)
    - Sites 7~8: VAL (20%)
    - Sites 9~10: TEST (20%)
    """
    items: list[FrameManifestItem] = []

    site_configs = [
        (
            "GWANGJU_SITE_101",
            SplitType.TRAIN,
            WeatherCondition.CLEAR,
            LightingCondition.DAYLIGHT,
        ),
        (
            "GWANGJU_SITE_102",
            SplitType.TRAIN,
            WeatherCondition.OVERCAST,
            LightingCondition.DAYLIGHT,
        ),
        (
            "GWANGJU_SITE_103",
            SplitType.TRAIN,
            WeatherCondition.RAIN,
            LightingCondition.DAYLIGHT,
        ),
        (
            "GWANGJU_SITE_104",
            SplitType.TRAIN,
            WeatherCondition.CLEAR,
            LightingCondition.DUSK,
        ),
        (
            "GWANGJU_SITE_105",
            SplitType.TRAIN,
            WeatherCondition.CLEAR,
            LightingCondition.NIGHT,
        ),
        (
            "GWANGJU_SITE_106",
            SplitType.TRAIN,
            WeatherCondition.CLEAR,
            LightingCondition.STRONG_BACKLIGHT,
        ),
        (
            "GWANGJU_SITE_201",
            SplitType.VAL,
            WeatherCondition.CLEAR,
            LightingCondition.DAYLIGHT,
        ),
        (
            "GWANGJU_SITE_202",
            SplitType.VAL,
            WeatherCondition.RAIN,
            LightingCondition.DUSK,
        ),
        (
            "GWANGJU_SITE_301",
            SplitType.TEST,
            WeatherCondition.CLEAR,
            LightingCondition.DAYLIGHT,
        ),
        (
            "GWANGJU_SITE_302",
            SplitType.TEST,
            WeatherCondition.RAIN,
            LightingCondition.STRONG_BACKLIGHT,
        ),
    ]

    for site_id, split, weather, lighting in site_configs:
        for seq_idx in range(num_sequences_per_site):
            seq_id = f"{site_id}_SEQ{seq_idx + 1:02d}"
            # Sequence scenarios:
            # 0: Pedestrian RED
            # 1: Pedestrian GREEN
            # 2: Vehicle Green / Ped Red (Hard Negative)
            # 3: Commercial light / Ambiguous
            scenario_type = seq_idx % 4

            for f_idx in range(frames_per_sequence):
                sample_id = f"{seq_id}_F{f_idx:03d}"
                raw_token = f"{sample_id}_{site_id}_{weather}_{lighting}".encode()
                sha256 = hashlib.sha256(raw_token).hexdigest()

                labels: list[StandardLabel] = []
                boxes: list[BoundingBox] = []
                is_hard_neg = False

                if scenario_type == 0:
                    labels.append(StandardLabel.PEDESTRIAN_SIGNAL_RED)
                    boxes.append(
                        BoundingBox(
                            left=0.45,
                            top=0.20,
                            right=0.55,
                            bottom=0.40,
                            label=StandardLabel.PEDESTRIAN_SIGNAL_RED,
                            track_id=f"{seq_id}_TRK1",
                        )
                    )
                elif scenario_type == 1:
                    labels.append(StandardLabel.PEDESTRIAN_SIGNAL_GREEN)
                    boxes.append(
                        BoundingBox(
                            left=0.45,
                            top=0.20,
                            right=0.55,
                            bottom=0.40,
                            label=StandardLabel.PEDESTRIAN_SIGNAL_GREEN,
                            track_id=f"{seq_id}_TRK1",
                        )
                    )
                elif scenario_type == 2:
                    # Hard negative: Vehicle signal is green, ped is red
                    is_hard_neg = True
                    labels.extend(
                        [
                            StandardLabel.PEDESTRIAN_SIGNAL_RED,
                            StandardLabel.VEHICLE_SIGNAL,
                        ]
                    )
                    boxes.append(
                        BoundingBox(
                            left=0.45,
                            top=0.20,
                            right=0.55,
                            bottom=0.40,
                            label=StandardLabel.PEDESTRIAN_SIGNAL_RED,
                            track_id=f"{seq_id}_TRK1",
                        )
                    )
                    boxes.append(
                        BoundingBox(
                            left=0.70,
                            top=0.15,
                            right=0.85,
                            bottom=0.30,
                            label=StandardLabel.VEHICLE_SIGNAL,
                            track_id=f"{seq_id}_TRK_VEH",
                        )
                    )
                else:
                    # Commercial backlight or billboard
                    is_hard_neg = True
                    labels.append(StandardLabel.HARD_NEGATIVE_LIGHT)
                    boxes.append(
                        BoundingBox(
                            left=0.20,
                            top=0.30,
                            right=0.35,
                            bottom=0.45,
                            label=StandardLabel.HARD_NEGATIVE_LIGHT,
                            track_id=f"{seq_id}_TRK_COMM",
                        )
                    )

                # Crosswalk annotation
                labels.extend(
                    [
                        StandardLabel.CROSSWALK_MASK,
                        StandardLabel.CROSSWALK_ENTRANCE,
                        StandardLabel.CROSSWALK_DIRECTION,
                        StandardLabel.TARGET_SIGNAL_LINK,
                    ]
                )
                crosswalk = CrosswalkAnnotation(
                    has_crosswalk=True,
                    entrance_point=[0.5, 0.85],
                    direction_degrees=0.0 if "302" not in site_id else 45.0,
                    mask_rle="RLE_COMPRESSED_POLYGON_MOCK",
                )

                item = FrameManifestItem(
                    sample_id=sample_id,
                    sequence_id=seq_id,
                    frame_index=f_idx,
                    source="SAFE_CROSS_LOCAL_PILOT",
                    license="CC-BY-4.0",
                    consent=True,
                    site_id=site_id,
                    device_id="GALAXY_S23_ULTRA",
                    timestamp=datetime(2026, 9, 1, 14, 0, tzinfo=timezone.utc),
                    weather=weather,
                    lighting=lighting,
                    split=split,
                    sha256_hash=sha256,
                    labels=labels,
                    boxes=boxes,
                    crosswalk=crosswalk,
                    is_hard_negative=is_hard_neg,
                )
                items.append(item)

    return DatasetManifest(
        dataset_name=dataset_name,
        version="1.0.0",
        created_at=datetime(2026, 9, 9, 9, 0, tzinfo=timezone.utc),
        description="Safe Cross KR Reproducible Dataset Manifest across 10 Gwangju sites",
        items=items,
    )
