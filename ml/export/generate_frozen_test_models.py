import hashlib
import json
import struct
from pathlib import Path


def create_minimal_tflite_bytes(model_name: str) -> bytes:
    """
    유효한 TFLite FlatBuffer 식별자('TFL3')를 포함하는 최소 동결 테스트 모델 바이너리를 생성합니다.
    Offset 4~7에 'TFL3'가 위치해야 표준 TFLite 바이너리로 인식됩니다.
    """
    # FlatBuffer 헤더 구성:
    # Offset 0~3: root table offset (e.g. 16)
    # Offset 4~7: file identifier 'TFL3'
    # Offset 8~15: 메타데이터 패딩
    header = bytearray()
    header.extend(struct.pack("<I", 16))  # Root table offset
    header.extend(b"TFL3")  # TFLite flatbuffer identifier
    header.extend(b"\x00" * 8)  # Reserved / metadata

    # 모델 페이로드 (모델명 및 고유 식별 정보)
    payload = f"SafeCrossKR_FrozenModel_{model_name}_v1.0.0_Deterministic".encode()
    padding = b"\x00" * ((16 - (len(payload) % 16)) % 16)

    return bytes(header + payload + padding)


def generate_models(output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)

    # 1. 보행자 신호등 모델 (ped_signal_v1)
    sig_name = "ped_signal_v1"
    sig_bytes = create_minimal_tflite_bytes("PedestrianSignal")
    sig_file = output_dir / f"{sig_name}.tflite"
    sig_file.write_bytes(sig_bytes)

    sig_sha256 = hashlib.sha256(sig_bytes).hexdigest()
    sig_labels = ["PEDESTRIAN_SIGNAL_RED", "PEDESTRIAN_SIGNAL_GREEN", "UNKNOWN"]
    sig_manifest = {
        "modelName": "ped_signal",
        "modelVersion": "1.0.0",
        "sha256": sig_sha256,
        "minAppVersion": "0.1.0",
        "disabled": False,
        "inputTensor": {
            "name": "input_image",
            "shape": [1, 320, 320, 3],
            "dataType": "FLOAT32",
        },
        "outputTensors": [
            {"name": "detection_boxes", "shape": [1, 10, 4], "dataType": "FLOAT32"},
            {"name": "detection_classes", "shape": [1, 10], "dataType": "FLOAT32"},
            {"name": "detection_scores", "shape": [1, 10], "dataType": "FLOAT32"},
            {"name": "num_detections", "shape": [1], "dataType": "FLOAT32"},
        ],
        "labelsOrder": sig_labels,
    }

    (output_dir / f"{sig_name}_labels.json").write_text(
        json.dumps(sig_labels, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (output_dir / f"{sig_name}_manifest.json").write_text(
        json.dumps(sig_manifest, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    # 2. 횡단보도 장면 추정 모델 (crosswalk_scene_v1)
    cw_name = "crosswalk_scene_v1"
    cw_bytes = create_minimal_tflite_bytes("CrosswalkScene")
    cw_file = output_dir / f"{cw_name}.tflite"
    cw_file.write_bytes(cw_bytes)

    cw_sha256 = hashlib.sha256(cw_bytes).hexdigest()
    cw_labels = ["CROSSWALK_MASK", "CROSSWALK_ENTRANCE", "CROSSWALK_DIRECTION"]
    cw_manifest = {
        "modelName": "crosswalk_scene",
        "modelVersion": "1.0.0",
        "sha256": cw_sha256,
        "minAppVersion": "0.1.0",
        "disabled": False,
        "inputTensor": {
            "name": "input_image",
            "shape": [1, 320, 320, 3],
            "dataType": "FLOAT32",
        },
        "outputTensors": [
            {"name": "crosswalk_polygon", "shape": [1, 8], "dataType": "FLOAT32"},
            {"name": "crosswalk_entrance", "shape": [1, 2], "dataType": "FLOAT32"},
            {"name": "crosswalk_direction", "shape": [1, 1], "dataType": "FLOAT32"},
            {"name": "crosswalk_quality", "shape": [1, 1], "dataType": "FLOAT32"},
        ],
        "labelsOrder": cw_labels,
    }

    (output_dir / f"{cw_name}_labels.json").write_text(
        json.dumps(cw_labels, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (output_dir / f"{cw_name}_manifest.json").write_text(
        json.dumps(cw_manifest, indent=2, ensure_ascii=False), encoding="utf-8"
    )

    print(f"[OK] Generated frozen models and manifests in: {output_dir}")
    print(f" - {sig_name}.tflite (SHA-256: {sig_sha256})")
    print(f" - {cw_name}.tflite (SHA-256: {cw_sha256})")


if __name__ == "__main__":
    target_assets = Path("android-app/app/src/main/assets/models")
    generate_models(target_assets)
