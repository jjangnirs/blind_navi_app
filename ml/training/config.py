"""Training Configuration Model and Loader.

Enforces deterministic reproducibility using a single configuration file.
"""

import yaml
from pydantic import BaseModel, Field


class AugmentationConfig(BaseModel):
    horizontal_flip: bool = Field(
        default=False, description="Must be False to preserve signal layout semantics"
    )
    random_brightness: bool = True
    random_contrast: bool = True
    letterbox_padding: bool = True


class OptimizerConfig(BaseModel):
    name: str = "AdamW"
    learning_rate: float = 0.001
    weight_decay: float = 0.0001


class TrainingConfig(BaseModel):
    seed: int = 42
    input_size: list[int] = Field(default=[320, 320, 3])
    batch_size: int = 16
    epochs: int = 10
    optimizer: OptimizerConfig = Field(default_factory=OptimizerConfig)
    augmentation: AugmentationConfig = Field(default_factory=AugmentationConfig)
    quantization_target: str = "PTQ_INT8"
    model_version: str = "1.0.0"

    @classmethod
    def from_yaml(cls, path: str) -> "TrainingConfig":
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        return cls(**data)

    def to_yaml(self, path: str) -> None:
        with open(path, "w", encoding="utf-8") as f:
            yaml.dump(self.model_dump(), f, default_flow_style=False, sort_keys=False)
