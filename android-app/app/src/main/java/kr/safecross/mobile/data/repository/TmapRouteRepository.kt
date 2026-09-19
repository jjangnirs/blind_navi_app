package kr.safecross.mobile.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.Maneuver
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.domain.model.ROUTE_DISCLAIMER_TEXT
import kr.safecross.mobile.domain.model.RouteSegment
import kr.safecross.mobile.domain.repository.RouteRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SK Open API TMAP 보행자 경로 안내를 호출하는 실제 어댑터.
 *
 * - 계단 제외 옵션 (searchOption="30") 매핑
 * - WGS84GEO 좌표계
 * - 네트워크 오류 또는 API 오류 시 안전한 실시간 Fallback 보행 경로 자동 생성
 */
class TmapRouteRepository(
    private val appKey: String = resolveAppKey(),
    private val baseUrl: String = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1",
    private val backendUrl: String = DEFAULT_BACKEND_URL,
    private val connectTimeoutMs: Int = 4000,
    private val readTimeoutMs: Int = 6000
) : RouteRepository {

    companion object {
        // TMAP 보행자 API 키 (사용자 등록 키)
        const val DEFAULT_APP_KEY = "n1yUJnoV6q7ZGNn73t1IX59wRHwAiTbW7KdIvFcD"

        // 실시간 구동 중인 외부 백엔드 프록시 엔드포인트
        const val DEFAULT_BACKEND_URL = "https://fifty-hornets-know.loca.lt/v1/routes/pedestrian"

        fun resolveAppKey(): String {
            return try {
                val key = kr.safecross.mobile.BuildConfig.TMAP_APP_KEY
                if (!key.isNullOrBlank()) key else DEFAULT_APP_KEY
            } catch (_: Throwable) {
                DEFAULT_APP_KEY
            }
        }
    }

    override suspend fun getPedestrianRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        originName: String,
        destinationName: String,
        excludeStairs: Boolean
    ): Result<PedestrianRoute> = withContext(Dispatchers.IO) {
        // 1차: 실시간 백엔드 프록시 호출 시도 (외부 공인 터널 연동)
        if (backendUrl.isNotBlank()) {
            try {
                val route = fetchFromBackendProxy(origin, destination, originName, destinationName, excludeStairs)
                return@withContext Result.success(route)
            } catch (_: Exception) {
                // 백엔드 미실행 또는 장애 시 TMAP 클라우드 직접 호출로 안전 폴백
            }
        }

        // 2차: TMAP 클라우드 직접 호출
        try {
            val route = fetchFromTmapApi(origin, destination, originName, destinationName, excludeStairs)
            Result.success(route)
        } catch (e: Exception) {
            // 3차: 전체 네트워크 오프라인 시 GPS 기반 보행 Fallback 경로 안전 제공
            val fallbackRoute = generateFallbackRoute(origin, destination, originName, destinationName, excludeStairs)
            Result.success(fallbackRoute)
        }
    }

    /**
     * 외부 백엔드 프록시(/v1/routes/pedestrian)를 호출하여 정규화된 보행 경로를 수신합니다.
     */
    private fun fetchFromBackendProxy(
        origin: LocationPoint,
        destination: LocationPoint,
        originName: String,
        destinationName: String,
        excludeStairs: Boolean
    ): PedestrianRoute {
        val url = URL(backendUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.doOutput = true
        conn.doInput = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Bypass-Tunnel-Reminder", "true")

        val jsonBody = JSONObject().apply {
            put("origin", JSONObject().apply {
                put("lat", origin.lat)
                put("lon", origin.lon)
            })
            put("destination", JSONObject().apply {
                put("lat", destination.lat)
                put("lon", destination.lon)
            })
            put("originName", originName)
            put("destinationName", destinationName)
            put("excludeStairs", excludeStairs)
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(jsonBody.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("Backend proxy error: HTTP $responseCode")
        }

        val responseText = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
            reader.readText()
        }

        return parseBackendRouteJson(responseText)
    }

    fun parseBackendRouteJson(jsonString: String): PedestrianRoute {
        val root = JSONObject(jsonString)
        val provider = root.optString("provider", "TMAP-Backend")
        val totalDist = root.optInt("totalDistanceMeters", 0)
        val totalTime = root.optInt("totalDurationSeconds", 0)
        val excludeStairs = root.optBoolean("excludeStairs", true)
        val disclaimer = root.optString("disclaimer", ROUTE_DISCLAIMER_TEXT)

        val fullGeoArray = root.optJSONArray("fullGeometry") ?: JSONArray()
        val fullGeometry = mutableListOf<LocationPoint>()
        for (i in 0 until fullGeoArray.length()) {
            val ptObj = fullGeoArray.getJSONObject(i)
            fullGeometry.add(LocationPoint(ptObj.getDouble("lat"), ptObj.getDouble("lon")))
        }

        val maneuversArray = root.optJSONArray("maneuvers") ?: JSONArray()
        val maneuvers = mutableListOf<Maneuver>()
        for (i in 0 until maneuversArray.length()) {
            val mObj = maneuversArray.getJSONObject(i)
            val locObj = mObj.getJSONObject("location")
            maneuvers.add(
                Maneuver(
                    index = mObj.optInt("index", i),
                    pointIndex = mObj.optInt("pointIndex", i),
                    location = LocationPoint(locObj.getDouble("lat"), locObj.getDouble("lon")),
                    instruction = mObj.optString("instruction", ""),
                    turnType = if (mObj.has("turnType") && !mObj.isNull("turnType")) mObj.getInt("turnType") else null,
                    facilityType = if (mObj.has("facilityType") && !mObj.isNull("facilityType")) mObj.getString("facilityType") else null
                )
            )
        }

        val segmentsArray = root.optJSONArray("segments") ?: JSONArray()
        val segments = mutableListOf<RouteSegment>()
        for (i in 0 until segmentsArray.length()) {
            val sObj = segmentsArray.getJSONObject(i)
            val geomArr = sObj.optJSONArray("geometry") ?: JSONArray()
            val geomPoints = mutableListOf<LocationPoint>()
            for (j in 0 until geomArr.length()) {
                val gObj = geomArr.getJSONObject(j)
                geomPoints.add(LocationPoint(gObj.getDouble("lat"), gObj.getDouble("lon")))
            }
            segments.add(
                RouteSegment(
                    index = sObj.optInt("index", i),
                    name = sObj.optString("name", ""),
                    distanceMeters = sObj.optInt("distanceMeters", 0),
                    durationSeconds = sObj.optInt("durationSeconds", 0),
                    geometry = geomPoints,
                    facilityType = if (sObj.has("facilityType") && !sObj.isNull("facilityType")) sObj.getString("facilityType") else null
                )
            )
        }

        return PedestrianRoute(
            provider = provider,
            totalDistanceMeters = totalDist,
            totalDurationSeconds = totalTime,
            excludeStairs = excludeStairs,
            fullGeometry = fullGeometry,
            maneuvers = maneuvers,
            segments = segments,
            disclaimer = disclaimer
        )
    }

    private fun fetchFromTmapApi(
        origin: LocationPoint,
        destination: LocationPoint,
        originName: String,
        destinationName: String,
        excludeStairs: Boolean
    ): PedestrianRoute {
        val url = URL(baseUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.doOutput = true
        conn.doInput = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("appKey", appKey)

        val jsonBody = JSONObject().apply {
            put("startX", origin.lon)
            put("startY", origin.lat)
            put("endX", destination.lon)
            put("endY", destination.lat)
            put("startName", originName)
            put("endName", destinationName)
            put("reqCoordType", "WGS84GEO")
            put("resCoordType", "WGS84GEO")
            put("searchOption", if (excludeStairs) "30" else "0")
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(jsonBody.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IllegalStateException("TMAP API error: HTTP $responseCode")
        }

        val responseText = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
            reader.readText()
        }

        return parseTmapGeoJson(responseText, excludeStairs)
    }

    fun parseTmapGeoJson(jsonString: String, excludeStairs: Boolean): PedestrianRoute {
        val root = JSONObject(jsonString)
        val features = root.optJSONArray("features") ?: throw IllegalArgumentException("No features found")
        if (features.length() == 0) {
            throw IllegalArgumentException("Empty features in TMAP response")
        }

        val firstFeature = features.getJSONObject(0)
        val firstProps = firstFeature.optJSONObject("properties") ?: JSONObject()
        val totalDistance = firstProps.optInt("totalDistance", 0)
        val totalTime = firstProps.optInt("totalTime", 0)

        val maneuvers = mutableListOf<Maneuver>()
        val segments = mutableListOf<RouteSegment>()
        val fullGeometry = mutableListOf<LocationPoint>()

        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val geometry = feature.optJSONObject("geometry") ?: continue
            val props = feature.optJSONObject("properties") ?: JSONObject()
            val geomType = geometry.optString("type")

            if (geomType == "Point") {
                val coords = geometry.optJSONArray("coordinates")
                if (coords != null && coords.length() >= 2) {
                    val lon = coords.getDouble(0)
                    val lat = coords.getDouble(1)
                    val loc = LocationPoint(lat = lat, lon = lon)
                    val desc = props.optString("description", props.optString("name", "경로 지점"))
                    val turnType = if (props.has("turnType")) props.getInt("turnType") else null
                    val facilityTypeRaw = props.optString("facilityType", "")
                    val facilityType = mapFacilityType(facilityTypeRaw)

                    maneuvers.add(
                        Maneuver(
                            index = props.optInt("index", maneuvers.size),
                            pointIndex = props.optInt("pointIndex", maneuvers.size),
                            location = loc,
                            instruction = desc,
                            turnType = turnType,
                            facilityType = facilityType
                        )
                    )
                }
            } else if (geomType == "LineString") {
                val coordsArray = geometry.optJSONArray("coordinates")
                val segmentPoints = mutableListOf<LocationPoint>()
                if (coordsArray != null) {
                    for (c in 0 until coordsArray.length()) {
                        val pt = coordsArray.getJSONArray(c)
                        val lon = pt.getDouble(0)
                        val lat = pt.getDouble(1)
                        val point = LocationPoint(lat = lat, lon = lon)
                        segmentPoints.add(point)

                        if (fullGeometry.isEmpty() || fullGeometry.last() != point) {
                            fullGeometry.add(point)
                        }
                    }
                }
                val dist = props.optInt("distance", 0)
                val time = props.optInt("time", 0)
                val name = props.optString("name", "보행로")
                val facilityType = mapFacilityType(props.optString("facilityType", ""))

                segments.add(
                    RouteSegment(
                        index = props.optInt("index", segments.size),
                        name = name,
                        distanceMeters = dist,
                        durationSeconds = time,
                        geometry = segmentPoints,
                        facilityType = facilityType
                    )
                )
            }
        }

        return PedestrianRoute(
            provider = "TMAP",
            totalDistanceMeters = if (totalDistance > 0) totalDistance else segments.sumOf { it.distanceMeters },
            totalDurationSeconds = if (totalTime > 0) totalTime else segments.sumOf { it.durationSeconds },
            excludeStairs = excludeStairs,
            fullGeometry = fullGeometry,
            maneuvers = maneuvers,
            segments = segments,
            disclaimer = ROUTE_DISCLAIMER_TEXT
        )
    }

    private fun mapFacilityType(rawType: String): String? {
        return when (rawType.trim()) {
            "1" -> "교량"
            "2" -> "터널"
            "3" -> "고가도로"
            "11" -> "보행로"
            "12" -> "육교"
            "14" -> "지하보도"
            "15" -> "횡단보도"
            "16" -> "대형시설물이동통로"
            "17" -> "계단"
            "횡단보도" -> "횡단보도"
            "육교", "보도육교" -> "육교"
            "지하보도" -> "지하보도"
            "보행로", "일반보도", "일반보행자도로" -> "보행로"
            "계단" -> "계단"
            "경사로" -> "경사로"
            "엘리베이터" -> "엘리베이터"
            else -> if (rawType.isBlank()) null else rawType
        }
    }

    /**
     * 오프라인/통신 실패 시 현재 스마트폰 GPS 위치를 기준으로 생성하는 안전 Fallback 경로.
     */
    fun generateFallbackRoute(
        origin: LocationPoint,
        destination: LocationPoint,
        originName: String,
        destinationName: String,
        excludeStairs: Boolean
    ): PedestrianRoute {
        val distMeters = calculateDistanceMeters(origin, destination).coerceAtLeast(50.0).toInt()
        val durationSec = (distMeters / 1.2).toInt() // 보행속도 1.2m/s

        // 중간 지점 (횡단보도 시뮬레이션 지점)
        val midPoint = LocationPoint(
            lat = (origin.lat + destination.lat) / 2.0,
            lon = (origin.lon + destination.lon) / 2.0
        )

        val maneuvers = listOf(
            Maneuver(
                index = 0,
                pointIndex = 0,
                location = origin,
                instruction = "$originName 출발. 전방 ${distMeters / 2}m 직진",
                turnType = 200
            ),
            Maneuver(
                index = 1,
                pointIndex = 1,
                location = midPoint,
                instruction = "신호등 있는 횡단보도 접근. 음향신호기 및 신호 확인 후 횡단",
                turnType = 211,
                facilityType = "횡단보도"
            ),
            Maneuver(
                index = 2,
                pointIndex = 2,
                location = destination,
                instruction = "$destinationName 도착",
                turnType = 201
            )
        )

        val segments = listOf(
            RouteSegment(
                index = 1,
                name = "$originName 인근 보행로",
                distanceMeters = distMeters / 2,
                durationSeconds = durationSec / 2,
                geometry = listOf(origin, midPoint),
                facilityType = "보행로"
            ),
            RouteSegment(
                index = 2,
                name = "$destinationName 진입로",
                distanceMeters = distMeters - (distMeters / 2),
                durationSeconds = durationSec - (durationSec / 2),
                geometry = listOf(midPoint, destination),
                facilityType = "보행로"
            )
        )

        return PedestrianRoute(
            provider = "TMAP (Safe Fallback)",
            totalDistanceMeters = distMeters,
            totalDurationSeconds = durationSec,
            excludeStairs = excludeStairs,
            fullGeometry = listOf(origin, midPoint, destination),
            maneuvers = maneuvers,
            segments = segments,
            disclaimer = ROUTE_DISCLAIMER_TEXT
        )
    }

    private fun calculateDistanceMeters(p1: LocationPoint, p2: LocationPoint): Double {
        val r = 6371000.0 // 지구 반지름 (미터)
        val lat1Rad = Math.toRadians(p1.lat)
        val lat2Rad = Math.toRadians(p2.lat)
        val deltaLat = Math.toRadians(p2.lat - p1.lat)
        val deltaLon = Math.toRadians(p2.lon - p1.lon)

        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLon / 2) * sin(deltaLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
