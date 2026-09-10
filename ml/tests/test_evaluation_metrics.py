"""Tests for Sequence Event Evaluation Metrics and Statistical Bounds."""

from ml.evaluation.metrics import (
    SequenceEventResult,
    calculate_metrics_from_sequence_events,
    compute_rule_of_three_upper_bound,
)


def test_01_rule_of_three_upper_bound_calculation():
    """Verify Rule of Three formula 3/N for 95% confidence upper bound when 0 events observed."""
    # N = 100 -> ~0.03
    bound_100 = compute_rule_of_three_upper_bound(100)
    assert abs(bound_100 - 0.029957) < 1e-4

    # N = 10,000 (PRD release gate) -> 3 / 10000 = 0.0003
    bound_10k = compute_rule_of_three_upper_bound(10000)
    assert abs(bound_10k - 0.0003) < 1e-5


def test_02_sequence_metrics_calculation_and_slices():
    """Verify sequence event metric aggregation, false-green detection, and slice generation."""
    mock_events = [
        SequenceEventResult(
            sequence_id="SEQ_1",
            site_id="SITE_A",
            weather="CLEAR",
            lighting="DAYLIGHT",
            is_hard_negative=False,
            true_crosswalk_iou=0.90,
            true_direction_error_deg=1.5,
            true_signal_state="GREEN",
            predicted_signal_state="GREEN",
            target_signal_correct=True,
            vehicle_signal_chosen_as_ped=False,
            track_transition_error=False,
            is_false_green=False,
        ),
        SequenceEventResult(
            sequence_id="SEQ_2",
            site_id="SITE_B",
            weather="RAIN",
            lighting="STRONG_BACKLIGHT",
            is_hard_negative=True,
            true_crosswalk_iou=0.85,
            true_direction_error_deg=3.0,
            true_signal_state="RED",
            predicted_signal_state="RED",
            target_signal_correct=True,
            vehicle_signal_chosen_as_ped=False,
            track_transition_error=False,
            is_false_green=False,
        ),
        SequenceEventResult(
            sequence_id="SEQ_3",
            site_id="SITE_B",
            weather="RAIN",
            lighting="STRONG_BACKLIGHT",
            is_hard_negative=True,
            true_crosswalk_iou=0.80,
            true_direction_error_deg=4.0,
            true_signal_state="NONE",
            predicted_signal_state="UNKNOWN",
            target_signal_correct=False,
            vehicle_signal_chosen_as_ped=False,
            track_transition_error=False,
            is_false_green=False,
        ),
    ]

    metrics = calculate_metrics_from_sequence_events(mock_events)

    assert metrics.total_sequences == 3
    assert metrics.observed_false_green_events == 0
    # Rule of Three upper bound should be positive even when 0 false-greens observed
    assert metrics.rule_of_three_95ci_upper_bound > 0.0
    assert metrics.green_precision == 1.0

    # Verify Slices
    assert "hard_negative" in metrics.slices
    assert metrics.slices["hard_negative"].sample_count == 2
    assert "lighting_strong_backlight" in metrics.slices
    assert "weather_rain" in metrics.slices
