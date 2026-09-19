package kr.safecross.mobile

import kr.safecross.mobile.domain.model.DirectionAction
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionActionTest {

    @Test
    fun `parses straight action correctly`() {
        val maneuver = Maneuver(
            index = 0,
            pointIndex = 0,
            location = LocationPoint(37.5, 127.0),
            instruction = "100m 직진하세요",
            turnType = 11
        )
        val action = DirectionAction.fromManeuver(maneuver)
        assertEquals(DirectionAction.STRAIGHT, action)
        assertEquals("직진", action.label)
    }

    @Test
    fun `parses turn left and turn right from turnType`() {
        val leftManeuver = Maneuver(
            index = 1,
            pointIndex = 1,
            location = LocationPoint(37.5, 127.0),
            instruction = "좌회전하세요",
            turnType = 12
        )
        assertEquals(DirectionAction.LEFT, DirectionAction.fromManeuver(leftManeuver))

        val rightManeuver = Maneuver(
            index = 2,
            pointIndex = 2,
            location = LocationPoint(37.5, 127.0),
            instruction = "우회전하세요",
            turnType = 13
        )
        assertEquals(DirectionAction.RIGHT, DirectionAction.fromManeuver(rightManeuver))
    }

    @Test
    fun `parses crosswalk from facilityType or turnType`() {
        val crosswalkFacility = Maneuver(
            index = 3,
            pointIndex = 3,
            location = LocationPoint(37.5, 127.0),
            instruction = "횡단보도를 건너세요",
            facilityType = "횡단보도"
        )
        assertEquals(DirectionAction.CROSSWALK, DirectionAction.fromManeuver(crosswalkFacility))

        val crosswalkTurnType = Maneuver(
            index = 4,
            pointIndex = 4,
            location = LocationPoint(37.5, 127.0),
            instruction = "횡단보도 진입",
            turnType = 211
        )
        assertEquals(DirectionAction.CROSSWALK, DirectionAction.fromManeuver(crosswalkTurnType))
    }

    @Test
    fun `parses destination arrival correctly`() {
        val destManeuver = Maneuver(
            index = 5,
            pointIndex = 5,
            location = LocationPoint(37.5, 127.0),
            instruction = "목적지에 도착했습니다",
            turnType = 201
        )
        assertEquals(DirectionAction.DESTINATION, DirectionAction.fromManeuver(destManeuver))

        val nullManeuver: Maneuver? = null
        assertEquals(DirectionAction.DESTINATION, DirectionAction.fromManeuver(nullManeuver))
    }

    @Test
    fun `prevents false overpass detection on normal walkways and landmark texts`() {
        // 1. 일반 보행로(facilityType = "보행로", 구 TMAP 11)는 STRAIGHT여야 함 (육교로 오인 금지)
        val walkwayManeuver = Maneuver(
            index = 6,
            pointIndex = 6,
            location = LocationPoint(37.5, 127.0),
            instruction = "일반보행로를 따라 직진하세요",
            facilityType = "보행로",
            turnType = 11
        )
        assertEquals(DirectionAction.STRAIGHT, DirectionAction.fromManeuver(walkwayManeuver))

        // 2. "육교 방면으로 직진" 지명 텍스트는 육교가 아니라 STRAIGHT여야 함
        val landmarkStraight = Maneuver(
            index = 7,
            pointIndex = 7,
            location = LocationPoint(37.5, 127.0),
            instruction = "금남육교 방면으로 120m 직진하세요",
            turnType = 11
        )
        assertEquals(DirectionAction.STRAIGHT, DirectionAction.fromManeuver(landmarkStraight))

        // 3. "육교 방면으로 우회전" 지명 텍스트는 육교가 아니라 RIGHT여야 함
        val landmarkRight = Maneuver(
            index = 8,
            pointIndex = 8,
            location = LocationPoint(37.5, 127.0),
            instruction = "한빛육교 방면으로 우회전하세요",
            turnType = 13
        )
        assertEquals(DirectionAction.RIGHT, DirectionAction.fromManeuver(landmarkRight))

        // 4. 실제 육교 시설이나 육교 횡단 동작은 정상적으로 OVERPASS 판정되어야 함
        val realOverpass = Maneuver(
            index = 9,
            pointIndex = 9,
            location = LocationPoint(37.5, 127.0),
            instruction = "육교를 이용하여 횡단하세요",
            facilityType = "육교",
            turnType = 125
        )
        assertEquals(DirectionAction.OVERPASS, DirectionAction.fromManeuver(realOverpass))
    }
}
