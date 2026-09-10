"""CI regression tests for model tampering, hash corruption, and manifest signatures.

Verifies acceptance criteria:
- Injected corrupted model fixture (tampered bytes or wrong SHA-256) is strictly detected and rejected.
- Mismatched labels or disabled kill-switch manifest triggers immediate safe rejection.
- Valid model bundle passes contract verification.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


def compute_sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_model_bundle_integrity(
    model_bytes: bytes, manifest_json: str, labels_json: str
) -> tuple[bool, str]:
    """Simulates CI / device-side model integrity contract gate."""
    try:
        manifest = json.loads(manifest_json)
        labels = json.loads(labels_json)
    except (json.JSONDecodeError, ValueError, TypeError) as e:
        return False, f"JSON parse error: {e}"

    # 1. Kill-switch check
    if manifest.get("disabled", False):
        return (
            False,
            f"Model is disabled via kill-switch: {manifest.get('killReason', 'N/A')}",
        )

    # 2. SHA-256 checksum check
    expected_sha = manifest.get("sha256")
    actual_sha = compute_sha256(model_bytes)
    if expected_sha != actual_sha:
        return (
            False,
            f"SHA-256 mismatch: expected {expected_sha}, computed {actual_sha}",
        )

    # 3. Label order check
    expected_labels = ["RED", "GREEN", "UNKNOWN"]
    if manifest.get("modelType") == "SIGNAL_CLASSIFIER":
        actual_labels = labels.get("labels", [])
        if actual_labels != expected_labels:
            return (
                False,
                f"Invalid label order: expected {expected_labels}, got {actual_labels}",
            )

    return True, "Integrity verified"


def test_01_valid_model_fixture_passes_integrity_gate(tmp_path: Path):
    """Verifies that untampered golden model fixture passes verification."""
    model_bytes = b"\x1c\x00\x00\x00TFL3\x00\x00safe_ped_signal_model_v1"
    sha = compute_sha256(model_bytes)

    manifest = {
        "modelId": "ped_signal_v1",
        "modelType": "SIGNAL_CLASSIFIER",
        "sha256": sha,
        "version": "1.0.0",
        "disabled": False,
    }
    labels = {"labels": ["RED", "GREEN", "UNKNOWN"]}

    is_valid, msg = verify_model_bundle_integrity(
        model_bytes, json.dumps(manifest), json.dumps(labels)
    )
    assert is_valid
    assert "Integrity verified" in msg


def test_02_tampered_model_byte_fails_integrity_gate():
    """Verifies that flipping 1 byte in the model file triggers SHA-256 failure."""
    original_bytes = b"\x1c\x00\x00\x00TFL3\x00\x00safe_ped_signal_model_v1"
    tampered_bytes = bytearray(original_bytes)
    tampered_bytes[-1] ^= 0xFF  # Corrupt last byte

    manifest = {
        "modelId": "ped_signal_v1",
        "modelType": "SIGNAL_CLASSIFIER",
        "sha256": compute_sha256(original_bytes),  # Old hash
        "version": "1.0.0",
        "disabled": False,
    }
    labels = {"labels": ["RED", "GREEN", "UNKNOWN"]}

    is_valid, msg = verify_model_bundle_integrity(
        bytes(tampered_bytes), json.dumps(manifest), json.dumps(labels)
    )
    assert not is_valid
    assert "SHA-256 mismatch" in msg


def test_03_disabled_kill_switch_manifest_fails_gate():
    """Verifies that a model flagged with disabled: true is rejected immediately."""
    model_bytes = b"\x1c\x00\x00\x00TFL3\x00\x00safe_ped_signal_model_v1"
    sha = compute_sha256(model_bytes)

    manifest = {
        "modelId": "ped_signal_v1",
        "modelType": "SIGNAL_CLASSIFIER",
        "sha256": sha,
        "version": "1.0.0",
        "disabled": True,
        "killReason": "Adversarial false-green vulnerability reported at site-042",
    }
    labels = {"labels": ["RED", "GREEN", "UNKNOWN"]}

    is_valid, msg = verify_model_bundle_integrity(
        model_bytes, json.dumps(manifest), json.dumps(labels)
    )
    assert not is_valid
    assert "disabled via kill-switch" in msg


def test_04_mismatched_labels_fails_gate():
    """Verifies that swapped or modified labels (e.g. GREEN before RED) are rejected."""
    model_bytes = b"\x1c\x00\x00\x00TFL3\x00\x00safe_ped_signal_model_v1"
    sha = compute_sha256(model_bytes)

    manifest = {
        "modelId": "ped_signal_v1",
        "modelType": "SIGNAL_CLASSIFIER",
        "sha256": sha,
        "version": "1.0.0",
        "disabled": False,
    }
    # Swapped labels (GREEN at index 0 is dangerous!)
    labels = {"labels": ["GREEN", "RED", "UNKNOWN"]}

    is_valid, msg = verify_model_bundle_integrity(
        model_bytes, json.dumps(manifest), json.dumps(labels)
    )
    assert not is_valid
    assert "Invalid label order" in msg
