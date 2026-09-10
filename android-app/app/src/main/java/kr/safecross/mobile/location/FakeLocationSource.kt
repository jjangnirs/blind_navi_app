package kr.safecross.mobile.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 단위 테스트 및 시뮬레이션용 모의 위치 공급원.
 */
class FakeLocationSource(
    private var gpsEnabled: Boolean = true,
    private var finePermissionGranted: Boolean = true
) : LocationSource {

    private val _locationFlow = MutableSharedFlow<LocationSample>(extraBufferCapacity = 64)
    override val locationUpdates: Flow<LocationSample> = _locationFlow.asSharedFlow()

    private var playbackJob: Job? = null
    private var isTracking = false

    override fun hasFineLocationPermission(): Boolean = finePermissionGranted

    fun setFineLocationPermission(granted: Boolean) {
        finePermissionGranted = granted
    }

    override fun isGpsEnabled(): Boolean = gpsEnabled

    fun setGpsEnabled(enabled: Boolean) {
        gpsEnabled = enabled
    }

    override fun startTracking() {
        isTracking = true
    }

    override fun stopTracking() {
        isTracking = false
        playbackJob?.cancel()
        playbackJob = null
    }

    /**
     * 특정 위치 샘플을 수동으로 즉시 방출합니다.
     */
    fun pushLocation(sample: LocationSample) {
        if (isTracking && gpsEnabled && finePermissionGranted) {
            _locationFlow.tryEmit(sample)
        }
    }

    /**
     * 위치 트레이스 목록을 일정 간격으로 순차 재생합니다.
     */
    fun playTrace(
        samples: List<LocationSample>,
        intervalMs: Long = 100L,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    ) {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            for (sample in samples) {
                if (!isTracking) break
                pushLocation(sample)
                delay(intervalMs)
            }
        }
    }
}
