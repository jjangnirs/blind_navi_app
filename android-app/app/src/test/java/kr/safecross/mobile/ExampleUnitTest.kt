package kr.safecross.mobile

import org.junit.Test
import org.junit.Assert.*

/**
 * Example local unit test for Safe Cross KR Android app.
 * 기본 빌드 및 단위 테스트 파이프라인 검증용.
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun defaultTargetPackage_isCorrect() {
        val expectedPackage = "kr.safecross.mobile"
        assertEquals("kr.safecross.mobile", expectedPackage)
    }
}
