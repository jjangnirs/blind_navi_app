"""Tests for Model Card Generator."""

import os
import tempfile

from ml.datasets.synthetic_manifest_generator import generate_synthetic_manifest
from ml.evaluation.evaluator import evaluate_model_pipeline
from ml.model_cards.generator import generate_model_card


def test_01_model_card_generation_includes_disclaimer_and_metrics():
    """Verify model card generates markdown with release disclaimer, ablation, and metrics."""
    manifest = generate_synthetic_manifest()

    with tempfile.TemporaryDirectory() as tmpdir:
        eval_json_path = os.path.join(tmpdir, "eval.json")
        card_path = os.path.join(tmpdir, "model-card.md")

        doc = evaluate_model_pipeline(manifest, output_path=eval_json_path)
        content = generate_model_card(doc, output_path=card_path)

        assert os.path.exists(card_path)
        # Check mandatory safety disclaimer
        assert "출시 승인 불가 경고" in content
        assert "제품 상용 출시를 일체 승인할 수 없습니다" in content
        assert "PRD 12장" in content

        # Check metrics & bounds
        assert "Rule of Three" in content
        assert "Ablation" in content
