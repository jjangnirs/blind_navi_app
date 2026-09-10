# [ADR 0003] LiteRT 온디바이스 모델 통합 및 가속기 Fallback 아키텍처

## 상태
**승인됨 (Approved)** — 2026-09-09

## 문맥 및 배경
Safe Cross KR 모바일 앱은 시각장애인 보행자의 횡단보도 접근 시 카메라 영상을 바탕으로 **보행자 신호등(적색/녹색/확인불가) 및 횡단보도 형상(영역/진입점/진행방향)**을 실시간 온디바이스로 추정해야 합니다 (SR-F-042~054, TRD.md 4.5, 8장).

이 과정에서 보행자의 생명 안전을 위해 다음 엄격한 제약사항이 요구됩니다:
1. **개인정보 및 지연시간**: 프레임 영상 원문은 외부 서버로 전송되지 않고 기기 메모리에서만 처리되어야 하며(비행기 모드 작동), 지연이 누적되지 않아야 합니다.
2. **모델 무결성 및 킬스위치**: 위조·변조된 모델이나 라벨 순서가 뒤바뀐 모델은 즉시 감지되어 로드가 거부되어야 합니다.
3. **가속기 Fail-Safe**: GPU/NPU 가속기 초기화나 추론이 실패하더라도 앱이 비정상 종료되거나 잘못된 녹색(False-Green)이 발생하지 않고, 안전하게 CPU 또는 `UNKNOWN`으로 대체되어야 합니다.

---

## 결정 사항

### 1. 공식 LiteRT 권장 의존성 및 API 선정
Google AI Edge LiteRT(구 TensorFlow Lite 2.x)의 최신 표준 Android Kotlin 라이브러리를 채택합니다:
- **의존성**: `com.google.ai.edge.litert:litert:2.2.0`
- **우선 API**: **`CompiledModel`** (`com.google.ai.edge.litert.CompiledModel`)
  - 최신 런타임 표준으로 하드웨어 가속기(CPU, GPU, NPU)를 통합 관리하며 수동 delegate 설정 없이 최적화된 컴파일 그래프를 생성합니다.
- **Fallback API**: **`Interpreter`** (`com.google.ai.edge.litert.Interpreter` / `org.tensorflow.lite.Interpreter`)
  - CompiledModel 미지원 레거시 환경이나 데스크톱 테스트 환경을 위한 CPU 폴백 인터프리터를 병행 지원합니다.
- **공식 문서 및 레퍼런스 URL**:
  - LiteRT 개요: https://ai.google.dev/edge/litert
  - LiteRT Android 개발 가이드: https://ai.google.dev/edge/litert/android
  - CompiledModel Kotlin API 가이드: https://ai.google.dev/edge/litert/android/compiled_model

### 2. 하드웨어 가속 및 단계적 Fallback 전략 (SR-F-047)
하드웨어 자원 상태에 따라 다음 우선순위로 자동 전환합니다:
```text
[NPU/GPU 가속 시도] 
    └── 실패 시 ──> [CPU 베이스라인 자동 Fallback]
                          └── 실패 시 ──> [UNKNOWN 상태 즉시 출력 (크래시 없음)]
```
- 가속기 오류나 수치 불안정이 발생하더라도 절대 녹색(`GREEN`)으로 뒤집히지 않으며, 안전 상태기계는 즉시 `UNKNOWN`으로 축소하여 사용자에게 안전 경고를 제공합니다.

### 3. 모델 무결성 검증 및 계약 엔진 (SR-F-050, SR-F-051)
모델 바이너리를 메모리에 로드하기 전 `ModelContractValidator`가 다음 항목을 전수 검증합니다:
1. **SHA-256 해시 일치 검증**: 매니페스트(`manifest.json`)의 SHA-256 해시와 실제 `.tflite` 바이너리 스트리밍 해시 비교. 불일치 시 위조 모델로 간주하고 로드 차단.
2. **라벨 순서 무결성**: `[PEDESTRIAN_SIGNAL_RED, PEDESTRIAN_SIGNAL_GREEN, UNKNOWN]` 순서가 1:1 일치하는지 검증. 라벨 순서 전도 시 감지 및 차단.
3. **입/출력 텐서 형상(Shape)**: 입력 `1x320x320x3` (Float32/INT8) 및 출력 텐서 규격 검증.
4. **원격 킬스위치(`disabled: true`)**: 이상 모델 감지 시 운영자에 의해 배포된 킬스위치 플래그를 확인하여 즉시 비활성화.

### 4. 컴퓨터 비전 좌표계 복원 및 골든 테스트
- 640x480 카메라 프레임을 320x320 정사각 모델 입력으로 변환할 때 종횡비를 보존하는 **Letterbox 변환**을 적용합니다.
- 모델이 출력한 Bounding Box 및 Polygon 좌표는 `unletterbox` 역변환을 거쳐 **동일한 원본 프레임 정규화 좌표계 [0.0, 1.0]**로 복원됩니다.
- 신호기와 횡단보도 추정기가 동일 좌표계를 공유함을 정밀 기하 골든 테스트로 검증합니다.

### 5. 사용자 점수 비노출 원칙 (SR-F-048)
- 모델의 confidence score(예: 0.94)는 오직 내부 임계치 게이트 판정에만 사용되며, UI 화면이나 TTS 음성으로 "94% 안전합니다" 또는 "90% 확률로 건너세요" 등의 확률적 표현을 절대 노출하지 않습니다.
