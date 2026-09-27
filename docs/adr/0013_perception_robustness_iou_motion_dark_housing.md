# ADR 0013: 신호등 인지 3대 취약점 개선 (IoU 공간 추적, 동역학 모션 필터, 다크 하우징 검증)

## 1. 문맥 (Context)
기존 신호등 판정 파이프라인에서 발견된 3대 세부 취약점:
1. **단순 상태 누적(Temporal Window Flaw)**: 프레임 간 바운딩 박스의 공간적 동일성을 추적하지 않고 단순 상태만 누적하여, 서로 다른 위치의 불빛이 롤링 버퍼에 오합산될 가능성 존재.
2. **동역학(Motion) 필터 부재**: 차도를 가로지르는 고속 이동 차량(초록색 간선버스, 트럭 등)이나 차량 후미등이 화면을 스쳐 지나갈 때 정지 신호등과 분별하지 못함.
3. **신호등 케이스(Housing) 미확인**: 발광 픽셀(Color Blob)의 유무만 판정하여, 차광판(Visor)과 검은색 하우징 케이스가 없는 벽면 간판, 전광판, 상점 조명 등을 물리적 구조 관점에서 원천 차단하지 못함.

시각장애인의 생명 안전과 직결된 **Zero False-Green(절대 오탐 방지)**을 산업 표준 수준으로 보장하기 위해 물리적·공간적 3단계 개선을 적용하였다.

## 2. 결정 (Decision)

### 1) IoU 기반 공간 추적 및 Track 단위 일관성 (`LocalVlmSignalVerifier.kt`)
- 프레임 간 Bounding Box 간의 Intersection over Union($\text{IoU}$) 연산 메서드 구현:
  $$\text{IoU}(B_1, B_2) = \frac{\text{Area}(B_1 \cap B_2)}{\text{Area}(B_1 \cup B_2)}$$
- $\text{IoU} \ge 0.35$ 일 때만 동일 Track으로 인정하여 시간 롤링 윈도우에 누적.
- 박스 위치가 점프하거나 다른 물체로 변경($\text{IoU} < 0.35$)되면 즉시 시간 버퍼를 초기화(`history.clear()`)하여 서로 다른 위치의 불빛 오합산을 100% 방지.

### 2) 프레임 간 변위 속도(Motion Vector) 동역학 필터 (`LocalVlmSignalVerifier.kt`)
- 보행신호등은 도심 인프라에 고정 설치되어 정지(Stationary) 상태를 유지한다는 물리 법칙 활용.
- 직전 프레임 중심점과 현재 프레임 중심점 간 이동 변위 속도 계산:
  $$v = \frac{\sqrt{(cx_t - cx_{t-1})^2 + (cy_t - cy_{t-1})^2}}{\Delta t} \quad (\text{단위: 화면 폭/초})$$
- 보행자 손떨림($v \le 0.30$)을 여유 있게 수용하고, 화면을 가로지르는 차량($v > 0.55/\text{sec}$)을 감지하여 `REJECTED_DYNAMIC_MOTION` 사유로 즉시 `UNKNOWN` 기각.

### 3) 다크 하우징(Dark Housing) 콘트라스트 검증 (`CameraVisionSignalEstimator.kt`)
- 실제 신호등은 고휘도 발광 램프($V \ge 0.70$) 주변이 무광 검정/암회색 폴리카보네이트 차광판($V \le 0.40$)으로 둘러싸여 있음.
- 반면 간판, 전광판, 건물 유리창 등은 외곽 테두리도 함께 밝음.
- 후보 발광체 외곽 마진(Collar Band)의 평균 명도($V_{\text{collar}}$)와 램프 명도($V_{\text{lamp}}$)의 대비 계산:
  $$V_{\text{collar}} \ge 0.45 \quad \text{and} \quad (V_{\text{lamp}} - V_{\text{collar}}) < 0.20 \implies \text{하우징 없는 비신호등(간판 등)으로 기각}$$

## 3. 결과 및 영향 (Consequences)
- **차량 및 동적 객체 오탐 원천 차단**: 횡단보도를 가로지르는 녹색 버스, 택시, 자전거 도색이 신호등으로 인식되지 않음.
- **간판 및 조명 노이즈 완전 배제**: 다크 하우징 케이스가 없는 도심 상점 간판, 네온사인, 건물 조명이 효과적으로 기각됨.
- **테스트 및 검증 통과**:
  - `PerceptionRobustnessTest`: IoU 연산, 공간 점프 리셋, 고속 이동 차량 기각, 정지 신호 통과, 다크 하우징 판별 등 6개 단위 테스트 전원 통과.
  - 전체 안드로이드 단위 테스트(`testDebugUnitTest`) 100% 통과.
  - 최신 디버그 APK(`assembleDebug`) 빌드 완료.
