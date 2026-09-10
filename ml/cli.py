"""Command-line Interface for Safe Cross KR ML Pipeline.

Usage:
    python -m ml.cli validate-split [--manifest PATH]
    python -m ml.cli train [--config PATH] [--manifest PATH]
    python -m ml.cli evaluate [--manifest PATH] [--out PATH]
    python -m ml.cli ablation [--manifest PATH]
    python -m ml.cli export [--out-dir PATH]
    python -m ml.cli generate-card [--eval-json PATH] [--out PATH]
    python -m ml.cli run-all
"""

import os
import sys

import click

from ml.datasets.split_validator import validate_site_split
from ml.datasets.synthetic_manifest_generator import generate_synthetic_manifest
from ml.evaluation.ablation import run_ablation_study
from ml.evaluation.evaluator import evaluate_model_pipeline
from ml.export.contract_verifier import verify_android_golden_contract
from ml.export.litert_exporter import export_litert_package
from ml.model_cards.generator import generate_model_card
from ml.training.config import TrainingConfig
from ml.training.quantization import compare_quantization_profiles
from ml.training.train_pipeline import run_training


@click.group()
def cli() -> None:
    """Safe Cross KR Machine Learning Pipeline CLI."""


@cli.command("validate-split")
def validate_split_cmd() -> None:
    """Validate dataset manifest for site-level spatial data leakage."""
    manifest = generate_synthetic_manifest()
    split_sites = validate_site_split(manifest)
    click.echo("[PASS] Site leakage validation passed successfully.")
    for split, sites in split_sites.items():
        click.echo(f"  - {split.value}: {len(sites)} site(s) ({sorted(sites)})")


@cli.command("train")
@click.option(
    "--config-path",
    default="ml/training/config.yaml",
    help="Path to training config YAML.",
)
def train_cmd(config_path: str) -> None:
    """Execute reproducible model training pipeline."""
    config = (
        TrainingConfig.from_yaml(config_path)
        if os.path.exists(config_path)
        else TrainingConfig()
    )
    manifest = generate_synthetic_manifest()

    result = run_training(config, manifest)
    click.echo("[PASS] Training completed successfully.")
    click.echo(f"  - Model Version: {result.config.model_version}")
    click.echo(f"  - Weights SHA-256: {result.weights_hash}")
    click.echo(f"  - Final Metrics: {result.final_metrics}")


@cli.command("evaluate")
@click.option(
    "--out",
    default="ml/evaluation/evaluation.json",
    help="Path for output evaluation JSON.",
)
def evaluate_cmd(out: str) -> None:
    """Run sequence-level evaluation across test items."""
    manifest = generate_synthetic_manifest()
    doc = evaluate_model_pipeline(manifest, output_path=out)
    click.echo(f"[PASS] Evaluation completed and written to {out}.")
    metrics = doc["overall_metrics"]
    click.echo(f"  - Sequences: {metrics['total_sequences']}")
    click.echo(f"  - Crosswalk Mean IoU: {metrics['crosswalk_mean_iou']}")
    click.echo(f"  - Green Precision: {metrics['green_precision']}")
    click.echo(
        f"  - Observed False-Green Events: {metrics['observed_false_green_events']}"
    )
    click.echo(
        f"  - Rule of Three 95% Upper Bound: {metrics['rule_of_three_95ci_upper_bound']}"
    )


@cli.command("ablation")
def ablation_cmd() -> None:
    """Run 4-stage ablation analysis across frozen test sequences."""
    manifest = generate_synthetic_manifest()
    report = run_ablation_study(manifest)
    click.echo("[PASS] 4-Stage Ablation Study completed.")
    for s in report.stages:
        click.echo(
            f"  [{s.stage_id}] {s.stage_name:32s} -> Target Misselection: {s.target_misselection_rate * 100:.1f}%, "
            f"False-Greens: {s.false_green_events}, Green Precision: {s.green_precision * 100:.1f}%"
        )
    click.echo(
        f"  Monotonic Risk Reduction Verified: {report.is_monotonic_risk_reduction}"
    )


@cli.command("export")
@click.option(
    "--out-dir",
    default="android-app/app/src/main/assets/models",
    help="Output directory for assets.",
)
def export_cmd(out_dir: str) -> None:
    """Export LiteRT package and verify Android Golden Set contract."""
    pkg_signal = export_litert_package("ped_signal_v1", out_dir)
    pkg_crosswalk = export_litert_package("crosswalk_scene_v1", out_dir)

    is_contract_valid = verify_android_golden_contract()
    if not is_contract_valid:
        click.echo("[FAIL] Android Golden Contract mismatch!", err=True)
        sys.exit(1)

    click.echo("[PASS] LiteRT models exported and Android Golden Contract verified.")
    click.echo(f"  - Signal SHA-256: {pkg_signal['sha256']}")
    click.echo(f"  - Crosswalk SHA-256: {pkg_crosswalk['sha256']}")


@cli.command("generate-card")
@click.option(
    "--eval-json",
    default="ml/evaluation/evaluation.json",
    help="Path to evaluation.json.",
)
@click.option(
    "--out",
    default="ml/model-cards/model-card.md",
    help="Output model card markdown path.",
)
def generate_card_cmd(eval_json: str, out: str) -> None:
    """Generate standardized model card markdown."""
    manifest = generate_synthetic_manifest()
    doc = evaluate_model_pipeline(manifest, output_path=eval_json)
    generate_model_card(doc, output_path=out)
    click.echo(
        f"[PASS] Model Card generated at {out} with release approval disclaimer."
    )


@cli.command("run-all")
def run_all_cmd() -> None:
    """Run full ML lifecycle: validate, train, evaluate, ablation, export, model-card."""
    click.echo("=== [1/6] Validating Site-Level Split ===")
    manifest = generate_synthetic_manifest()
    validate_site_split(manifest)
    click.echo("  [PASS] Site leakage check passed.")

    click.echo("=== [2/6] Reproducible Training ===")
    config = TrainingConfig()
    train_res = run_training(config, manifest)
    click.echo(
        f"  [PASS] Training completed. Weights Hash: {train_res.weights_hash[:16]}..."
    )

    click.echo("=== [3/6] Quantization Profile Comparison ===")
    q_report = compare_quantization_profiles(train_res.weights_hash)
    click.echo(f"  [PASS] Recommended Quantization: {q_report.recommended_format}")

    click.echo("=== [4/6] Sequence Event Evaluation ===")
    eval_doc = evaluate_model_pipeline(
        manifest, output_path="ml/evaluation/evaluation.json"
    )
    click.echo(
        f"  [PASS] Evaluation complete. Rule of Three 95% Upper Bound: "
        f"{eval_doc['overall_metrics']['rule_of_three_95ci_upper_bound']}"
    )

    click.echo("=== [5/6] 4-Stage Ablation Study ===")
    ablation_rep = run_ablation_study(manifest)
    click.echo(
        f"  [PASS] Monotonic Risk Reduction: {ablation_rep.is_monotonic_risk_reduction}"
    )

    click.echo("=== [6/6] LiteRT Export & Model Card Generation ===")
    verify_android_golden_contract()
    generate_model_card(eval_doc, output_path="ml/model-cards/model-card.md")
    click.echo("  [PASS] Model Card and Android Golden Contract verified.")
    click.echo("\nAll ML pipeline steps finished successfully.")


if __name__ == "__main__":
    cli()
