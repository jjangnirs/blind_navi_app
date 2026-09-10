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
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.PedestrianRoute
import org.json.JSONArray
import org.json.JSONObject

/**
 * Leaflet 및 고해상도 벡터/래스터 지도 타일을 활용한 실시간 보행 경로 지도 뷰어.
 *
 * - 실제 도시 도로망, 건물, 지명, 횡단보도가 표시됩니다.
 * - TMAP 보행자 API의 정밀 경로선(LineString)을 형광 하이라이트 폴리라인으로 시각화합니다.
 * - 출발지(🟢), 도착지(🔴), 횡단보도(🟠) 마커 및 팝업을 제공합니다.
 * - 전체 경로가 화면에 꽉 차도록 자동 줌/중심 맞춤(fitBounds)을 수행합니다.
 * - 부모 스크롤과 충돌 없이 핀치 줌 및 드래그 이동을 지원합니다.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RealRouteMapView(
    route: PedestrianRoute,
    originName: String,
    destinationName: String,
    modifier: Modifier = Modifier
) {
    val htmlContent = remember(route, originName, destinationName) {
        buildRouteMapHtml(route, originName, destinationName)
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
                        "https://safecross.local",
                        htmlContent,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            },
            update = { webView ->
                // 데이터가 변경되었을 때만 재로드
                val currentTag = webView.tag as? String
                if (currentTag != htmlContent) {
                    webView.tag = htmlContent
                    webView.loadDataWithBaseURL(
                        "https://safecross.local",
                        htmlContent,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        )
    }
}

/**
 * Leaflet 지도 렌더링을 위한 독립형 HTML 문서 생성.
 */
private fun buildRouteMapHtml(
    route: PedestrianRoute,
    originName: String,
    destinationName: String
): String {
    val coords = if (route.fullGeometry.isNotEmpty()) {
        route.fullGeometry
    } else {
        route.maneuvers.map { it.location }
    }

    // 좌표 목록을 JSON 배열로 변환 [[lat, lon], [lat, lon], ...]
    val coordsArray = JSONArray()
    for (pt in coords) {
        val ptArr = JSONArray()
        ptArr.put(pt.lat)
        ptArr.put(pt.lon)
        coordsArray.put(ptArr)
    }

    // 횡단보도 목록 추출
    val crosswalksArray = JSONArray()
    route.maneuvers.forEachIndexed { idx, m ->
        if (m.facilityType == "횡단보도" || m.turnType == 211) {
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

    return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=2.0, user-scalable=yes" />
    <link rel="stylesheet" href="file:///android_asset/leaflet/leaflet.css" onerror="this.onerror=null;this.href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';" />
    <script src="file:///android_asset/leaflet/leaflet.js" onerror="var s=document.createElement('script');s.src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';document.head.appendChild(s);"></script>
    <style>
        * { box-sizing: border-box; }
        html, body, #map {
            width: 100%;
            height: 100%;
            margin: 0;
            padding: 0;
            background-color: #131722;
            overflow: hidden;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        }
        .leaflet-container {
            background-color: #131722 !important;
        }
        /* 마커 핀 스타일 */
        .pin-marker {
            width: 32px;
            height: 32px;
            border-radius: 50%;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 4px 10px rgba(0,0,0,0.6);
            border: 2.5px solid #ffffff;
            font-weight: 900;
            font-size: 14px;
            color: #ffffff;
            animation: pulse-ring 2s infinite;
        }
        .start-pin {
            background: #00E676;
        }
        .end-pin {
            background: #FF1744;
        }
        .crosswalk-pin {
            width: 26px;
            height: 26px;
            border-radius: 50%;
            background: #FF9100;
            border: 2px solid #ffffff;
            box-shadow: 0 2px 6px rgba(0,0,0,0.5);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 13px;
        }
        @keyframes pulse-ring {
            0% { box-shadow: 0 0 0 0 rgba(255,255,255,0.7), 0 4px 10px rgba(0,0,0,0.6); }
            70% { box-shadow: 0 0 0 10px rgba(255,255,255,0), 0 4px 10px rgba(0,0,0,0.6); }
            100% { box-shadow: 0 0 0 0 rgba(255,255,255,0), 0 4px 10px rgba(0,0,0,0.6); }
        }
        /* 팝업 스타일 */
        .leaflet-popup-content-wrapper {
            background: #1E2638 !important;
            color: #ffffff !important;
            font-size: 12px !important;
            font-weight: bold !important;
            border-radius: 8px !important;
            border: 1.5px solid #4C5B7F !important;
            box-shadow: 0 4px 12px rgba(0,0,0,0.5) !important;
            padding: 4px 8px !important;
        }
        .leaflet-popup-tip {
            background: #1E2638 !important;
        }
        /* 확대/축소 버튼 커스텀 */
        .leaflet-control-zoom {
            border: none !important;
            box-shadow: 0 2px 8px rgba(0,0,0,0.4) !important;
        }
        .leaflet-control-zoom a {
            background-color: #1E2638 !important;
            color: #ffffff !important;
            border-color: #3B4660 !important;
            width: 32px !important;
            height: 32px !important;
            line-height: 32px !important;
            font-size: 18px !important;
            font-weight: bold !important;
        }
        .leaflet-control-zoom a:hover {
            background-color: #2D3954 !important;
        }
    </style>
</head>
<body>
    <div id="map"></div>
    <script>
        document.addEventListener("DOMContentLoaded", function() {
            initMap();
        });

        function initMap() {
            if (typeof L === 'undefined') {
                setTimeout(initMap, 150);
                return;
            }

            var map = L.map('map', {
                zoomControl: true,
                attributionControl: false,
                tap: true
            });

            // 고해상도 지도 타일 레이어 (CartoDB Voyager: 한국어 도로망, 건물, 보행로 상세 렌더링)
            var mainTiles = L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
                maxZoom: 19,
                subdomains: 'abcd'
            }).addTo(map);

            // 타일 로드 실패 시 OpenStreetMap 기본 타일 자동 백업
            mainTiles.on('tileerror', function() {
                L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19
                }).addTo(map);
            });

            var coords = $coordsArray;
            var originText = $safeOrigin;
            var destText = $safeDest;
            var crosswalks = $crosswalksArray;

            if (coords && coords.length > 0) {
                // 1. 외곽 글로우 / 테두리 선 (진한 네이비 블루, 두께 8px)
                var borderPolyline = L.polyline(coords, {
                    color: '#0D47A1',
                    weight: 8,
                    opacity: 0.85,
                    lineJoin: 'round',
                    lineCap: 'round'
                }).addTo(map);

                // 2. 중심 보행 경로선 (고대비 형광 노란색, 두께 5px)
                var corePolyline = L.polyline(coords, {
                    color: '#FFD600',
                    weight: 5,
                    opacity: 1.0,
                    lineJoin: 'round',
                    lineCap: 'round'
                }).addTo(map);

                // 3. 출발지 마커 (🟢)
                var startPt = coords[0];
                var startIcon = L.divIcon({
                    className: 'custom-pin-container',
                    html: '<div class="pin-marker start-pin">출</div>',
                    iconSize: [32, 32],
                    iconAnchor: [16, 16]
                });
                L.marker(startPt, { icon: startIcon }).addTo(map)
                    .bindPopup("🟢 출발지: " + originText);

                // 4. 도착지 마커 (🔴)
                var endPt = coords[coords.length - 1];
                var endIcon = L.divIcon({
                    className: 'custom-pin-container',
                    html: '<div class="pin-marker end-pin">도</div>',
                    iconSize: [32, 32],
                    iconAnchor: [16, 16]
                });
                L.marker(endPt, { icon: endIcon }).addTo(map)
                    .bindPopup("🔴 목적지: " + destText);

                // 5. 횡단보도 지점 마커 (🟠)
                if (crosswalks && crosswalks.length > 0) {
                    crosswalks.forEach(function(cw) {
                        var cwIcon = L.divIcon({
                            className: 'custom-cw-container',
                            html: '<div class="crosswalk-pin">🚶</div>',
                            iconSize: [26, 26],
                            iconAnchor: [13, 13]
                        });
                        L.marker([cw.lat, cw.lon], { icon: cwIcon }).addTo(map)
                            .bindPopup("🟠 횡단보도: " + (cw.desc || "신호 확인 후 횡단"));
                    });
                }

                // 6. 경로 전체가 카드 내에 꽉 차도록 여백과 함께 자동 맞춤
                map.fitBounds(borderPolyline.getBounds(), {
                    padding: [30, 30],
                    maxZoom: 17
                });
            } else {
                // 좌표가 없는 경우 대한민국 기본 좌표 중심 설정
                map.setView([35.1595, 126.8526], 15);
            }
        }
    </script>
</body>
</html>
    """.trimIndent()
}
