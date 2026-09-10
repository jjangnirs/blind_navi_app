"""Tests for LiteRT Export and Android Golden Set Contract Verification."""

import os
import tempfile

from ml.export.contract_verifier import (
    compute_letterbox_transform,
    verify_android_golden_contract,
)
from ml.export.litert_exporter import export_litert_package


def test_01_android_golden_contract_verification():
    """Verify that Python letterbox and unletterbox formulas match Android VisionTransforms.kt."""
    assert verify_android_golden_contract() is True


def test_02_letterbox_transform_numerical_accuracy():
    """Verify exact padding and scaling for 640x480 to 320x320."""
    transform = compute_letterbox_transform(640, 480, 320, 320)
    assert transform["scale"] == 0.5
    assert transform["scaled_w"] == 320.0
    assert transform["scaled_h"] == 240.0
    assert transform["pad_left"] == 0.0
    assert transform["pad_top"] == 40.0


def test_03_litert_package_export():
    """Verify export_litert_package produces valid binary, SHA-256, labels, and manifest."""
    with tempfile.TemporaryDirectory() as tmpdir:
        res = export_litert_package("test_ped_signal", tmpdir, model_version="1.0.0")

        assert os.path.exists(res["tflite_path"])
        assert os.path.exists(res["manifest_path"])
        assert os.path.exists(res["labels_path"])
        assert len(res["sha256"]) == 64
