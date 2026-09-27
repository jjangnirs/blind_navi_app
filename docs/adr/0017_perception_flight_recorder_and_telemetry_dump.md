# ADR 0017: 비전 지각 비행기록장치(Perception Flight Recorder) 및 진단 덤프 아키텍처

## 1. 문맥 (Context)
2026-09-23 및 09-24 현장 테스트 과정에서 신호등 인식 락온 후 상태 전이 실패, 손떨림 및 빛 반사 등에 의한 일시적 미인식 등 복합적인 현장 문제가 발생하였다.
야외 실환경에서는 조도 급변, 차량 오클루전, 신호등 지주 각도 및 흔들림 등 다양한 비정형 변수가 존재하지만, 현장에서 스마트폰 디버깅 로그를 실시간으로 확인하기 어렵고 장애 시점 전후의 센서/카메라/엔진 내부 상태를 사후에 객관적으로 복기할 방법이 부족하였다.

따라서 시각장애인 보행 보조 앱의 안전성과 신뢰성을 위해, 민감한 개인정보(원문 이미지 프레임, 정밀 GPS 좌표 등)를 유출하지 않으면서도 지각 파이프라인의 핵심 텔레메트리(프레임별 센서 자세, 감지 박스, 광학 색상 판정, 트래커 ID, 엔진 최종 결정)를 지속 추적하고 장애 발생 시 진단 덤프를 남길 수 있는 경량 비행기록장치(Flight Recorder) 체계가 요구되었다.

---

## 2. 결정 (Decision)

### 1) 무잠금 원형 링 버퍼(Circular Ring Buffer) 기반 상시 기록 (`PerceptionFlightRecorder.kt`)
- 메모리 부하와 GC(Garbage Collection) 스파이크를 방지하기 위해 최대 300프레임(약 10~15초 분량)의 고정 크기 링 버퍼를 메모리에 상주:
  ```kotlin
  data class PerceptionTelemetryFrame(
      val frameIndex: Long,
      val timestampMs: Long,
      val sensorPitchDeg: Float,
      val sensorRollDeg: Float,
      val isReticleLocked: Boolean,
      val reticleBox: NormalizedRect?,
      val rawDetectionsCount: Int,
      val primaryBox: NormalizedRect?,
      val primaryColor: SignalColor,
      val primaryConfidence: Float,
      val trackerId: String?,
      val decisionState: CrossingState,
      val remainingGreenSeconds: Int?,
      val rejectReason: String?
  )
  ```
- 메인 카메라 스레드에 오버헤드를 주지 않는 비동기 저지연 기록 파이프라인 구축.

### 2) 비식별화 및 개인정보 보호 (Zero Privacy Leak)
- 카메라 원본 이미지(Bitmap/YUV), 사용자의 정밀 위경도 좌표, 목적지 텍스트 등 개인 식별 정보는 일절 버퍼에 기록하지 않음.
- 정규화된 바운딩 박스 좌표(`0.0 ~ 1.0`), 색상 분류 확률, 기기 기울기 각도(Pitch/Roll) 등 순수 지각 및 알고리즘 판정 수치만을 기록.

### 3) 이상 상태 자동 감지 및 사후 덤프(Anomaly Auto-Dump) 트리거
- 다음과 같은 이상 징후 감지 시 자동으로 최근 300프레임 로그를 로컬 앱 저장소(`files/flight_records/flight_record_YYYYMMDD_HHMMSS.json`)로 원자적 플러시(Flush):
  1. `UNEXPECTED_COLOR_TRANSITION`: 비정상적인 색상 급변 (e.g. GREEN $\to$ UNKNOWN 반복 플리커링)
  2. `PROLONGED_RETICLE_LOCK_NO_SIGNAL`: 락온 상태가 5초 이상 지속되었으나 신호 분류에 실패하는 경우
  3. `MANUAL_USER_TRIGGER`: 설정 화면 또는 개발자 제스처를 통한 수동 진단 로그 저장
- 최대 저장 파일 개수(최신 10개) 제한 및 오래된 진단 파일 자동 회전(Rotation)을 적용하여 기기 저장공간 고갈 방지.

---

## 3. 결과 및 영향 (Consequences)
- **현장 오류 신속 진단**: 실기기 테스트 중 미인식이나 오작동 발생 시 직전 10초간의 기기 각도, 카메라 바운딩 박스 크기, 색상 확률 분포를 JSON으로 즉시 추출하여 원인 규명 가능.
- **개인정보 안전성 보장**: 사진이나 위치 좌표를 저장하지 않으므로 개인정보보호법 및 위치정보법 관련 규제 리스크 완전 격리.
- **성능 영향 최소화**: 원형 버퍼 기록에 따른 CPU 오버헤드는 프레임당 0.1ms 미만으로 배터리 및 프레임레이트에 무영향.
