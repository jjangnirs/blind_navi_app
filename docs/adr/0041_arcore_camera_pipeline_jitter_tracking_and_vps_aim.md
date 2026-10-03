# ADR-0041: ARCore 카메라 파이프라인, 공간 흔들림 추적 및 VPS 횡단 조준

## 1. 배경 및 문제 의식 (Context)
- 횡단보도 신호 확인 화면은 CameraX 기반이었으나, 길안내 시 활용하던 ARCore Geospatial의 정밀한 공간 자세(Quaternion), 깊이(Depth), VPS 3D 위치를 횡단보도 카메라 화면에서도 직접 활용하여 신호등 조준 및 손떨림 추적을 고도화할 필요가 제기됨.
- 길안내 백그라운드 세션과 횡단보도 화면 간의 카메라 하드웨어 점유 충돌을 방지하고, ARCore 미지원/인증 실패 기기를 위한 CameraX 무중단 대체(Fallback) 구조가 요구됨.

## 2. 결정 사항 (Decisions)

### 2.1 ARCore 카메라 파이프라인 (`ArCoreCameraPipeManager`, `ArBackgroundRenderer`, `YuvToRgba`)
- OpenGL ES 배경 렌더러를 통해 ARCore 카메라 프리뷰를 화면 전체에 렌더링.
- CPU 이미지(`YUV_420_888`)를 `RGBA_8888`로 실시간 변환하여 YOLOv8 및 OpenCV HSV 분석 파이프라인에 공급.
- ARCore 세션 생성 실패 시 CameraX `PreviewView`로 즉시 무중단 대체.

### 2.2 공간 정보 및 손떨림 추적 (`ArFrameContext`)
- 카메라 쿼터니언 회전 정보로부터 월드 시선 방향(`worldDirection`) 및 화면 재투영(`project`)을 계산.
- 보행자의 손떨림이나 보행 충격으로 카메라가 흔들려도 직전 프레임에서 발견한 신호등의 월드 시선 방향을 유지하여 추적 안정성 대폭 향상.

### 2.3 VPS 기반 횡단 조준 및 섀도 기록
- ARCore VPS 정밀 좌표와 방향을 실시간으로 반영하여 횡단보도 맞은편 신호등 조준 방위각/거리를 보정.
- ARCore 깊이(Depth) 및 장면 라벨(Scene Semantics)은 직접 판정에 쓰지 않고 섀도 기록(`[VISION]`)으로 남겨 추후 연구 데이터로 활용 (신호등 위치가 "건물·나무"로 분류되는 한계 확인).

### 2.4 세션 전환 안전성 (`MainActivity`, `GeospatialHeadingProvider`)
- 카메라 신호 확인 화면 진입 직전 길안내 백그라운드 VPS 세션을 닫아 카메라 자원을 양보.
- 신호 확인 화면 종료 후 길안내 복귀 시 1초 간격 최대 5회 재시도를 통해 세션을 안전하게 복구.

## 3. 검증
- 단위 테스트 `ArCameraContextTest`(5건): 월드 방향과 화면 투영 왕복 변환, 카메라 패닝 시 화면 상 타깃 이동, 세로/센서 회전 변환, Geospatial 카메라 시선 방위각 및 앙각 계산.
- 현장 테스트 (2026-10-03 오전): 5회 횡단 모두 ARCore 카메라 정상 구동, 조준 계산의 90%를 VPS로 성공 수행.
- APK: `release/app-debug-1003-v45.apk`.
