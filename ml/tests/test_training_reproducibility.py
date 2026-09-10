"""Tests for Training Pipeline Reproducibility and Quantization."""

from ml.datasets.synthetic_manifest_generator import generate_synthetic_manifest
from ml.training.config import TrainingConfig
from ml.training.quantization import compare_quantization_profiles
from ml.training.train_pipeline import run_training


def test_01_training_reproducibility_with_fixed_seed():
    """Verify that running training twice with the same seed produces 100% identical outputs."""
    manifest = generate_synthetic_manifest()
    config = TrainingConfig(seed=42, epochs=5)

    run1 = run_training(config, manifest)
    run2 = run_training(config, manifest)

    assert run1.weights_hash == run2.weights_hash
    assert run1.final_metrics == run2.final_metrics
    assert len(run1.epochs_history) == len(run2.epochs_history)
    for ep1, ep2 in zip(run1.epochs_history, run2.epochs_history):
        assert ep1 == ep2


def test_02_different_seed_produces_different_weights():
    """Verify that different seeds produce different weight fingerprints."""
    manifest = generate_synthetic_manifest()
    config_a = TrainingConfig(seed=42, epochs=5)
    config_b = TrainingConfig(seed=999, epochs=5)

    run_a = run_training(config_a, manifest)
    run_b = run_training(config_b, manifest)

    assert run_a.weights_hash != run_b.weights_hash


def test_03_quantization_profile_comparison_maintains_zero_false_green():
    """Verify quantization report recommends PTQ_INT8 with 0 false-green events."""
    q_report = compare_quantization_profiles("mock_weights_hash")
    assert q_report.recommended_format == "PTQ_INT8"

    for item in q_report.items:
        assert item.false_green_events == 0
        assert item.model_size_mb <= 15.0  # Under 15MB budget
        assert item.ram_usage_mb <= 150.0  # Under 150MB budget
