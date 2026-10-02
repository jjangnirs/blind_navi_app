# VPS 측정 시험 앱 (`vpsprobe`)

ARCore Geospatial(VPS)가 현장에서 쓸 만한지 측정하는 별도 앱입니다 (ADR-0037). 메인 앱과 패키지가 달라(`kr.safecross.vpsprobe`, 앱 이름 "VPS 측정") 함께 설치해도 서로 영향이 없습니다.

## 준비
1. Google Cloud 콘솔에서 **ARCore API** 사용 설정 → **API 키** 발급 (`AIza…`, OAuth 클라이언트 ID 아님)
2. 키 제한: Android 앱(`kr.safecross.vpsprobe`, `kr.safecross.mobile` + 빌드 PC 디버그 서명 SHA-1), API 제한 = ARCore API
3. 저장소 루트 `.env`에 `ARCORE_API_KEY=...` 추가 (git에 올라가지 않음)
4. 빌드: `gradle :vpsprobe:assembleDebug` → `vpsprobe/build/outputs/apk/debug/vpsprobe-debug.apk`
5. 설치: 휴대폰 파일 관리자에서 설치가 막히면(Play 프로텍트) USB 디버깅 후 `adb install -r vpsprobe-debug.apk`

## 화면
- VPS 지원 여부("VPS 확인" 버튼), Earth 상태, VPS 위치·방향·고도 오차
- 휴대폰 GPS·나침반과의 위치·방향 차이
- 최근 30초 추적률, 오차 중앙값·90% 값, 판정(사용 가능: 위치 ≤5 m·방향 ≤5° / 제한적: ≤10 m·≤15° / 부적합)

## 측정 방법
1. 밖에서 "기록 시작" → 휴대폰을 세워 주변 건물을 10~20초 천천히 둘러보기
2. 횡단보도마다 "VPS 확인" → "지점 표시" → 1~2분 신호등 방향 비추기
3. 끝나면 "기록 중지" (필수 아님, 기록은 1초마다 바로 저장)
4. 기록 파일: `Android/data/kr.safecross.vpsprobe/files/logs/vps_probe_*.csv`

## 주의
- 측정 중 카메라 영상 특징이 Google VPS 서버로 전송됩니다.
- Geospatial에는 `play-services-location`이 반드시 포함되어야 합니다 (`21.3.0`; `21.4.0`은 프로젝트 Kotlin 2.0.21과 비호환).
