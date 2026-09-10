"""Tests for Dataset Manifest Schema and Site Leakage Validator."""

from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from ml.datasets.manifest_schema import (
    FrameManifestItem,
    LightingCondition,
    SplitType,
    StandardLabel,
    WeatherCondition,
)
from ml.datasets.split_validator import SiteLeakageError, validate_site_split
from ml.datasets.synthetic_manifest_generator import generate_synthetic_manifest


def test_01_synthetic_manifest_valid_and_consent_checked():
    """Verify clean manifest passes validation with consent=True and valid hash."""
    manifest = generate_synthetic_manifest()
    assert manifest.total_items > 0
    assert len(manifest.site_ids) == 10

    for item in manifest.items:
        assert item.consent is True
        assert len(item.sha256_hash) == 64
        assert item.split in [SplitType.TRAIN, SplitType.VAL, SplitType.TEST]


def test_02_consent_false_fails_validation():
    """Verify items without verified human consent raise ValidationError."""
    with pytest.raises(ValidationError):
        FrameManifestItem(
            sample_id="SAMPLE_NOCONSENT_01",
            sequence_id="SEQ_01",
            frame_index=0,
            source="UNAUTHORIZED",
            license="CC-BY-4.0",
            consent=False,  # Unconsented
            site_id="SITE_X",
            device_id="DEV_X",
            timestamp=datetime.now(timezone.utc),
            weather=WeatherCondition.CLEAR,
            lighting=LightingCondition.DAYLIGHT,
            split=SplitType.TRAIN,
            sha256_hash="a" * 64,
            labels=[StandardLabel.PEDESTRIAN_SIGNAL_RED],
        )


def test_03_clean_site_split_validation_passes():
    """Verify synthetic manifest has strictly disjoint sites across splits."""
    manifest = generate_synthetic_manifest()
    split_sites = validate_site_split(manifest)

    train_sites = split_sites[SplitType.TRAIN]
    val_sites = split_sites[SplitType.VAL]
    test_sites = split_sites[SplitType.TEST]

    assert len(train_sites) == 6
    assert len(val_sites) == 2
    assert len(test_sites) == 2

    # Verify disjoint sets
    assert train_sites.isdisjoint(val_sites)
    assert train_sites.isdisjoint(test_sites)
    assert val_sites.isdisjoint(test_sites)


def test_04_site_leakage_detected_and_raises_error():
    """Verify that when a site appears in both TRAIN and TEST, SiteLeakageError is raised."""
    manifest = generate_synthetic_manifest()

    # Intentionally inject leakage: change one TEST item to have a TRAIN site_id
    leaking_item = manifest.items[-1].model_copy(update={"site_id": "GWANGJU_SITE_101"})
    corrupted_items = manifest.items[:-1] + [leaking_item]
    corrupted_manifest = manifest.model_copy(update={"items": corrupted_items})

    with pytest.raises(SiteLeakageError) as exc_info:
        validate_site_split(corrupted_manifest)

    assert "GWANGJU_SITE_101" in exc_info.value.leaking_sites
    assert SplitType.TRAIN.value in exc_info.value.leaking_sites["GWANGJU_SITE_101"]
    assert SplitType.TEST.value in exc_info.value.leaking_sites["GWANGJU_SITE_101"]
