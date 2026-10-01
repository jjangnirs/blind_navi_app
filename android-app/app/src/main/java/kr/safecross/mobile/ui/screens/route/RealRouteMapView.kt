package kr.safecross.mobile.ui.screens.route

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kr.safecross.mobile.BuildConfig
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.PedestrianRoute
import org.json.JSONArray
import org.json.JSONObject

/**
 * 대한민국 국토교통부 VWorld 표준 정밀 세부지도 및 실시간 보행 위치 추적을 지원하는 고정밀 지도 뷰어.
 *
 * - 국토교통부 VWorld 전국 정밀 2D 지도(전국 1:1000 골목길, 건물명, 횡단보도, 지하철 출구 100% 한국어)를
 *   기본 엔진으로 렌더링하여 까만 화면 없이 도로와 건물이 선명하게 표출됩니다.
 * - 네트워크 또는 타일 에러 시 OpenStreetMap 및 CartoDB 고해상도 타일로 순차 자동 안전 전환(Fallback)됩니다.
 * - TMAP 보행자 API의 정밀 경로선(LineString)을 고대비 네이비 테두리 + 형광 옐로우 중심선으로 시각화합니다.
 * - 보행자의 실시간 현재 위치(🔵 파란색 레이더 펄스 핀)와 경로 이탈(🔴) 상태를 실시간으로 표시합니다.
 * - 출발지(🟢), 도착지(🔴), 횡단보도(🟠), 목표 분기점 마커를 제공합니다.
 * - 지도 내 [내 위치 중심] 및 [전체 경로 보기] 버튼을 제공합니다.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RealRouteMapView(
    route: PedestrianRoute,
    originName: String = "출발지",
    destinationName: String = "목적지",
    currentLocation: LocationPoint? = null,
    currentManeuverIndex: Int = 0,
    isOffRoute: Boolean = false,
    headingDegrees: Float = 0f,
    isHeadingUp: Boolean = false,
    showLiveTrackingControls: Boolean = true,
    tmapAppKey: String = BuildConfig.TMAP_APP_KEY,
    modifier: Modifier = Modifier
) {
    // HTML은 최초 경로로 한 번만 만든다. 재탐색으로 경로가 바뀌면 페이지를 다시 로드하지 않고
    // replaceRoute()로 경로 레이어만 교체해 지도 시점(위치·줌·회전)이 튀지 않게 한다.
    val routeDataJs = remember(route) { buildRouteDataJs(route) }
    val latestRouteDataJs = rememberUpdatedState(routeDataJs)
    val htmlContent = remember(originName, destinationName) {
        buildRouteMapHtml(
            route = route,
            originName = originName,
            destinationName = destinationName,
            initialLocation = currentLocation,
            initialOffRoute = isOffRoute,
            initialHeading = headingDegrees,
            initialHeadingUp = isHeadingUp,
            showControls = showLiveTrackingControls
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF131722))
            .border(1.5.dp, Color(0xFF2C3242), RoundedCornerShape(12.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(0xFF131722.toInt())

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        setSupportZoom(true)
                        builtInZoomControls = false
                        displayZoomControls = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 로드 중에 경로가 바뀌었을 수 있으므로 최신 경로로 동기화
                            if (view?.getTag(ROUTE_TAG_KEY) != latestRouteDataJs.value) {
                                view?.setTag(ROUTE_TAG_KEY, latestRouteDataJs.value)
                                view?.evaluateJavascript(
                                    "if (typeof replaceRoute === 'function') { replaceRoute(${latestRouteDataJs.value}); }",
                                    null
                                )
                            }
                            // 페이지 로드 완료 후 현재 위치 및 진행방향 각도 즉시 반영
                            if (currentLocation != null) {
                                view?.evaluateJavascript(
                                    "if (typeof updateUserLocation === 'function') { updateUserLocation(${currentLocation.lat}, ${currentLocation.lon}, $isOffRoute, false, $headingDegrees, $isHeadingUp); }",
                                    null
                                )
                            } else {
                                view?.evaluateJavascript(
                                    "if (typeof setHeading === 'function') { setHeading($headingDegrees, $isHeadingUp); }",
                                    null
                                )
                            }
                        }
                    }

                    // Compose 부모 수직 스크롤과의 터치 제스처 충돌 방지
                    setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }

                    tag = htmlContent
                    setTag(ROUTE_TAG_KEY, routeDataJs)
                    loadDataWithBaseURL(
                        "https://safecross.kr",
                        htmlContent,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            update = { webView ->
                val currentTag = webView.tag as? String
                if (currentTag != htmlContent) {
                    webView.tag = htmlContent
                    webView.loadDataWithBaseURL(
                        "https://safecross.kr",
                        htmlContent,
                        "text/html",
                        "UTF-8",
                        null
                    )
                } else {
                    // 재탐색으로 경로가 바뀐 경우: 페이지 재로드 없이 경로 레이어만 교체
                    if (webView.getTag(ROUTE_TAG_KEY) != routeDataJs) {
                        webView.setTag(ROUTE_TAG_KEY, routeDataJs)
                        webView.evaluateJavascript(
                            "if (typeof replaceRoute === 'function') { replaceRoute($routeDataJs); }",
                            null
                        )
                    }
                    // HTML 재로드 없이 자바스크립트로 내 위치 마커 및 맵 회전 실시간 부드럽게 갱신
                    if (currentLocation != null) {
                        val js = "if (typeof updateUserLocation === 'function') { updateUserLocation(${currentLocation.lat}, ${currentLocation.lon}, $isOffRoute, false, $headingDegrees, $isHeadingUp); }"
                        webView.evaluateJavascript(js, null)
                    } else {
                        val js = "if (typeof setHeading === 'function') { setHeading($headingDegrees, $isHeadingUp); }"
                        webView.evaluateJavascript(js, null)
                    }
                }
            }
        )
    }
}

/**
 * 국토교통부 VWorld 정밀 국가 전자지도 타일 및 Leaflet 기반 독립형 HTML 문서 생성.
 * 보행자 진행방향 위로(Heading-Up) 부드러운 지도 회전 및 북쪽 고정(North-Up) 토글 지원.
 */
private fun buildRouteMapHtml(
    route: PedestrianRoute,
    originName: String,
    destinationName: String,
    initialLocation: LocationPoint?,
    initialOffRoute: Boolean,
    initialHeading: Float = 0f,
    initialHeadingUp: Boolean = false,
    showControls: Boolean
): String {
    val coords = routeCoords(route)
    val coordsArray = buildCoordsJson(route)
    val crosswalksArray = buildCrosswalksJson(route)

    val safeOrigin = JSONObject.quote(originName)
    val safeDest = JSONObject.quote(destinationName)

    val initLat = initialLocation?.lat ?: (coords.firstOrNull()?.lat ?: 35.1595)
    val initLon = initialLocation?.lon ?: (coords.firstOrNull()?.lon ?: 126.8526)
    val hasInitLoc = initialLocation != null

    return buildRouteMapHtmlDocument(
        coordsArray = coordsArray,
        crosswalksArray = crosswalksArray,
        safeOrigin = safeOrigin,
        safeDest = safeDest,
        hasInitLoc = hasInitLoc,
        initLat = initLat,
        initLon = initLon,
        initialOffRoute = initialOffRoute,
        initialHeading = initialHeading,
        initialHeadingUp = initialHeadingUp
    )
}

private const val ROUTE_TAG_KEY = 0x5AFEC055

private fun routeCoords(route: PedestrianRoute): List<LocationPoint> =
    if (route.fullGeometry.isNotEmpty()) route.fullGeometry else route.maneuvers.map { it.location }

/** replaceRoute(coords, crosswalks) 호출 인자 문자열 */
private fun buildRouteDataJs(route: PedestrianRoute): String =
    "${buildCoordsJson(route)}, ${buildCrosswalksJson(route)}"

/** 경로 좌표 목록을 JSON 배열로 변환 [[lat, lon], [lat, lon], ...] */
private fun buildCoordsJson(route: PedestrianRoute): JSONArray {
    val coordsArray = JSONArray()
    for (pt in routeCoords(route)) {
        val ptArr = JSONArray()
        ptArr.put(pt.lat)
        ptArr.put(pt.lon)
        coordsArray.put(ptArr)
    }
    return coordsArray
}

/** 횡단보도 및 분기점 목록 추출 (C-ITS 실시간 신호 연동 여부 자동 판정) */
private fun buildCrosswalksJson(route: PedestrianRoute): JSONArray {
    val crosswalksArray = JSONArray()
    route.maneuvers.forEachIndexed { idx, m ->
        val isCrosswalk = m.facilityType == "횡단보도" || (m.turnType != null && m.turnType in 211..217) ||
                m.instruction.contains("횡단보도")
        if (isCrosswalk) {
            val cwObj = JSONObject()
            cwObj.put("lat", m.location.lat)
            cwObj.put("lon", m.location.lon)
            cwObj.put("desc", m.instruction)
            cwObj.put("index", idx + 1)

            // C-ITS 실시간 보행 신호 연동 여부 판정:
            // 1) 신호등 키워드 ("신호등", "신호에 따라")
            // 2) 주요 교차로/간선도로 키워드 ("사거리", "오거리", "교차로", "대로", "로", "역", "거리")
            // 3) 무신호 횡단보도가 아닌 경우
            val isExplicitNoSignal = m.instruction.contains("무신호")
            val hasSignalKeywords = m.instruction.contains("신호")
            val hasCrossroadKeywords = m.instruction.contains("사거리") || m.instruction.contains("오거리") ||
                    m.instruction.contains("교차로") || m.instruction.contains("대로") ||
                    m.instruction.contains("로") || m.instruction.contains("역")

            val isCits = !isExplicitNoSignal && (hasSignalKeywords || hasCrossroadKeywords)
            cwObj.put("isCits", isCits)
            crosswalksArray.put(cwObj)
        }
    }
    return crosswalksArray
}

private fun buildRouteMapHtmlDocument(
    coordsArray: JSONArray,
    crosswalksArray: JSONArray,
    safeOrigin: String,
    safeDest: String,
    hasInitLoc: Boolean,
    initLat: Double,
    initLon: Double,
    initialOffRoute: Boolean,
    initialHeading: Float,
    initialHeadingUp: Boolean
): String {
    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=2.0, user-scalable=yes" />
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>
        * { box-sizing: border-box; }
        html, body {
            width: 100%;
            height: 100%;
            margin: 0;
            padding: 0;
            background-color: #131722;
            overflow: hidden;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", Helvetica, Arial, sans-serif;
        }

        /* 360도 회전 시 맵 모서리 빈 공간이 보이지 않도록 170% 확장 뷰포트 레이아웃 */
        #map-viewport {
            position: relative;
            width: 100%;
            height: 100%;
            overflow: hidden;
        }
        #map {
            position: absolute;
            width: 170%;
            height: 170%;
            left: -35%;
            top: -35%;
            transform-origin: 50% 50%;
            transition: transform 0.8s ease-out;
        }

        /* 컨트롤 버튼 플로팅 패널 */
        .map-control-panel {
            position: absolute;
            right: 12px;
            bottom: 14px;
            z-index: 1000;
            display: flex;
            flex-direction: column;
            gap: 8px;
        }
        .map-btn {
            background: #1E2638;
            color: #FFFFFF;
            border: 1.5px solid #3B4660;
            border-radius: 8px;
            padding: 7px 11px;
            font-size: 12px;
            font-weight: bold;
            box-shadow: 0 4px 10px rgba(0,0,0,0.5);
            cursor: pointer;
            display: flex;
            align-items: center;
            gap: 5px;
            user-select: none;
            touch-action: manipulation;
        }
        .map-btn:active {
            background: #2D3954;
            transform: scale(0.96);
        }
        .map-btn.active-mode {
            background: #0D47A1;
            border-color: #448AFF;
            color: #FFFFFF;
        }

        /* 핀 마커 공통 스타일 */
        .pin-marker {
            width: 32px;
            height: 32px;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 4px 12px rgba(0,0,0,0.6);
            border: 2.5px solid #ffffff;
            font-weight: 900;
            font-size: 14px;
            color: #ffffff;
        }
        .start-pin {
            background: #00E676;
            animation: pulse-ring 2.2s infinite;
        }
        .end-pin {
            background: #FF1744;
            animation: pulse-ring 2.2s infinite;
        }
        .crosswalk-pin {
            width: 28px;
            height: 28px;
            border-radius: 50%;
            background: #FF9100;
            border: 2px solid #ffffff;
            box-shadow: 0 3px 8px rgba(0,0,0,0.6);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 14px;
        }

        /* C-ITS 실시간 보행 신호등 횡단보도 핀 (에메랄드/시안 글로우 & 펄스 링) */
        .cits-crosswalk-pin {
            width: 32px;
            height: 32px;
            border-radius: 50%;
            background: linear-gradient(135deg, #00E5FF 0%, #00C853 100%);
            border: 2.5px solid #FFFFFF;
            box-shadow: 0 0 14px rgba(0, 229, 255, 0.9), 0 3px 8px rgba(0,0,0,0.6);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 16px;
            position: relative;
            animation: cits-pulse-ring 2.0s infinite ease-out;
        }
        .cits-sub-badge {
            position: absolute;
            bottom: -9px;
            background: #00E5FF;
            color: #002244;
            font-size: 8px;
            font-weight: 900;
            padding: 0 4px;
            border-radius: 4px;
            letter-spacing: -0.3px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.6);
            border: 1px solid #FFFFFF;
            white-space: nowrap;
        }

        @keyframes cits-pulse-ring {
            0% { box-shadow: 0 0 0 0 rgba(0, 229, 255, 0.8), 0 3px 8px rgba(0,0,0,0.6); }
            70% { box-shadow: 0 0 0 10px rgba(0, 229, 255, 0), 0 3px 8px rgba(0,0,0,0.6); }
            100% { box-shadow: 0 0 0 0 rgba(0, 229, 255, 0), 0 3px 8px rgba(0,0,0,0.6); }
        }

        /* 지도 좌측 상단 범례 박스 */
        .map-legend-box {
            position: absolute;
            top: 14px;
            left: 14px;
            z-index: 1000;
            background: rgba(19, 23, 34, 0.90);
            backdrop-filter: blur(8px);
            border: 1px solid rgba(255, 255, 255, 0.18);
            border-radius: 8px;
            padding: 6px 10px;
            display: flex;
            flex-direction: column;
            gap: 4px;
            box-shadow: 0 4px 12px rgba(0,0,0,0.6);
            pointer-events: auto;
        }
        .legend-row {
            display: flex;
            align-items: center;
            gap: 6px;
            font-size: 11px;
            color: #E2E8F0;
            font-weight: 600;
        }
        .legend-icon-cits {
            width: 12px;
            height: 12px;
            border-radius: 50%;
            background: linear-gradient(135deg, #00E5FF, #00C853);
            border: 1.5px solid #FFFFFF;
            box-shadow: 0 0 6px #00E5FF;
            display: inline-block;
        }
        .legend-icon-normal {
            width: 12px;
            height: 12px;
            border-radius: 50%;
            background: #FF9100;
            border: 1.5px solid #FFFFFF;
            display: inline-block;
        }

        /* 실시간 내 위치 펄싱 및 진행방향 쉐브론 마커 */
        .user-loc-wrapper {
            position: relative;
            width: 36px;
            height: 36px;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .user-loc-radar {
            width: 46px;
            height: 46px;
            border-radius: 50%;
            background: rgba(41, 121, 255, 0.3);
            border: 2px solid #2979FF;
            position: absolute;
            top: -5px;
            left: -5px;
            z-index: 1;
            animation: radar-wave 2s infinite ease-out;
            pointer-events: none;
        }
        .user-loc-dot {
            width: 14px;
            height: 14px;
            border-radius: 50%;
            background: #2979FF;
            border: 2px solid #FFFFFF;
            position: absolute;
            top: 11px;
            left: 11px;
            z-index: 2;
        }
        .user-loc-arrow {
            position: absolute;
            width: 26px;
            height: 26px;
            top: 5px;
            left: 5px;
            z-index: 3;
            pointer-events: none;
            transition: transform 0.20s ease-out;
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .user-loc-offroute {
            background: #FF1744 !important;
            box-shadow: 0 0 14px rgba(255, 23, 68, 1.0) !important;
        }
        .radar-offroute {
            background: rgba(255, 23, 68, 0.35) !important;
            border-color: #FF1744 !important;
        }

        @keyframes radar-wave {
            0% { transform: scale(0.4); opacity: 1; }
            100% { transform: scale(1.8); opacity: 0; }
        }
        @keyframes pulse-ring {
            0% { box-shadow: 0 0 0 0 rgba(255,255,255,0.7), 0 4px 10px rgba(0,0,0,0.6); }
            70% { box-shadow: 0 0 0 10px rgba(255,255,255,0), 0 4px 10px rgba(0,0,0,0.6); }
            100% { box-shadow: 0 0 0 0 rgba(255,255,255,0), 0 4px 10px rgba(0,0,0,0.6); }
        }

        /* 툴팁 및 팝업 고대비 디자인 */
        .leaflet-tooltip.user-tooltip {
            background: #0D47A1;
            color: #FFFFFF;
            font-size: 11px;
            font-weight: 800;
            border: 1px solid #64B5F6;
            border-radius: 6px;
            box-shadow: 0 2px 8px rgba(0,0,0,0.6);
            padding: 3px 7px;
            white-space: nowrap;
        }
        .leaflet-popup-content-wrapper {
            background: #1E2638 !important;
            color: #ffffff !important;
            font-size: 13px !important;
            font-weight: bold !important;
            border-radius: 8px !important;
            border: 1.5px solid #4C5B7F !important;
            box-shadow: 0 4px 12px rgba(0,0,0,0.6) !important;
            padding: 6px 10px !important;
        }
        .leaflet-popup-tip {
            background: #1E2638 !important;
        }
    </style>
</head>
<body>
    <div id="map-viewport">
        <div id="map"></div>
        <div class="map-legend-box">
            <div class="legend-row">
                <span class="legend-icon-cits"></span>
                <span>C-ITS 신호 연동</span>
            </div>
            <div class="legend-row">
                <span class="legend-icon-normal"></span>
                <span>일반 횡단보도</span>
            </div>
        </div>
    </div>

    <div class="map-control-panel">
        <button type="button" id="btn-heading" class="map-btn" onclick="toggleHeadingMode()">🧭 진행방향 위</button>
        <button type="button" class="map-btn" onclick="focusUserLocation()">📍 내 위치</button>
        <button type="button" class="map-btn" onclick="fitRouteBounds()">🔍 전체 경로</button>
    </div>

    <script>
        var coords = $coordsArray;
        var originText = $safeOrigin;
        var destText = $safeDest;
        var crosswalks = $crosswalksArray;
        var hasInitLoc = $hasInitLoc;
        var initLat = $initLat;
        var initLon = $initLon;
        var isOffRoute = $initialOffRoute;
        var currentHeading = $initialHeading;
        var isHeadingUp = $initialHeadingUp;
        var currentContinuousMapAngle = -$initialHeading;
        var currentContinuousArrowAngle = $initialHeading;
        var hasInitializedMapAngle = false;
        var hasInitializedArrowAngle = false;
        var lastPannedLatLng = null;

        var map = null;
        var routePolyline = null;
        var routeBounds = null;
        var routeLayer = null;
        var userMarker = null;

        document.addEventListener("DOMContentLoaded", function() {
            initDetailedMap();
        });

        function initDetailedMap() {
            if (typeof L === 'undefined') {
                setTimeout(initDetailedMap, 100);
                return;
            }

            var centerLat = (coords && coords.length > 0) ? coords[0][0] : initLat;
            var centerLon = (coords && coords.length > 0) ? coords[0][1] : initLon;

            map = L.map('map', {
                zoomControl: false,
                attributionControl: false,
                tap: true
            }).setView([centerLat, centerLon], 17);

            // [1] 국토교통부 VWorld 대한민국 표준 고해상도 2D 정밀 전자지도 (기본 타일)
            var vworldTile = L.tileLayer('https://xdworld.vworld.kr/2d/Base/service/{z}/{x}/{y}.png', {
                maxZoom: 19,
                minZoom: 6
            }).addTo(map);

            // [2] 타일 에러 발생 시 OpenStreetMap 표준 타일로 자동 안전 전환
            vworldTile.on('tileerror', function() {
                console.warn("VWorld tile error, falling back to OpenStreetMap");
                var osmTile = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19
                }).addTo(map);

                // [3] OSM도 실패할 경우 CartoDB Voyager 타일로 최종 전환
                osmTile.on('tileerror', function() {
                    console.warn("OSM tile error, falling back to CartoDB Voyager");
                    L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
                        maxZoom: 19,
                        subdomains: 'abcd'
                    }).addTo(map);
                });
            });

            drawRouteLayers();
            if (!hasInitLoc && routeBounds) {
                map.fitBounds(routeBounds, {
                    padding: [36, 36],
                    maxZoom: 18
                });
            }

            // 초기 내 위치 표시 및 진행방향 각도 회전
            if (hasInitLoc) {
                updateUserLocation(initLat, initLon, isOffRoute, isHeadingUp, currentHeading, isHeadingUp);
            } else {
                applyMapRotation(currentHeading, isHeadingUp);
            }
            updateHeadingButtonUi();
        }

        // 경로선/출발·도착/횡단보도 마커를 그린다. 재탐색 시 지도 시점(위치·줌·회전)을 바꾸지 않고 이 레이어만 교체한다.
        function drawRouteLayers() {
            if (!map) return;
            if (routeLayer) {
                routeLayer.clearLayers();
            } else {
                routeLayer = L.layerGroup().addTo(map);
            }
            routePolyline = null;
            routeBounds = null;
            if (coords && coords.length > 0) {
                // 외곽 두꺼운 고대비 네이비 블루 테두리선
                L.polyline(coords, {
                    color: '#0D47A1',
                    weight: 9,
                    opacity: 0.95,
                    lineJoin: 'round',
                    lineCap: 'round'
                }).addTo(routeLayer);

                // 중심 보행 경로선 (고대비 형광 옐로우)
                routePolyline = L.polyline(coords, {
                    color: '#FFEA00',
                    weight: 5.5,
                    opacity: 1.0,
                    lineJoin: 'round',
                    lineCap: 'round'
                }).addTo(routeLayer);

                routeBounds = routePolyline.getBounds();

                // 출발지 마커 (🟢)
                var startIcon = L.divIcon({
                    className: 'custom-pin-container',
                    html: '<div class="pin-marker start-pin">출</div>',
                    iconSize: [32, 32],
                    iconAnchor: [16, 16]
                });
                L.marker(coords[0], { icon: startIcon }).addTo(routeLayer)
                    .bindPopup("🟢 출발지: " + originText);

                // 도착지 마커 (🔴)
                var endIcon = L.divIcon({
                    className: 'custom-pin-container',
                    html: '<div class="pin-marker end-pin">도</div>',
                    iconSize: [32, 32],
                    iconAnchor: [16, 16]
                });
                L.marker(coords[coords.length - 1], { icon: endIcon }).addTo(routeLayer)
                    .bindPopup("🔴 목적지: " + destText);

                // 횡단보도 마커 (C-ITS 실시간 신호 🚦 vs 일반 건널목 🚶)
                if (crosswalks && crosswalks.length > 0) {
                    crosswalks.forEach(function(cw) {
                        var isCits = cw.isCits === true;
                        var iconHtml = isCits 
                            ? '<div class="cits-crosswalk-pin">🚦<span class="cits-sub-badge">C-ITS</span></div>'
                            : '<div class="crosswalk-pin">🚶</div>';
                        var iconSize = isCits ? [34, 34] : [28, 28];
                        var iconAnchor = isCits ? [17, 17] : [14, 14];

                        var cwIcon = L.divIcon({
                            className: 'custom-cw-container',
                            html: iconHtml,
                            iconSize: iconSize,
                            iconAnchor: iconAnchor
                        });

                        var popupContent = isCits
                            ? '<div style="font-family: sans-serif; line-height: 1.4; min-width: 170px;">' +
                              '<b style="color: #00E676; font-size: 13px;">🚦 C-ITS 실시간 신호 연동</b><br/>' +
                              '<span style="font-size: 11px; color: #E2E8F0;">' + (cw.desc || "실시간 신호 확인 가능") + '</span><br/>' +
                              '<div style="margin-top:5px; font-size: 10px; color: #00E5FF; font-weight:bold; background:rgba(0,229,255,0.15); border:1px solid rgba(0,229,255,0.4); padding:3px 6px; border-radius:4px;">✓ 잔여시간 실시간 음성 안내</div>' +
                              '</div>'
                            : '<div style="font-family: sans-serif; line-height: 1.4; min-width: 150px;">' +
                              '<b style="color: #FF9100; font-size: 13px;">🚶 일반 횡단보도</b><br/>' +
                              '<span style="font-size: 11px; color: #E2E8F0;">' + (cw.desc || "신호 직접 확인 필요") + '</span><br/>' +
                              '<div style="margin-top:5px; font-size: 10px; color: #FFB74D; background:rgba(255,145,0,0.15); border:1px solid rgba(255,145,0,0.3); padding:3px 6px; border-radius:4px;">※ 카메라 비전 보조 사용</div>' +
                              '</div>';

                        L.marker([cw.lat, cw.lon], { icon: cwIcon }).addTo(routeLayer)
                            .bindPopup(popupContent);
                    });
                }

            }
        }

        // 네이티브에서 재탐색된 경로를 전달받아 페이지 재로드 없이 교체 (지도 깜빡임/시점 초기화 방지)
        function replaceRoute(newCoords, newCrosswalks) {
            coords = newCoords;
            crosswalks = newCrosswalks;
            drawRouteLayers();
        }

        // 지도 회전 적용 함수: 최단 각도 누적(Unwrap) 및 2.5도 불감대(Deadband) 필터 적용 (360도 풍차 회전 및 잔떨림 원천 방지)
        function applyMapRotation(headingDeg, headingUp) {
            var mapEl = document.getElementById('map');
            if (!mapEl) return;
            if (headingUp) {
                var targetRot = -headingDeg;
                if (!hasInitializedMapAngle) {
                    currentContinuousMapAngle = targetRot;
                    hasInitializedMapAngle = true;
                    mapEl.style.transform = "rotate(" + currentContinuousMapAngle + "deg)";
                    return;
                }
                // -180 ~ +180 범위의 최단 회전 각도차(delta) 산출
                var delta = (targetRot - currentContinuousMapAngle) % 360;
                if (delta > 180) delta -= 360;
                if (delta < -180) delta += 360;

                // 6도 미만의 미세 손떨림 및 발걸음 진자 운동은 무시하여 지도 고정 유지
                // (헤딩은 네이티브에서 이미 저역 통과 필터로 평활화되어 들어온다)
                if (Math.abs(delta) < 6.0) {
                    return;
                }
                currentContinuousMapAngle += delta;
                mapEl.style.transform = "rotate(" + currentContinuousMapAngle + "deg)";
            } else {
                // 북쪽 고정(North-Up) 시 0도로 최단각 복귀
                if (hasInitializedMapAngle) {
                    var delta = (0 - currentContinuousMapAngle) % 360;
                    if (delta > 180) delta -= 360;
                    if (delta < -180) delta += 360;
                    currentContinuousMapAngle += delta;
                    mapEl.style.transform = "rotate(" + currentContinuousMapAngle + "deg)";
                } else {
                    mapEl.style.transform = "rotate(0deg)";
                }
            }
        }

        // 헤딩 모드 토글 (진행방향 위 <-> 북쪽 위)
        function toggleHeadingMode() {
            isHeadingUp = !isHeadingUp;
            updateHeadingButtonUi();
            applyMapRotation(currentHeading, isHeadingUp);
            if (userMarker) {
                map.panTo(userMarker.getLatLng(), { animate: true, duration: 0.3 });
            }
        }

        function updateHeadingButtonUi() {
            var btn = document.getElementById('btn-heading');
            if (btn) {
                if (isHeadingUp) {
                    btn.className = "map-btn active-mode";
                    btn.innerHTML = "🧭 진행방향 위";
                } else {
                    btn.className = "map-btn";
                    btn.innerHTML = "🧭 북쪽 고정";
                }
            }
        }

        function setHeading(headingDeg, headingUp) {
            currentHeading = headingDeg;
            if (typeof headingUp === 'boolean') {
                isHeadingUp = headingUp;
                updateHeadingButtonUi();
            }
            applyMapRotation(currentHeading, isHeadingUp);
            updateMarkerArrow(currentHeading);
        }

        function updateMarkerArrow(headingDeg) {
            if (!userMarker) return;
            var el = userMarker.getElement();
            if (!el) return;
            var arrow = el.querySelector('.user-loc-arrow');
            if (arrow) {
                if (!hasInitializedArrowAngle) {
                    currentContinuousArrowAngle = headingDeg;
                    hasInitializedArrowAngle = true;
                    arrow.style.transform = "rotate(" + currentContinuousArrowAngle + "deg)";
                    return;
                }
                var delta = (headingDeg - currentContinuousArrowAngle) % 360;
                if (delta > 180) delta -= 360;
                if (delta < -180) delta += 360;
                currentContinuousArrowAngle += delta;
                arrow.style.transform = "rotate(" + currentContinuousArrowAngle + "deg)";
            }
        }

        // 실시간 내 위치 마커 생성 및 위치 갱신 함수 (네이티브 Android에서 evaluateJavascript로 호출)
        function updateUserLocation(lat, lon, offRoute, autoCenter, heading, headingUp) {
            if (!map || typeof L === 'undefined') return;
            var latLng = [lat, lon];
            var offRouteClass = offRoute ? " user-loc-offroute" : "";
            var offRouteRadar = offRoute ? " radar-offroute" : "";
            var tooltipText = offRoute ? "⚠️ 경로 이탈! 경로로 이동하세요" : "📍 현재 내 위치";

            if (typeof heading === 'number') {
                currentHeading = heading;
            }
            if (typeof headingUp === 'boolean') {
                isHeadingUp = headingUp;
                updateHeadingButtonUi();
            }

            if (!userMarker) {
                var userIcon = L.divIcon({
                    className: 'custom-user-pin',
                    html: '<div class="user-loc-wrapper">' +
                          '  <div class="user-loc-radar' + offRouteRadar + '"></div>' +
                          '  <div class="user-loc-dot' + offRouteClass + '"></div>' +
                          '  <div class="user-loc-arrow">' +
                          '    <svg viewBox="0 0 24 24" width="26" height="26">' +
                          '      <polygon points="12,2 22,21 12,16 2,21" fill="#00E5FF" stroke="#FFFFFF" stroke-width="2"/>' +
                          '    </svg>' +
                          '  </div>' +
                          '</div>',
                    iconSize: [36, 36],
                    iconAnchor: [18, 18]
                });

                userMarker = L.marker(latLng, { icon: userIcon, zIndexOffset: 2000 }).addTo(map);
                userMarker.bindTooltip(tooltipText, {
                    permanent: false,
                    direction: 'top',
                    offset: [0, -18],
                    className: 'user-tooltip'
                });
            } else {
                userMarker.setLatLng(latLng);
                userMarker.setTooltipContent(tooltipText);

                var el = userMarker.getElement();
                if (el) {
                    var dot = el.querySelector('.user-loc-dot');
                    var radar = el.querySelector('.user-loc-radar');
                    if (dot) dot.className = 'user-loc-dot' + offRouteClass;
                    if (radar) radar.className = 'user-loc-radar' + offRouteRadar;
                }
            }

            updateMarkerArrow(currentHeading);
            applyMapRotation(currentHeading, isHeadingUp);

            if (isHeadingUp || autoCenter) {
                var shouldPan = false;
                if (!lastPannedLatLng) {
                    shouldPan = true;
                } else {
                    var dLat = (lat - lastPannedLatLng[0]) * 111000;
                    var dLon = (lon - lastPannedLatLng[1]) * 111000 * Math.cos(lat * Math.PI / 180);
                    var distM = Math.sqrt(dLat * dLat + dLon * dLon);
                    // 4m 미만의 미세 GPS 지터(Jitter)는 카메라를 이동하지 않고 마커만 갱신 (화면 떨림 방지)
                    if (distM >= 4.0) {
                        shouldPan = true;
                    }
                }
                if (shouldPan) {
                    lastPannedLatLng = [lat, lon];
                    map.panTo(latLng, { animate: true, duration: 0.9, easeLinearity: 0.5 });
                }
            }
        }

        // 내 위치로 화면 이동 버튼 동작
        function focusUserLocation() {
            if (userMarker) {
                map.setView(userMarker.getLatLng(), 18, { animate: true });
                applyMapRotation(currentHeading, isHeadingUp);
            } else if (coords && coords.length > 0) {
                map.setView(coords[0], 18, { animate: true });
            }
        }

        // 전체 경로 한눈에 보기 버튼 동작
        function fitRouteBounds() {
            if (map && routeBounds) {
                // 전체 경로를 한눈에 볼 때는 북쪽 고정(0도)으로 전환하여 경로 전체를 똑바로 표출
                isHeadingUp = false;
                updateHeadingButtonUi();
                applyMapRotation(0, false);
                map.fitBounds(routeBounds, {
                    padding: [36, 36],
                    maxZoom: 18,
                    animate: true
                });
            }
        }
    </script>
</body>
</html>
    """.trimIndent()
}
