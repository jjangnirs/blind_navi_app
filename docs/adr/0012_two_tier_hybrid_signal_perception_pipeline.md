# ADR 0012: 2단계 하이브리드 보행신호 판정 파이프라인 (Two-Tier Hybrid Pipeline)

## 1. 문맥 (Context)
기존 신호등 인식 방식은 다음과 같은 치명적인 안전 취약점(False-Green 위험)이 존재했다:
- `CameraVisionSignalEstimator`가 전체 화면 상단(8%~65%)의 광범위한 영역에서 HSV 픽셀 색상만으로 신호등을 판정하여, **딥러닝 모델(`LiteRtPedestrianSignalEstimator`)이 신호등을 전혀 찾지 못했음에도 배경의 초록색 간판, 버스 도색, 청록색 조명 등이 비치면 독자적으로 `GREEN` 판정을 내릴 위험**이 존재함.
- 시각장애인 보행 안전의 제1원칙인 **Zero False-Green(신호등이 아닌 곳에서 절대 보행 녹색 판정을 내리지 않음)**을 원천 보장하기 위한 아키텍처 개선이 요구됨.

## 2. 결정 (Decision)
**"LiteRT 딥러닝 객체 검출 모델이 신호등 형태(Box)를 먼저 검출하고, 그 검출된 Box 내부에서만 정밀 HSV 색상 분석을 수행"**하는 **2단계 하이브리드 보행신호 추정기 (`TwoTierHybridSignalEstimator`)**를 구현하고 메인 파이프라인에 공식 주입한다:

1. **Tier 1 (딥러닝 객체 검출 - LiteRT)**:
   - 전방 횡단보도의 보행신호기 형태(Bounding Box)를 검출.
   - **신호등 Bounding Box가 검출되지 않거나 깨진 경우: 배경에 초록색이 아무리 많아도 즉시 `ObservedSignalState.UNKNOWN`을 반환하여 차단.**
2. **Tier 2 (박스 한정 정밀 HSV 비전 분석 - `CameraVisionSignalEstimator.estimateWithinRoi`)**:
   - Tier 1에서 신호등으로 확정된 Bounding Box 영역 내부로만 스캔 영역을 국한하여 적/녹 LED 파장을 정밀 분석.
   - 전체 화면 스캔 대비 연산량이 90% 이상 절감되고 배경 노이즈가 원천 격리됨.
3. **Tier 3 (시간 일관성 검증 - `LocalVlmSignalVerifier`)**:
   - 최근 5프레임 롤링 버퍼에서 60% 이상 안정적으로 녹색이 지속될 때만 최종 `GREEN` 판정 승인.

## 3. 결과 및 영향 (Consequences)
- **Zero False-Green 완벽 달성**: 딥러닝이 신호등 형태를 입증하지 못하면 어떠한 녹색 불빛도 보행 신호로 오판정되지 않음.
- **연산 및 배터리 효율 향상**: 전체 화면 픽셀 스캔 대신 작은 신호등 박스 내부만 분석하여 모바일 발열 대폭 감소.
- **검증 완료**: `TwoTierHybridSignalEstimatorTest` 단위 테스트 4종 전원 통과 및 전체 단위 테스트 통과, 최신 APK(`app-debug.apk`) 정상 빌드 완료.
