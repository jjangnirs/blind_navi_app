"""Tests for 4-Stage Ablation Analysis."""

from ml.datasets.synthetic_manifest_generator import generate_synthetic_manifest
from ml.evaluation.ablation import run_ablation_study


def test_01_ablation_study_monotonic_risk_reduction():
    """Verify that false-green events and target misselection monotonically decrease across ablation stages."""
    manifest = generate_synthetic_manifest()
    report = run_ablation_study(manifest)

    assert len(report.stages) == 4
    assert report.is_monotonic_risk_reduction is True

    s1, s2, s3, s4 = report.stages

    # Target misselection drops monotonically
    assert (
        s1.target_misselection_rate
        > s2.target_misselection_rate
        > s3.target_misselection_rate
        >= s4.target_misselection_rate
    )

    # False green drops from 3 to 0
    assert s1.false_green_events == 3
    assert s2.false_green_events == 1
    assert s3.false_green_events == 0
    assert s4.false_green_events == 0

    # Track transition errors drop to 0
    assert (
        s1.erroneous_track_transitions
        > s2.erroneous_track_transitions
        > s3.erroneous_track_transitions
    )
    assert s3.erroneous_track_transitions == 0
