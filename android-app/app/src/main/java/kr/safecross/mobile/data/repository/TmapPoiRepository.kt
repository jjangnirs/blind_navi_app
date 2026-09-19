package kr.safecross.mobile.data.repository

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.safecross.mobile.BuildConfig
import kr.safecross.mobile.domain.model.DestinationItem
import kr.safecross.mobile.domain.model.LocationPoint
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * SK TMAP 공식 POI 통합검색 API 연동 및 안드로이드 Geocoder 폴백 리포지토리.
 *
 * - 사용자가 입력한 전국 역, 건물명, 상호, 도로명 주소를 TMAP POI DB에서 실시간 검색하여
 *   정확한 위경도 좌표(WGS84)와 정규화된 주소를 반환합니다.
 * - 현재 GPS 위치(centerLat, centerLon)가 주어지면 내 위치 주변의 검색 결과를 우선 정렬합니다.
 * - 네트워크 장애 또는 TMAP API 오류 시 기기 내장 Geocoder로 자동 안전 전환(Fallback)합니다.
 */
class TmapPoiRepository(
    private val appKey: String = BuildConfig.TMAP_APP_KEY,
    private val context: Context? = null,
    private val connectTimeoutMs: Int = 5000,
    private val readTimeoutMs: Int = 5000
) {

    suspend fun searchPoi(
        keyword: String,
        centerLat: Double? = null,
        centerLon: Double? = null,
        count: Int = 20
    ): Result<List<DestinationItem>> = withContext(Dispatchers.IO) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.success(emptyList())
        }

        // 1차: SK TMAP 공식 POI 통합검색 API 호출
        try {
            val tmapResults = fetchFromTmapPoi(trimmed, centerLat, centerLon, count)
            if (tmapResults.isNotEmpty()) {
                return@withContext Result.success(tmapResults)
            }
        } catch (_: Exception) {
            // TMAP 실패 시 Geocoder 폴백 진행
        }

        // 2차: 안드로이드 플랫폼 Geocoder 폴백
        try {
            val geocoderResults = fetchFromGeocoder(trimmed, count)
            if (geocoderResults.isNotEmpty()) {
                return@withContext Result.success(geocoderResults)
            }
        } catch (_: Exception) {
            // Geocoder 실패 시
        }

        Result.success(emptyList())
    }

    /**
     * SK TMAP REST POI 통합검색 API 호출
     * GET https://apis.openapi.sk.com/tmap/pois
     */
    private fun fetchFromTmapPoi(
        keyword: String,
        centerLat: Double?,
        centerLon: Double?,
        count: Int
    ): List<DestinationItem> {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
        val sb = StringBuilder("https://apis.openapi.sk.com/tmap/pois?version=1&searchKeyword=$encodedKeyword&count=$count&resCoordType=WGS84GEO&reqCoordType=WGS84GEO")

        if (centerLat != null && centerLon != null && centerLat != 0.0 && centerLon != 0.0) {
            sb.append("&centerLat=$centerLat&centerLon=$centerLon")
        }

        val url = URL(sb.toString())
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("appKey", appKey.trim())
        conn.setRequestProperty("Accept", "application/json")

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            conn.disconnect()
            throw RuntimeException("TMAP POI API HTTP $responseCode")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
        val responseBody = reader.readText()
        reader.close()
        conn.disconnect()

        return parseTmapPoiJson(responseBody)
    }

    /**
     * TMAP POI JSON 응답 파싱
     */
    private fun parseTmapPoiJson(jsonStr: String): List<DestinationItem> {
        val items = mutableListOf<DestinationItem>()
        val root = JSONObject(jsonStr)
        val searchPoiInfo = root.optJSONObject("searchPoiInfo") ?: return items
        val pois = searchPoiInfo.optJSONObject("pois") ?: return items
        val poiArray = pois.optJSONArray("poi") ?: return items

        for (i in 0 until poiArray.length()) {
            val p = poiArray.getJSONObject(i)
            val id = p.optString("id", "poi_$i")
            val name = p.optString("name", "장소").trim()

            // 좌표 추출 (noorLat/noorLon: 입구 좌표 우선, 없으면 frontLat/frontLon)
            val latStr = p.optString("noorLat").ifEmpty { p.optString("frontLat") }
            val lonStr = p.optString("noorLon").ifEmpty { p.optString("frontLon") }

            val lat = latStr.toDoubleOrNull() ?: continue
            val lon = lonStr.toDoubleOrNull() ?: continue

            // 도로명 주소 또는 지번 주소 조합
            val upperAddr = p.optString("upperAddrName").trim()
            val middleAddr = p.optString("middleAddrName").trim()
            val lowerAddr = p.optString("lowerAddrName").trim()
            val roadName = p.optString("roadName").trim()
            val firstBuildNo = p.optString("firstBuildNo").trim()
            val detailAddr = p.optString("detailAddrName").trim()

            val roadPart = if (roadName.isNotEmpty()) {
                if (firstBuildNo.isNotEmpty()) "$roadName $firstBuildNo" else roadName
            } else {
                detailAddr
            }

            val fullAddress = listOf(upperAddr, middleAddr, lowerAddr, roadPart)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

            items.add(
                DestinationItem(
                    id = "tmap_poi_$id",
                    name = name,
                    address = fullAddress.ifEmpty { "대한민국" },
                    location = LocationPoint(lat, lon),
                    isFavorite = false
                )
            )
        }
        return items
    }

    /**
     * 안드로이드 플랫폼 Geocoder 폴백
     */
    private fun fetchFromGeocoder(keyword: String, count: Int): List<DestinationItem> {
        val targetContext = context ?: return emptyList()
        val geocoder = Geocoder(targetContext, Locale.KOREA)
        val addresses = geocoder.getFromLocationName(keyword, count) ?: return emptyList()

        return addresses.mapIndexed { index, addr ->
            val name = addr.featureName ?: keyword
            val fullAddr = addr.getAddressLine(0)?.replace("대한민국 ", "") ?: keyword
            DestinationItem(
                id = "geocoder_$index",
                name = name,
                address = fullAddr,
                location = LocationPoint(addr.latitude, addr.longitude),
                isFavorite = false
            )
        }
    }
}
