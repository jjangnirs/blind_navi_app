package kr.safecross.mobile

import kr.safecross.mobile.domain.model.DirectionAction
import kr.safecross.mobile.guidance.BlindGuidanceFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindGuidanceFormatterTest {

    @Test
    fun testDistanceToStepsCalculation() {
        assertEquals(0, BlindGuidanceFormatter.distanceToSteps(0.0))
        // 6.5m / 0.65m = 10 steps
        assertEquals(10, BlindGuidanceFormatter.distanceToSteps(6.5))
        // 20m / 0.65m = 30.76 -> 31 steps
        assertEquals(31, BlindGuidanceFormatter.distanceToSteps(20.0))
        // 1m -> at least 1 step
        assertEquals(2, BlindGuidanceFormatter.distanceToSteps(1.0))
    }

    @Test
    fun testFormatDistanceWithSteps() {
        assertEquals("잠시 후", BlindGuidanceFormatter.formatDistanceWithSteps(4.0))
        assertEquals("잠시 후", BlindGuidanceFormatter.formatDistanceWithSteps(6.0))
        val text20 = BlindGuidanceFormatter.formatDistanceWithSteps(20.0)
        assertTrue("걸음 수 포함 확인", text20.contains("걸음 앞"))
        assertTrue("미터 포함 확인", text20.contains("20미터"))
    }

    @Test
    fun testRelativeBearingToClockDirection() {
        assertEquals("12시 방향(정면)", BlindGuidanceFormatter.relativeBearingToClockDirection(0.0))
        assertEquals("12시 방향(정면)", BlindGuidanceFormatter.relativeBearingToClockDirection(355.0))
        assertEquals("12시 방향(정면)", BlindGuidanceFormatter.relativeBearingToClockDirection(10.0))
        assertEquals("1시 방향", BlindGuidanceFormatter.relativeBearingToClockDirection(30.0))
        assertEquals("2시 방향", BlindGuidanceFormatter.relativeBearingToClockDirection(60.0))
        assertEquals("3시 방향(우측)", BlindGuidanceFormatter.relativeBearingToClockDirection(90.0))
        assertEquals("6시 방향(뒤쪽)", BlindGuidanceFormatter.relativeBearingToClockDirection(180.0))
        assertEquals("9시 방향(좌측)", BlindGuidanceFormatter.relativeBearingToClockDirection(270.0))
        assertEquals("11시 방향", BlindGuidanceFormatter.relativeBearingToClockDirection(330.0))
    }

    @Test
    fun testCleanInstructionRemovesVisualLandmarks() {
        val raw1 = "소망약국 방면으로 횡단보도 건너기"
        assertEquals("횡단보도 건너기", BlindGuidanceFormatter.cleanInstruction(raw1))

        val raw2 = "을지로입구역 2번 출구 방면으로 직진"
        assertEquals("직진", BlindGuidanceFormatter.cleanInstruction(raw2))

        val raw3 = "강남대로 방면으로 우회전"
        assertEquals("우회전", BlindGuidanceFormatter.cleanInstruction(raw3))
    }

    @Test
    fun testFormatApproachGuidance() {
        val crosswalkGuidance = BlindGuidanceFormatter.formatApproachGuidance(
            action = DirectionAction.CROSSWALK,
            distanceMeters = 25.0,
            relativeBearingDeg = 60.0
        )
        assertTrue(crosswalkGuidance.contains("걸음 앞"))
        assertTrue(crosswalkGuidance.contains("2시 방향"))
        assertTrue(crosswalkGuidance.contains("횡단보도"))
        assertTrue(crosswalkGuidance.contains("점자블록"))

        val turnGuidance = BlindGuidanceFormatter.formatApproachGuidance(
            action = DirectionAction.RIGHT,
            distanceMeters = 5.0,
            relativeBearingDeg = 90.0
        )
        assertTrue(turnGuidance.contains("잠시 후"))
        assertTrue(turnGuidance.contains("3시 방향(우측)"))
        assertTrue(turnGuidance.contains("우회전"))
    }

    @Test
    fun testEvaluateOrientation() {
        // 1. 헤딩 45도, 목표 48도 -> 정대 상태 (차이 3도)
        val alignedPrompt = BlindGuidanceFormatter.evaluateOrientation(
            currentHeadingDeg = 45.0,
            targetBearingDeg = 48.0
        )
        assertTrue(alignedPrompt.isAligned)
        assertTrue(alignedPrompt.message.contains("올바른 진행 방향입니다"))

        // 2. 헤딩 0도, 목표 60도 -> 오른쪽으로 60도 회전
        val turnRightPrompt = BlindGuidanceFormatter.evaluateOrientation(
            currentHeadingDeg = 0.0,
            targetBearingDeg = 60.0
        )
        assertFalse(turnRightPrompt.isAligned)
        assertEquals(60, turnRightPrompt.relativeDegrees)
        assertTrue(turnRightPrompt.message.contains("오른쪽으로 60도"))
        assertTrue(turnRightPrompt.message.contains("2시 방향"))

        // 3. 헤딩 90도, 목표 30도 -> 왼쪽으로 60도 회전
        val turnLeftPrompt = BlindGuidanceFormatter.evaluateOrientation(
            currentHeadingDeg = 90.0,
            targetBearingDeg = 30.0
        )
        assertFalse(turnLeftPrompt.isAligned)
        assertEquals(-60, turnLeftPrompt.relativeDegrees)
        assertTrue(turnLeftPrompt.message.contains("왼쪽으로 60도"))
        assertTrue(turnLeftPrompt.message.contains("10시 방향"))
    }

    @Test
    fun testZeroProhibitedPhrases() {
        val phrases = listOf("안전합니다", "지금 건너세요", "차가 없습니다", "100% 녹색", "장애인 안전 경로", "안심 경로")
        val sample1 = BlindGuidanceFormatter.formatApproachGuidance(DirectionAction.CROSSWALK, 15.0, 30.0)
        val sample2 = BlindGuidanceFormatter.evaluateOrientation(0.0, 10.0).message
        val sample3 = BlindGuidanceFormatter.evaluateOrientation(0.0, 90.0).message

        for (prohibited in phrases) {
            assertFalse(sample1.contains(prohibited))
            assertFalse(sample2.contains(prohibited))
            assertFalse(sample3.contains(prohibited))
        }
    }
}
