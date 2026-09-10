package kr.safecross.mobile.accessibility

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

interface VoiceAnnouncer {
    val engineState: StateFlow<TtsEngineState>
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH, utteranceId: String? = null)
    fun stop()
    fun shutdown()
}

/**
 * Android TextToSpeech 및 오디오 포커스 중재 헬퍼 (SR-F-074~076, TRD 4.8 준수).
 */
class TtsAnnouncementHelper(
    context: Context,
    private val onInitComplete: ((Boolean) -> Unit)? = null
) : VoiceAnnouncer, TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _engineState = MutableStateFlow(TtsEngineState.INITIALIZING)
    override val engineState: StateFlow<TtsEngineState> = _engineState.asStateFlow()

    private val pendingUtterances = mutableListOf<Pair<String, Int>>()
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        setupUtteranceListener()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.KOREAN)
            when (result) {
                TextToSpeech.LANG_MISSING_DATA -> {
                    Log.w("TtsAnnouncementHelper", "한국어 음성 데이터 누락")
                    _engineState.value = TtsEngineState.LANG_MISSING_DATA
                    onInitComplete?.invoke(false)
                }
                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    Log.w("TtsAnnouncementHelper", "한국어 미지원")
                    _engineState.value = TtsEngineState.LANG_NOT_SUPPORTED
                    onInitComplete?.invoke(false)
                }
                else -> {
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.0f)
                    _engineState.value = TtsEngineState.READY
                    onInitComplete?.invoke(true)

                    // 대기 중인 발화 처리
                    synchronized(pendingUtterances) {
                        for ((text, queueMode) in pendingUtterances) {
                            speak(text, queueMode)
                        }
                        pendingUtterances.clear()
                    }
                }
            }
        } else {
            Log.e("TtsAnnouncementHelper", "TextToSpeech 초기화 실패 (status: $status)")
            _engineState.value = TtsEngineState.INIT_FAILED
            onInitComplete?.invoke(false)
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                abandonAudioFocus()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                abandonAudioFocus()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                abandonAudioFocus()
            }
        })
    }

    override fun speak(text: String, queueMode: Int, utteranceId: String?) {
        if (text.isBlank()) return

        if (_engineState.value != TtsEngineState.READY) {
            if (_engineState.value == TtsEngineState.INITIALIZING) {
                synchronized(pendingUtterances) {
                    pendingUtterances.add(text to queueMode)
                }
            }
            return
        }

        // 주변 소리 청취 방해를 방지하기 위해 Ducking 오디오 포커스 요청 (TRD 4.8)
        requestAudioFocus()

        val id = utteranceId ?: "tts_${System.currentTimeMillis()}"
        tts?.speak(text, queueMode, null, id)
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                            stop()
                        }
                    }
                    .build()

                val res = audioManager?.requestAudioFocus(audioFocusRequest!!)
                if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.w("TtsAnnouncementHelper", "오디오 포커스 획득 실패 (may duck)")
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.e("TtsAnnouncementHelper", "오디오 포커스 요청 예외", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    override fun stop() {
        tts?.stop()
        abandonAudioFocus()
    }

    override fun shutdown() {
        try {
            stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("TtsAnnouncementHelper", "TTS shutdown 오류", e)
        } finally {
            tts = null
            _engineState.value = TtsEngineState.UNINITIALIZED
        }
    }
}
