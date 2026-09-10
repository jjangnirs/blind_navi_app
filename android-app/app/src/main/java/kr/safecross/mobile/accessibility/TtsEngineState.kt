package kr.safecross.mobile.accessibility

/**
 * TextToSpeech 엔진 상태 (SR-F-075 준수).
 *
 * 초기화 실패, 언어 미지원, 오디오 포커스 실패를 상태 머신으로 명확히 표현하여,
 * TTS 실패 시에도 TalkBack 라이브 영역 또는 화면 고대비 배너로 안전하게 대체할 수 있도록 합니다.
 */
enum class TtsEngineState(val isUsable: Boolean, val userDescription: String) {
    UNINITIALIZED(false, "음성 엔진 초기화 전"),
    INITIALIZING(false, "음성 엔진 초기화 중"),
    READY(true, "음성 안내 준비 완료"),
    LANG_MISSING_DATA(false, "한국어 음성 데이터가 기기에 설치되어 있지 않습니다"),
    LANG_NOT_SUPPORTED(false, "기기에서 한국어 음성을 지원하지 않습니다"),
    INIT_FAILED(false, "음성 엔진 초기화에 실패했습니다"),
    AUDIO_FOCUS_FAILED(false, "오디오 포커스를 획득하지 못했습니다")
}
