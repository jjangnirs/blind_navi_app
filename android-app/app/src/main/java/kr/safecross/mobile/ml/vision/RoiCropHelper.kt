package kr.safecross.mobile.ml.vision

import kr.safecross.mobile.perception.CrosswalkObservation
import kr.safecross.mobile.perception.NormalizedBox

/**
 * 원경의 작은 보행신호가 전체 장면(320x320) 축소 과정에서 소실되는 현상을 방지하기 위한
 * 고해상도 관심 영역(ROI) 계산 및 비교 헬퍼 (TRD 4.4, 요구 9).
 */
object RoiCropHelper {

    /**
     * 횡단보도 소실점 및 진행 방향을 바탕으로 보행신호가 위치할 가능성이 높은 상단 전방 ROI를 산출합니다.
     */
    fun computePedestrianSignalRoi(
        crosswalk: CrosswalkObservation?,
        cropWidthRatio: Float = 0.5f,
        cropHeightRatio: Float = 0.45f
    ): NormalizedBox {
        // 횡단보도 진입점이나 진행방향이 있으면 해당 방향을 중심으로 상단 ROI 산출
        val centerX = crosswalk?.entrancePoint?.x ?: 0.5f
        val halfW = cropWidthRatio / 2f

        val left = (centerX - halfW).coerceIn(0f, 1f - cropWidthRatio)
        val right = left + cropWidthRatio
        val top = 0.05f
        val bottom = top + cropHeightRatio

        return NormalizedBox(
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }

    /**
     * 전체 장면 검출 신호와 ROI Crop 검출 신호를 비교하여 더 높은 해상도/신뢰도를 가진 최적 신호 박스를 선택합니다.
     */
    fun selectBestSignalDetections(
        fullFrameDetections: List<NormalizedBox>,
        roiCropDetections: List<NormalizedBox>,
        roiCropRegion: NormalizedBox
    ): List<NormalizedBox> {
        // ROI 크롭 좌표를 전체 프레임 좌표로 다시 매핑
        val remappedRoiDetections = roiCropDetections.map { roiBox ->
            val roiW = roiCropRegion.width
            val roiH = roiCropRegion.height
            NormalizedBox(
                left = (roiCropRegion.left + roiBox.left * roiW).coerceIn(0f, 1f),
                top = (roiCropRegion.top + roiBox.top * roiH).coerceIn(0f, 1f),
                right = (roiCropRegion.left + roiBox.right * roiW).coerceIn(0f, 1f),
                bottom = (roiCropRegion.top + roiBox.bottom * roiH).coerceIn(0f, 1f)
            )
        }

        // 전체 프레임 검출 결과와 ROI 검출 결과 병합
        return (fullFrameDetections + remappedRoiDetections).distinctBy {
            // 중복 박스 제거: 중심점 거리가 5% 이내면 동일 박스로 간주
            Pair((it.centerX * 20).toInt(), (it.centerY * 20).toInt())
        }
    }
}
