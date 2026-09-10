package kr.safecross.mobile

import kr.safecross.mobile.domain.model.ROUTE_DISCLAIMER_TEXT
import kr.safecross.mobile.domain.model.WalkingMode
import kr.safecross.mobile.ui.screens.destination.defaultDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySemanticsTest {

    @Test
    fun `walkingMode states provide distinct text labels and safety guidance for non-color feedback`() {
        // SR-NF-032: 색상만으로 상태 전달 금지. 4단계 보행 모드는 모두 고유한 텍스트 라벨과 안내 문구를 가짐
        val modes = WalkingMode.entries
        assertEquals(4, modes.size)

        val labels = modes.map { it.label }
        val descriptions = modes.map { it.description }
        val guidances = modes.map { it.safetyGuidance }

        // 중복 라벨/설명 없음 검증
        assertEquals(4, labels.toSet().size)
        assertEquals(4, descriptions.toSet().size)
        assertEquals(4, guidances.toSet().size)

        // 각 모드별 핵심 키워드 검증
        assertTrue(WalkingMode.APPROACHING_CROSSING.description.contains("횡단보도"))
        assertTrue(WalkingMode.APPROACHING_CROSSING.safetyGuidance.contains("음향신호기"))
        assertTrue(WalkingMode.CROSSING.safetyGuidance.contains("건너"))
        assertTrue(WalkingMode.WALKING.description.contains("보행로"))
        assertTrue(WalkingMode.WALKING.safetyGuidance.contains("점자블록"))
    }



    @Test
    fun `defaultDestinations have valid coordinates and non-empty accessible addresses`() {
        assertTrue(defaultDestinations.isNotEmpty())
        for (item in defaultDestinations) {
            assertTrue(item.name.isNotBlank())
            assertTrue(item.address.isNotBlank())
            // 대한민국 지리적 범위 (위도 32~39, 경도 124~132)
            assertTrue(item.location.lat in 32.0..39.0)
            assertTrue(item.location.lon in 124.0..132.0)
        }
    }

    @Test
    fun `disclaimer text strictly warns about accessibility limits and prohibits false safety guarantee`() {
        // 금지 문구: 단독 "안전한 경로입니다", "안심 경로" 오인 표현 금지
        assertFalse(ROUTE_DISCLAIMER_TEXT.contains("안전한 경로입니다"))
        assertFalse(ROUTE_DISCLAIMER_TEXT.contains("장애인 안전 경로"))
        assertFalse(ROUTE_DISCLAIMER_TEXT.contains("안심 보행로"))

        // 필수 고지 항목: 접근성 한계 및 미보장 사항 명시
        assertTrue(ROUTE_DISCLAIMER_TEXT.contains("TMAP 보행자 경로 안내"))
        assertTrue(ROUTE_DISCLAIMER_TEXT.contains("계단 제외 옵션"))
        assertTrue(ROUTE_DISCLAIMER_TEXT.contains("안전 경로가 아닙니다"))
        assertTrue(ROUTE_DISCLAIMER_TEXT.contains("음향신호기"))
        assertTrue(ROUTE_DISCLAIMER_TEXT.contains("점자블록"))
        assertTrue(ROUTE_DISCLAIMER_TEXT.contains("휠체어 단차"))
    }
}
