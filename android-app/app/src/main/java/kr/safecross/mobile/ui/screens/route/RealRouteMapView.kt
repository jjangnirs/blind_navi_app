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
    showLiveTrackingControls: Boolean = true,
    tmapAppKey: String = BuildConfig.TMAP_APP_KEY,
    modifier: Modifier = Modifier
) {
    val htmlContent = remember(route, originName, destinationName) {
        buildRouteMapHtml(
            route = route,
            originName = originName,
            destinationName = destinationName,
            initialLocation = currentLocation,
            initialOffRoute = isOffRoute,
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
                            // 페이지 로드 완료 후 현재 위치 즉시 반영
                            currentLocation?.let { loc ->
                                view?.evaluateJavascript(
                                    "if (typeof updateUserLocation === 'function') { updateUserLocation(${loc.lat}, ${loc.lon}, $isOffRoute, false); }",
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
                    // HTML 재로드 없이 자바스크립트로 내 위치 마커만 실시간 부드럽게 갱신
                    if (currentLocation != null) {
                        val js = "if (typeof updateUserLocation === 'function') { updateUserLocation(${currentLocation.lat}, ${currentLocation.lon}, $isOffRoute, false); }"
                        webView.evaluateJavascript(js, null)
                    }
                }
            }
        )
    }
}

/**
 * 국토교통부 VWorld 정밀 국가 전자지도 타일 및 Leaflet 기반 독립형 HTML 문서 생성.
 */
private fun buildRouteMapHtml(
    route: PedestrianRoute,
    originName: String,
    destinationName: String,
    initialLocation: LocationPoint?,
    initialOffRoute: Boolean,
    showControls: Boolean
): String {
    val coords = if (route.fullGeometry.isNotEmpty()) {
        route.fullGeometry
    } else {
        route.maneuvers.map { it.location }
    }

    // 경로 좌표 목록을 JSON 배열로 변환 [[lat, lon], [lat, lon], ...]
    val coordsArray = JSONArray()
    for (pt in coords) {
        val ptArr = JSONArray()
        ptArr.put(pt.lat)
        ptArr.put(pt.lon)
        coordsArray.put(ptArr)
    }

    // 횡단보도 및 분기점 목록 추출
    val crosswalksArray = JSONArray()
    route.maneuvers.forEachIndexed { idx, m ->
        if (m.facilityType == "횡단보도" || m.turnType in 211..217) {
            val cwObj = JSONObject()
            cwObj.put("lat", m.location.lat)
            cwObj.put("lon", m.location.lon)
            cwObj.put("desc", m.instruction)
            cwObj.put("index", idx + 1)
            crosswalksArray.put(cwObj)
        }
    }

    val safeOrigin = JSONObject.quote(originName)
    val safeDest = JSONObject.quote(destinationName)

    val initLat = initialLocation?.lat ?: (coords.firstOrNull()?.lat ?: 35.1595)
    val initLon = initialLocation?.lon ?: (coords.firstOrNull()?.lon ?: 126.8526)
    val hasInitLoc = initialLocation != null

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
        html, body, #map {
            width: 100%;
            height: 100%;
            margin: 0;
            padding: 0;
            background-color: #131722;
            overflow: hidden;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", Helvetica, Arial, sans-serif;
        }

        /* 컨트롤 버튼 플로팅 패널 */
        .map-control-panel {
            position: absolute;
            right: 12px;
            bottom: 16px;
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
            padding: 8px 12px;
            font-size: 13px;
            font-weight: bold;
            box-shadow: 0 4px 10px rgba(0,0,0,0.5);
            cursor: pointer;
            display: flex;
            align-items: center;
            gap: 6px;
            user-select: none;
            touch-action: manipulation;
        }
        .map-btn:active {
            background: #2D3954;
            transform: scale(0.96);
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

        /* 실시간 내 위치 펄싱 마커 */
        .user-loc-wrapper {
            position: relative;
            width: 24px;
            height: 24px;
        }
        .user-loc-dot {
            width: 22px;
            height: 22px;
            border-radius: 50%;
            background: #2979FF;
            border: 3px solid #FFFFFF;
            box-shadow: 0 0 12px rgba(41, 121, 255, 0.9);
            position: absolute;
            top: 1px;
            left: 1px;
            z-index: 2;
        }
        .user-loc-radar {
            width: 48px;
            height: 48px;
            border-radius: 50%;
            background: rgba(41, 121, 255, 0.28);
            border: 2px solid #2979FF;
            position: absolute;
            top: -12px;
            left: -12px;
            z-index: 1;
            animation: radar-wave 2s infinite ease-out;
            pointer-events: none;
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
            100% { transform: scale(1.9); opacity: 0; }
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
    <div id="map"></div>

    <div class="map-control-panel">
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

        var map = null;
        var routePolyline = null;
        var routeBounds = null;
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

            // 보행 경로선(Polyline) 렌더링
            if (coords && coords.length > 0) {
                // 외곽 두꺼운 고대비 네이비 블루 테두리선
                L.polyline(coords, {
                    color: '#0D47A1',
                    weight: 9,
                    opacity: 0.95,
                    lineJoin: 'round',
                    lineCap: 'round'
                }).addTo(map);

                // 중심 보행 경로선 (고대비 형광 옐로우)
                routePolyline = L.polyline(coords, {
                    color: '#FFEA00',
                    weight: 5.5,
                    opacity: 1.0,
                    lineJoin: 'round',
                    lineCap: 'round'
                }).addTo(map);

                routeBounds = routePolyline.getBounds();

                // 출발지 마커 (🟢)
                var startIcon = L.divIcon({
                    className: 'custom-pin-container',
                    html: '<div class="pin-marker start-pin">출</div>',
                    iconSize: [32, 32],
                    iconAnchor: [16, 16]
                });
                L.marker(coords[0], { icon: startIcon }).addTo(map)
                    .bindPopup("🟢 출발지: " + originText);

                // 도착지 마커 (🔴)
                var endIcon = L.divIcon({
                    className: 'custom-pin-container',
                    html: '<div class="pin-marker end-pin">도</div>',
                    iconSize: [32, 32],
                    iconAnchor: [16, 16]
                });
                L.marker(coords[coords.length - 1], { icon: endIcon }).addTo(map)
                    .bindPopup("🔴 목적지: " + destText);

                // 횡단보도 마커 (🟠)
                if (crosswalks && crosswalks.length > 0) {
                    crosswalks.forEach(function(cw) {
                        var cwIcon = L.divIcon({
                            className: 'custom-cw-container',
                            html: '<div class="crosswalk-pin">🚶</div>',
                            iconSize: [28, 28],
                            iconAnchor: [14, 14]
                        });
                        L.marker([cw.lat, cw.lon], { icon: cwIcon }).addTo(map)
                            .bindPopup("🟠 횡단보도: " + (cw.desc || "신호 확인"));
                    });
                }

                // 전체 경로 자동 맞춤
                map.fitBounds(routeBounds, {
                    padding: [36, 36],
                    maxZoom: 18
                });
            }

            // 초기 내 위치 표시
            if (hasInitLoc) {
                updateUserLocation(initLat, initLon, isOffRoute, false);
            }
        }

        // 실시간 내 위치 마커 생성 및 위치 갱신 함수 (네이티브 Android에서 evaluateJavascript로 호출)
        function updateUserLocation(lat, lon, offRoute, autoCenter) {
            if (!map || typeof L === 'undefined') return;
            var latLng = [lat, lon];
            var offRouteClass = offRoute ? " user-loc-offroute" : "";
            var offRouteRadar = offRoute ? " radar-offroute" : "";
            var tooltipText = offRoute ? "⚠️ 경로 이탈! 경로로 이동하세요" : "📍 현재 내 위치 (정상 진행 중)";

            if (!userMarker) {
                var userIcon = L.divIcon({
                    className: 'custom-user-pin',
                    html: '<div class="user-loc-wrapper">' +
                          '  <div class="user-loc-radar' + offRouteRadar + '"></div>' +
                          '  <div class="user-loc-dot' + offRouteClass + '"></div>' +
                          '</div>',
                    iconSize: [24, 24],
                    iconAnchor: [12, 12]
                });

                userMarker = L.marker(latLng, { icon: userIcon, zIndexOffset: 2000 }).addTo(map);
                userMarker.bindTooltip(tooltipText, {
                    permanent: true,
                    direction: 'top',
                    offset: [0, -14],
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

            if (autoCenter) {
                map.panTo(latLng);
            }
        }

        // 내 위치로 화면 이동 버튼 동작
        function focusUserLocation() {
            if (userMarker) {
                map.setView(userMarker.getLatLng(), 18, { animate: true });
            } else if (coords && coords.length > 0) {
                map.setView(coords[0], 18, { animate: true });
            }
        }

        // 전체 경로 한눈에 보기 버튼 동작
        function fitRouteBounds() {
            if (map && routeBounds) {
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
