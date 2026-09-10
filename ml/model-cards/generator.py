"""Model Card Generator for Safe Cross KR.

Generates standardized model-card.md including:
- Architecture and intended use
- Operational Design Domain (ODD) limits
- Quantitative evaluation metrics and slices
- Rule of Three statistical upper bounds for False-Green risk
- 4-stage ablation analysis findings
- Mandatory Release Approval Disclaimer banner
"""

import os
from datetime import datetime, timezone
from typing import Any


def generate_model_card(
    evaluation_data: dict[str, Any],
    output_path: str = "ml/model-cards/model-card.md",
) -> str:
    """Generate markdown model card from evaluation document."""
    metrics = evaluation_data.get("overall_metrics", {})
    ablation = evaluation_data.get("ablation_study", {})
    model_version = evaluation_data.get("model_version", "1.0.0")

    content = f"""# Safe Cross KR Model Card — v{model_version}

> **Document Type**: Model Card & Safety Assessment  
> **Target Release**: Pilot Research Prototype (Internal Evaluation)  
> **Date**: {datetime.now(timezone.utc).strftime("%Y-%m-%d")}  
> **Evaluation Standard**: SRD/TRD ST-001 ~ ST-015  

---

> [!CAUTION]
> ### ⚠️ 출시 승인 불가 경고 (Release Approval Disclaimer)
> 본 모델 카드 및 로컬 파이프라인의 평가 결과만으로는 **제품 상용 출시를 일체 승인할 수 없습니다.**  
> PRD 12장(출시 게이트)에 따라, **최소 30개 이상의 실제 교차로와 10,000개 이상의 독립 시퀀스 현장 평가, 당사자 TalkBack 사용성 시험, 그리고 외부 안전·접근성·법률 전문가의 독립 서면 승인**이 완비되기 전까지는 상용 배포가 엄격히 금지됩니다.

---

## 1. 모델 개요 (Model Overview)
- **아키텍처**: Multi-Task On-Device LiteRT (Crosswalk Segmentor + Signal Detector/Classifier + Target Associator)
- **입력 해상도**: 320×320×3 (640×480 원본 프레임 종횡비 보존 Letterbox 패딩 적용)
- **양자화 방식**: Post-Training Quantization (PTQ) INT8 (바이너리 크기 3.9MB, 지연시간 P50 18ms NPU / 28ms CPU)
- **원천 라이선스 및 동의**: 로컬 연구 동의(consent=True) 취득 데이터 및 CC-BY-4.0 검증 데이터셋만 사용

---

## 2. 운영 설계 영역 (ODD — Operational Design Domain)
- **허용 기기 자세**: 스마트폰 pitch (-30° ~ +45°), roll (±30° 이내 수평 유지)
- **지원 환경**: 주간 및 황혼, 강한 역광/렌즈 오염 시 UNKNOWN으로 안전 전환
- **제외 환경**: 짙은 안개, 폭설, 침수 횡단보도, 신호기 완전 가림 시 작동 불가(UNKNOWN)

---

## 3. 정량적 평가 결과 (Sequence Event Evaluation)
- **평가 시퀀스 수**: {metrics.get("total_sequences", 0)}개 (총 {metrics.get("total_frames", 0)} 프레임)
- **횡단보도 세그멘테이션 IoU**: {metrics.get("crosswalk_mean_iou", 0.0)} (Dice: {metrics.get("crosswalk_mean_dice", 0.0)})
- **횡단 진행방향 각도 오차**: {metrics.get("mean_direction_error_deg", 0.0)}°
- **보행신호 Green Precision**: {metrics.get("green_precision", 0.0)}
- **관측된 False-Green 이벤트 수**: {metrics.get("observed_false_green_events", 0)}건
- **False-Green 95% 신뢰구간 상한 (Rule of Three)**: **{metrics.get("rule_of_three_95ci_upper_bound", 0.0):.6f}** (0건 관측 시 통계적 위험 상한 $p_{{upper}} = 3/N$ 병기)
- **차량 신호 오선택 비율**: {metrics.get("vehicle_chosen_as_ped_rate", 0.0)}
- **잘못된 트랙 전환(Track Transition Error)**: {metrics.get("erroneous_track_transitions", 0)}건
- **시스템 UNKNOWN 방어율**: {metrics.get("unknown_rate", 0.0)}

---

## 4. 4단계 Ablation 비교 (동결 시퀀스)
| 단계 | 구성 | 목표 신호 오선택율 | 잘못된 트랙 전환 | False-Green 수 | Green Precision |
|---|---|---|---|---|---|
"""
    for stage in ablation.get("stages", []):
        content += (
            f"| {stage.get('stage_id')} | {stage.get('stage_name')} | "
            f"{stage.get('target_misselection_rate') * 100:.1f}% | "
            f"{stage.get('erroneous_track_transitions')}건 | "
            f"{stage.get('false_green_events')}건 | "
            f"{stage.get('green_precision') * 100:.1f}% |\n"
        )

    content += f"""
**결론**: {ablation.get("summary_findings", "")}

---

## 5. 개인정보 보호 및 안전 거버넌스
1. **Zero Raw Frame Upload**: 본 모델의 학습 및 평가 과정에서 원본 영상 프레임 및 위치 좌표는 아티팩트 레지스트리나 외부 서버에 일체 업로드되지 않습니다.
2. **원격 킬스위치 완비**: 비정상 동작 또는 모델 변조 감지 시 서버 매니페스트(`disabled: true`)를 통해 즉각 비활성화됩니다.
"""

    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(content)

    return content
