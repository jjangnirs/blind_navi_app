package kr.safecross.mobile.navigation.crossing

/**
 * 횡단보도 시설물 정보 도메인 모델.
 *
 * 삼항 논리(Boolean?)를 엄격히 보존합니다:
 * - true: 설치됨
 * - false: 명시적 미설치
 * - null: 원천 공공데이터 미기재/미상
 */
data class CrossingFacility(
    val id: String,
    val lat: Double,
    val lon: Double,
    val approachBearingDeg: Double? = null,
    val isFieldVerified: Boolean = false,
    val acousticSignal: Boolean? = null,
    val tactilePaving: Boolean? = null,
    val curbCut: Boolean? = null,
    val roadName: String? = null
)
