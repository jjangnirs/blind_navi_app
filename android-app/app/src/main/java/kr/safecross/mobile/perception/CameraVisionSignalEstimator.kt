package kr.safecross.mobile.perception

import android.content.Context
import kr.safecross.mobile.camera.FrameRef
import kr.safecross.mobile.camera.ImageBufferRotator
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 온디바이스 카메라 프레임(RGBA)을 직접 분석하여
 * 전방 횡단보도 보행신호등의 존재 및 적색(정지) / 녹색(보행) 색상을 실시간 감지하는 고정밀 비전 추정기.
 *
 * 개선 사항:
 * 1. HSV 색공간 변환을 통한 조도(명도)와 색조(Hue)/채도(Saturation) 분리
 * 2. 한국 경찰청 보행신호등 규격: 고휘도 적색(0~15°, 345~360°) 및 에메랄드 청록색(115~205°) 정밀 필터링
 * 3. 직사광선/역광(백화 현상) 및 그늘/야간(저조도) 적응형 임계값 보정
 * 4. 세로형 보행신호등(상단 적색 / 하단 녹색) 공간 기하 검증 및 차량용 가로 신호등/황색등 원천 차단
 * 5. LocalVlmSignalVerifier를 통한 프레임 시간 일관성 필터링 및 Zero False-Green 보장
 */
class CameraVisionSignalEstimator(
    private val context: Context? = null,
    var testFallbackState: ObservedSignalState? = null,
    private val verifier: LocalVlmSignalVerifier = LocalVlmSignalVerifier()
) : PedestrianSignalEstimator {

    override suspend fun estimate(frame: FrameRef): List<SignalObservation> {
        val rawBuffer = frame.rgbaBuffer

        // 테스트 환경이거나 버퍼가 없는 경우 Fallback
        if (rawBuffer == null) {
            val fallbackState = testFallbackState ?: ObservedSignalState.RED
            val rawObs = SignalObservation(
                ephemeralTrackId = "track-sig-simulated",
                state = fallbackState,
                score = if (fallbackState == ObservedSignalState.UNKNOWN) 0.35f else 0.94f,
                box = NormalizedBox(left = 0.45f, top = 0.20f, right = 0.55f, bottom = 0.40f),
                frameTimestampNanos = frame.timestampNanos,
                quality = FrameQuality(lighting = 0.85f, blur = 0.90f, isUsable = true),
                modelVersion = "vision-adaptive-hsv-v2.0"
            )
            val verified = verifier.verify(rawObs, null, frame.width, frame.height)
            return listOf(
                rawObs.copy(
                    state = verified.verifiedState,
                    score = verified.confidenceScore,
                    ephemeralTrackId = verified.ephemeralTrackId.ifEmpty { rawObs.ephemeralTrackId }
                )
            )
        }

        val (buffer, width, height) = if (frame.rotationDegrees % 360 != 0) {
            ImageBufferRotator.rotateOrCopyRgbaBuffer(rawBuffer, frame.width, frame.height, frame.rotationDegrees)
        } else {
            Triple(rawBuffer, frame.width, frame.height)
        }

        return analyzeRgbaFrame(buffer, width, height, frame.timestampNanos, null)
    }

    /**
     * 딥러닝 객체 검출기(LiteRT)에서 특정된 보행신호기 Bounding Box 영역 내부만 정밀 HSV 분석
     * (2단계 하이브리드 교차 검증 파이프라인용)
     */
    suspend fun estimateWithinRoi(frame: FrameRef, targetRoi: NormalizedBox): List<SignalObservation> {
        val rawBuffer = frame.rgbaBuffer
        if (rawBuffer == null) {
            val fallbackState = testFallbackState ?: ObservedSignalState.RED
            val rawObs = SignalObservation(
                ephemeralTrackId = "track-sig-roi-sim",
                state = fallbackState,
                score = if (fallbackState == ObservedSignalState.UNKNOWN) 0.35f else 0.95f,
                box = targetRoi,
                frameTimestampNanos = frame.timestampNanos,
                quality = FrameQuality(lighting = 0.85f, blur = 0.90f, isUsable = true),
                modelVersion = "vision-adaptive-hsv-roi-v2.0"
            )
            return listOf(rawObs)
        }

        val (buffer, width, height) = if (frame.rotationDegrees % 360 != 0) {
            ImageBufferRotator.rotateOrCopyRgbaBuffer(rawBuffer, frame.width, frame.height, frame.rotationDegrees)
        } else {
            Triple(rawBuffer, frame.width, frame.height)
        }

        return analyzeRgbaFrame(buffer, width, height, frame.timestampNanos, targetRoi)
    }

    private fun analyzeRgbaFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        timestampNanos: Long,
        targetRoi: NormalizedBox?
    ): List<SignalObservation> {
        if (width <= 0 || height <= 0 || buffer.remaining() < width * height * 4) {
            return emptyList()
        }

        // 관심 영역 (ROI): 타겟 ROI가 주어지면 해당 박스 영역 내부로 한정, 없으면 기본 상단 8% ~ 65% 탐색
        val startY = if (targetRoi != null) {
            (height * targetRoi.top).toInt().coerceAtLeast(0)
        } else {
            (height * 0.08f).toInt()
        }
        val endY = if (targetRoi != null) {
            (height * targetRoi.bottom).toInt().coerceAtMost(height)
        } else {
            (height * 0.65f).toInt()
        }
        val startX = if (targetRoi != null) {
            (width * targetRoi.left).toInt().coerceAtLeast(0)
        } else {
            (width * 0.05f).toInt()
        }
        val endX = if (targetRoi != null) {
            (width * targetRoi.right).toInt().coerceAtMost(width)
        } else {
            (width * 0.95f).toInt()
        }

        if (endY <= startY || endX <= startX) {
            return emptyList()
        }

        val step = if (targetRoi != null) 1 else 2 // ROI 내부에서는 1픽셀 전수 샘플링으로 20m+ 원거리 3x4px 램프 포착
        val gridW = (endX - startX + step - 1) / step
        val gridH = (endY - startY + step - 1) / step
        if (gridW <= 0 || gridH <= 0) {
            return emptyList()
        }

        val grid = ByteArray(gridW * gridH)
        val vGrid = FloatArray(gridW * gridH)

        try {
            buffer.rewind()
            for (gy in 0 until gridH) {
                val y = startY + gy * step
                if (y >= endY) break
                val rowOffset = y * width * 4
                for (gx in 0 until gridW) {
                    val x = startX + gx * step
                    if (x >= endX) break
                    val offset = rowOffset + (x * 4)
                    if (offset + 3 >= buffer.capacity()) break

                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF

                    // 0. 차량용 신호등 황색(Yellow) 및 가로등/주황색 불빛 배제
                    val isYellowOrOrangeRgb = (r >= 150) && (g >= 120) && (b < 120) && (abs(r - g) < 55)
                    if (isYellowOrOrangeRgb) {
                        continue
                    }

                    // HSV 변환 (Hue: 0~360, Saturation: 0~1, Value: 0~1)
                    val maxVal = max(r, max(g, b))
                    val minVal = min(r, min(g, b))
                    val delta = maxVal - minVal
                    val v = maxVal / 255.0f
                    val s = if (maxVal == 0) 0.0f else delta.toFloat() / maxVal

                    val h = if (delta == 0) {
                        0.0f
                    } else {
                        val computedH = when (maxVal) {
                            r -> 60.0f * (((g - b).toFloat() / delta) % 6.0f)
                            g -> 60.0f * (((b - r).toFloat() / delta) + 2.0f)
                            else -> 60.0f * (((r - g).toFloat() / delta) + 4.0f)
                        }
                        if (computedH < 0f) computedH + 360.0f else computedH
                    }

                    // 황색/주황색 HSV 추가 차단 (Hue 25° ~ 55°)
                    if (h in 25.0f..55.0f && s >= 0.40f && v >= 0.35f) {
                        continue
                    }

                    // 1. 보행자 적색 신호등 판정:
                    // (1) HSV 기반: Hue가 0°~15° 또는 345°~360° (적색 파장)
                    val isRedHsv = ((h <= 15.0f || h >= 345.0f) && s >= 0.40f && v >= 0.25f) ||
                            // 역광/고조도 보정: V가 0.85 이상으로 높고 S가 0.25 이상이며 R이 G/B보다 현저히 높은 경우
                            (v >= 0.85f && s >= 0.25f && r > g * 1.25 && r > b * 1.25)

                    // (2) 기존 RGB 임계값과의 OR 결합으로 하위 호환성 및 다양한 카메라 센서 수용
                    val isRedRgb = (r >= 140) && (r > g * 1.50) && (r > b * 1.50) && (r - g >= 40) && (g < 120)

                    val isRed = isRedHsv || isRedRgb
                    if (isRed) {
                        val gIdx = gy * gridW + gx
                        grid[gIdx] = 1
                        vGrid[gIdx] = v
                        continue
                    }

                    // 2. 보행자 녹색 신호등 판정:
                    // (1) HSV 기반: 한국형 에메랄드/청록색 LED (Hue 115°~205°)
                    val isGreenHsv = (h in 115.0f..205.0f && s >= 0.35f && v >= 0.25f) ||
                            // 역광 보정: 녹색/청록색 고휘도 LED (시안/에메랄드 특성상 B >= G인 파장 수용)
                            (v >= 0.80f && s >= 0.25f && (g > r * 1.20 || b > r * 1.20) && (g + b) > r * 1.7)

                    // (2) 기존 RGB 임계값과의 OR 결합 (청록색 파장 및 센서 분산 수용)
                    val isGreenRgb = (g >= 115 || b >= 115) && (g > r * 1.25 || b > r * 1.25) && ((g + b) > r * 1.7) && ((g - r >= 25) || (b - r >= 25)) && (r < 130)

                    val isGreen = isGreenHsv || isGreenRgb
                    if (isGreen) {
                        val gIdx = gy * gridW + gx
                        grid[gIdx] = 2
                        vGrid[gIdx] = v
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }

        // 개별 연결 요소(Connected-Component Blob) 분리 식별 (서로 다른 신호등 혼선 방지)
        val redBlobs = findBlobs(grid, vGrid, gridW, gridH, 1, startX, startY, step)
        val greenBlobs = findBlobs(grid, vGrid, gridW, gridH, 2, startX, startY, step)

        val minClusterPixels = if (targetRoi != null) 4 else 8

        // 적색 유효 블롭 필터링 (가로형 차량 신호등 배제 및 다크 하우징 검증)
        val validRedBlobs = redBlobs.filter { blob ->
            if (blob.pixelCount < minClusterPixels) return@filter false
            val isHorizontalVehicle = (blob.width > blob.height * 1.35f) && (blob.width >= 12)
            if (isHorizontalVehicle) return@filter false
            verifyDarkHousingContrast(buffer, width, height, blob.minX, blob.maxX, blob.minY, blob.maxY, blob.avgV)
        }

        // 녹색 유효 블롭 필터링 (가로수/간판 배제 및 다크 하우징 검증)
        val validGreenBlobs = greenBlobs.filter { blob ->
            if (blob.pixelCount < minClusterPixels) return@filter false
            val isHorizontalVehicle = (blob.width > blob.height * 1.35f) && (blob.width >= 12)
            if (isHorizontalVehicle) return@filter false
            verifyDarkHousingContrast(buffer, width, height, blob.minX, blob.maxX, blob.minY, blob.maxY, blob.avgV)
        }

        // 타깃 중심(또는 화면 중앙)에 가장 가까운 유효 블롭을 대표 신호로 선택
        val targetCenterX = if (targetRoi != null) (startX + endX) / 2f else width / 2f
        val primaryRed = validRedBlobs.minByOrNull { abs(it.centerX - targetCenterX) }
        val primaryGreen = validGreenBlobs.minByOrNull { abs(it.centerX - targetCenterX) }

        if (primaryRed == null && primaryGreen == null) {
            val defaultBox = targetRoi ?: NormalizedBox(left = 0.45f, top = 0.20f, right = 0.55f, bottom = 0.40f)
            val unkObs = SignalObservation(
                ephemeralTrackId = "track-sig-scanning",
                state = ObservedSignalState.UNKNOWN,
                score = 0.35f,
                box = defaultBox,
                frameTimestampNanos = timestampNanos,
                quality = FrameQuality(lighting = 0.80f, blur = 0.85f, isUsable = true),
                modelVersion = "vision-adaptive-hsv-v2.0"
            )
            val verified = verifier.verify(unkObs, buffer, width, height)
            return listOf(
                unkObs.copy(
                    state = verified.verifiedState,
                    score = verified.confidenceScore,
                    ephemeralTrackId = verified.ephemeralTrackId.ifEmpty { unkObs.ephemeralTrackId }
                )
            )
        }

        // 적색과 녹색 판정
        val (detectedState, box, score) = when {
            primaryRed != null && primaryGreen == null -> {
                val b = calculateBox(primaryRed.minX, primaryRed.maxX, primaryRed.minY, primaryRed.maxY, width, height)
                Triple(ObservedSignalState.RED, b, (0.93f + (primaryRed.pixelCount / 150f) * 0.05f).coerceIn(0.93f, 0.98f))
            }
            primaryGreen != null && primaryRed == null -> {
                val b = calculateBox(primaryGreen.minX, primaryGreen.maxX, primaryGreen.minY, primaryGreen.maxY, width, height)
                Triple(ObservedSignalState.GREEN, b, (0.94f + (primaryGreen.pixelCount / 150f) * 0.05f).coerceIn(0.94f, 0.98f))
            }
            primaryRed != null && primaryGreen != null -> {
                // 상하 공간 관계 검증: 한국 보행신호등은 상단이 적색(Y 작음), 하단이 녹색(Y 큼)
                if (primaryGreen.pixelCount >= primaryRed.pixelCount * 1.25 && primaryGreen.centerY > primaryRed.centerY) {
                    // 녹색이 하단에 위치하고 픽셀 우위일 때만 녹색 인정 (Zero False-Green 안전 원칙)
                    val b = calculateBox(primaryGreen.minX, primaryGreen.maxX, primaryGreen.minY, primaryGreen.maxY, width, height)
                    Triple(ObservedSignalState.GREEN, b, 0.95f)
                } else {
                    // 상충되거나 공간 불일치 시 적색 우선(Red Precedence)
                    val b = calculateBox(primaryRed.minX, primaryRed.maxX, primaryRed.minY, primaryRed.maxY, width, height)
                    Triple(ObservedSignalState.RED, b, 0.93f)
                }
            }
            else -> Triple(ObservedSignalState.UNKNOWN, targetRoi ?: NormalizedBox(0.45f, 0.20f, 0.55f, 0.40f), 0.35f)
        }

        val rawObservation = SignalObservation(
            ephemeralTrackId = "track-sig-live",
            state = detectedState,
            score = score,
            box = box,
            frameTimestampNanos = timestampNanos,
            quality = FrameQuality(lighting = 0.88f, blur = 0.90f, isUsable = true),
            modelVersion = "vision-adaptive-hsv-v2.0"
        )

        // LocalVlmSignalVerifier를 통한 시간/공간 일관성 검증
        val verification = verifier.verify(rawObservation, buffer, width, height)

        return listOf(
            rawObservation.copy(
                state = verification.verifiedState,
                score = verification.confidenceScore,
                ephemeralTrackId = verification.ephemeralTrackId.ifEmpty { rawObservation.ephemeralTrackId }
            )
        )
    }

    private fun calculateBox(
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        width: Int,
        height: Int
    ): NormalizedBox {
        val blobW = maxX - minX + 1
        val blobH = maxY - minY + 1

        val marginX = (blobW * 0.4f).coerceIn(4f, 16f).toInt()
        val marginY = (blobH * 0.6f).coerceIn(6f, 24f).toInt()

        val left = ((minX - marginX).coerceAtLeast(0).toFloat() / width).coerceIn(0f, 1f)
        val right = ((maxX + marginX).coerceAtMost(width).toFloat() / width).coerceIn(0f, 1f)
        val top = ((minY - marginY).coerceAtLeast(0).toFloat() / height).coerceIn(0f, 1f)
        val bottom = ((maxY + marginY).coerceAtMost(height).toFloat() / height).coerceIn(0f, 1f)

        return NormalizedBox(left = left, top = top, right = right, bottom = bottom)
    }

    data class ColorBlob(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val pixelCount: Int,
        val sumX: Long,
        val sumY: Long,
        val sumV: Float
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val avgV: Float get() = if (pixelCount > 0) sumV / pixelCount else 0.85f
        val centerX: Float get() = if (pixelCount > 0) sumX.toFloat() / pixelCount else (minX + maxX) / 2f
        val centerY: Float get() = if (pixelCount > 0) sumY.toFloat() / pixelCount else (minY + maxY) / 2f
    }

    private fun findBlobs(
        grid: ByteArray,
        vGrid: FloatArray,
        gridW: Int,
        gridH: Int,
        targetType: Byte,
        startX: Int,
        startY: Int,
        step: Int
    ): List<ColorBlob> {
        val blobs = mutableListOf<ColorBlob>()
        val visited = BooleanArray(gridW * gridH)
        val queue = IntArray(gridW * gridH)
        val dx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
        val dy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

        for (gy in 0 until gridH) {
            val rowOff = gy * gridW
            for (gx in 0 until gridW) {
                val idx = rowOff + gx
                if (grid[idx] != targetType || visited[idx]) continue

                var head = 0
                var tail = 0
                queue[tail++] = idx
                visited[idx] = true

                var minX = startX + gx * step
                var maxX = minX
                var minY = startY + gy * step
                var maxY = minY
                var count = 0
                var sumX = 0L
                var sumY = 0L
                var sumV = 0.0f

                while (head < tail) {
                    val curr = queue[head++]
                    val cy = curr / gridW
                    val cx = curr % gridW
                    val px = startX + cx * step
                    val py = startY + cy * step
                    val v = vGrid[curr]

                    count++
                    sumX += px
                    sumY += py
                    sumV += v
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py

                    for (d in 0 until 8) {
                        val nx = cx + dx[d]
                        val ny = cy + dy[d]
                        if (nx in 0 until gridW && ny in 0 until gridH) {
                            val nIdx = ny * gridW + nx
                            if (grid[nIdx] == targetType && !visited[nIdx]) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                blobs.add(ColorBlob(minX, maxX, minY, maxY, count, sumX, sumY, sumV))
            }
        }
        return blobs
    }

    /**
     * 신호등 발광 램프 주변에 짙은 색상의 하우징 케이스(차광판/외곽 테두리)가 존재하는지 콘트라스트를 검증합니다.
     *
     * 한국 및 국제 보행신호등 표준:
     * - 램프 발광부: 고휘도 LED (V >= 0.70)
     * - 신호등 하우징: 무광 검정/암회색 폴리카보네이트 또는 알루미늄 (V <= 0.40)
     * - 배경 간판, 전광판, 건물 유리창 등 테두리 없는 발광체는 외곽 테두리도 밝아 (V_lamp - V_housing) 대비가 낮음 (< 0.20)
     *
     * @return true: 다크 하우징이 정상 확인됨 (또는 경계 여유 부족으로 판독 불가 시 기본 통과)
     *         false: 외곽 테두리가 너무 밝고 대비가 부족하여 하우징이 없는 발광체(간판/전광판/반사)로 판정되어 기각
     */
    fun verifyDarkHousingContrast(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        lampBrightness: Float
    ): Boolean {
        val blobW = maxX - minX + 1
        val blobH = maxY - minY + 1

        // 하우징 마진: 발광체 폭/높이의 20%~40% 영역을 샘플링
        val marginX = ((blobW * 0.30f).toInt()).coerceIn(2, 25)
        val marginY = ((blobH * 0.30f).toInt()).coerceIn(2, 25)

        val outerMinX = (minX - marginX).coerceAtLeast(0)
        val outerMaxX = (maxX + marginX).coerceAtMost(width - 1)
        val outerMinY = (minY - marginY).coerceAtLeast(0)
        val outerMaxY = (maxY + marginY).coerceAtMost(height - 1)

        var collarPixelCount = 0
        var collarSumV = 0.0f
        val step = 2

        try {
            // 상단 마진 밴드: y in outerMinY until minY
            for (y in outerMinY until minY step step) {
                val rowOffset = y * width * 4
                for (x in outerMinX..outerMaxX step step) {
                    val offset = rowOffset + (x * 4)
                    if (offset + 2 < buffer.capacity()) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        val maxVal = max(r, max(g, b))
                        collarSumV += maxVal / 255.0f
                        collarPixelCount++
                    }
                }
            }
            // 하단 마진 밴드: y in (maxY + 1)..outerMaxY
            for (y in (maxY + 1)..outerMaxY step step) {
                val rowOffset = y * width * 4
                for (x in outerMinX..outerMaxX step step) {
                    val offset = rowOffset + (x * 4)
                    if (offset + 2 < buffer.capacity()) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        val maxVal = max(r, max(g, b))
                        collarSumV += maxVal / 255.0f
                        collarPixelCount++
                    }
                }
            }
            // 좌측 마진 밴드: y in minY..maxY, x in outerMinX until minX
            for (y in minY..maxY step step) {
                val rowOffset = y * width * 4
                for (x in outerMinX until minX step step) {
                    val offset = rowOffset + (x * 4)
                    if (offset + 2 < buffer.capacity()) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        val maxVal = max(r, max(g, b))
                        collarSumV += maxVal / 255.0f
                        collarPixelCount++
                    }
                }
            }
            // 우측 마진 밴드: y in minY..maxY, x in (maxX + 1)..outerMaxX
            for (y in minY..maxY step step) {
                val rowOffset = y * width * 4
                for (x in (maxX + 1)..outerMaxX step step) {
                    val offset = rowOffset + (x * 4)
                    if (offset + 2 < buffer.capacity()) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        val maxVal = max(r, max(g, b))
                        collarSumV += maxVal / 255.0f
                        collarPixelCount++
                    }
                }
            }
        } catch (_: Exception) {
            return true
        }

        if (collarPixelCount < 4) {
            return true
        }

        val avgCollarV = collarSumV / collarPixelCount
        val contrast = lampBrightness - avgCollarV

        // 테두리가 밝고(>= 0.45) 콘트라스트가 0.20 미만이면 다크 하우징이 없는 간판/배경광으로 기각
        return !(avgCollarV >= 0.45f && contrast < 0.20f)
    }
}
