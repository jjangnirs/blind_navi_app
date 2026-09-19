package kr.safecross.mobile.ui.screens.destination

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kr.safecross.mobile.domain.model.LocationPoint
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.safecross.mobile.accessibility.VoiceAnnouncer
import kr.safecross.mobile.domain.model.DestinationItem
import kr.safecross.mobile.ui.theme.CardBackground
import kr.safecross.mobile.ui.theme.HighContrastBlack
import kr.safecross.mobile.ui.theme.HighContrastWhite
import kr.safecross.mobile.ui.theme.HighContrastYellow
import kr.safecross.mobile.ui.theme.TextSecondary

@Composable
fun DestinationScreen(
    viewModel: DestinationViewModel,
    voiceAnnouncer: VoiceAnnouncer?,
    onNavigateToRouteSummary: (DestinationItem) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    currentGps: LocationPoint? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    // Android 음성 인식 (SpeechRecognizer Intent) 런처
    val voiceLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenList = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val recognizedText = spokenList?.firstOrNull()
            if (!recognizedText.isNullOrBlank()) {
                viewModel.onVoiceInputResult(recognizedText)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onScreenStarted()
        viewModel.effects.collect { effect ->
            when (effect) {
                is DestinationEffect.SpeakAnnouncement -> {
                    voiceAnnouncer?.speak(effect.text)
                }
                is DestinationEffect.NavigateToRouteSummary -> {
                    onNavigateToRouteSummary(effect.destination)
                }
                is DestinationEffect.NavigateToSettings -> {
                    onNavigateToSettings()
                }
                is DestinationEffect.StartVoiceInput -> {
                    val speechIntent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "가고 싶은 목적지를 말씀하세요 (예: 강남역, 시청, 편의점)")
                    }
                    try {
                        voiceLauncher.launch(speechIntent)
                    } catch (_: Exception) {
                        voiceAnnouncer?.speak("음성 인식 서비스를 실행할 수 없습니다. 텍스트 검색을 이용하세요.")
                    }
                }
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = HighContrastBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 상단 바: 타이틀 및 설정 버튼 (48dp 이상)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "목적지 선택",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = HighContrastYellow
                    )
                )
                IconButton(
                    onClick = { viewModel.openSettings() },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "앱 환경설정 화면으로 이동합니다."
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = HighContrastWhite,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // GPS 신호로 찾은 현재 위치 (출발지) 및 실시간 수신 강도(%) 카드
            val signalPercent = uiState.gpsSignalStrengthPercent
            val accuracy = uiState.gpsAccuracyMeters
            val signalColor = when {
                signalPercent >= 80 -> Color(0xFF4CAF50) // 초정밀/우수 (녹색)
                signalPercent >= 60 -> Color(0xFF29B6F6) // 양호 (하늘색)
                signalPercent >= 40 -> Color(0xFFFF9800) // 보통 (주황색)
                else -> Color(0xFFFF5252) // 신호 약함 (빨간색)
            }
            val signalLabel = when {
                signalPercent >= 85 -> "L1+L5 초정밀 수신"
                signalPercent >= 70 -> "우수"
                signalPercent >= 50 -> "양호"
                signalPercent >= 30 -> "수신 대기 중"
                else -> "신호 탐색 중"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E2638), RoundedCornerShape(12.dp))
                    .border(
                        1.5.dp,
                        signalColor,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(14.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "현재 출발지 위치. ${uiState.currentLocationAddress ?: "스마트폰 GPS 신호를 수신 중입니다."}. GPS 수신 강도 ${signalPercent}퍼센트, 정확도 ${String.format("%.1f", accuracy)}미터, 상태는 ${signalLabel}입니다."
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            tint = signalColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = if (uiState.isGpsReady) "현재 출발지" else "출발지 탐색 중...",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = signalColor
                            )
                        )
                    }

                    // GPS 수신 강도 % 및 상태 뱃지
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(signalColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .border(1.dp, signalColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "📡 GPS $signalPercent% ($signalLabel)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = signalColor,
                                fontSize = 12.sp
                            )
                        )
                    }
                }

                Text(
                    text = uiState.currentLocationAddress ?: "스마트폰 GPS 신호를 수신하고 있습니다...",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = HighContrastWhite,
                        fontSize = 16.sp
                    )
                )

                if (accuracy > 0f) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "위치 정확도: ±${String.format("%.1f", accuracy)}m",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        )
                        Text(
                            text = if (signalPercent >= 80) "S25 Ultra 듀얼 GNSS 연동" else "위성 신호 정밀 락 대기 중",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (signalPercent >= 80) Color(0xFF81C784) else Color(0xFFFFB74D),
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            // 음성 검색 버튼 (최소 64dp 터치 타깃)
            Button(
                onClick = { viewModel.onVoiceInputClicked() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "음성으로 목적지 말하기 버튼. 누른 후 목적지를 말씀하세요."
                    },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HighContrastYellow,
                    contentColor = HighContrastBlack
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "음성으로 목적지 말하기",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    )
                )
            }

            // 텍스트 검색 입력 필드 (최소 56dp)
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .semantics {
                        contentDescription = "목적지 직접 검색 입력창. 현재 검색어는 ${uiState.searchQuery.ifEmpty { "비어 있음" }}입니다."
                    },
                label = {
                    Text(
                        text = "목적지 이름 또는 주소 검색",
                        color = TextSecondary,
                        fontSize = 16.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = HighContrastYellow
                    )
                },
                trailingIcon = {
                    if (uiState.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = HighContrastYellow,
                            strokeWidth = 2.5.dp
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = HighContrastWhite,
                    unfocusedTextColor = HighContrastWhite,
                    focusedBorderColor = HighContrastYellow,
                    unfocusedBorderColor = Color(0xFF666666)
                )
            )

            // 추천 및 즐겨찾기 또는 검색 결과 헤더
            val headerText = if (uiState.searchQuery.isNotBlank()) {
                if (uiState.isSearching) "검색 중..." else "검색 결과 (${uiState.destinations.size}건)"
            } else {
                "추천 및 즐겨찾기 목적지"
            }

            Text(
                text = headerText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = HighContrastWhite
                ),
                modifier = Modifier.padding(top = 8.dp)
            )

            // 검색 결과 없음 안내
            if (uiState.destinations.isEmpty() && !uiState.isSearching && uiState.searchQuery.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackground, RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF444444), RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🔍 검색 결과가 없습니다",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = HighContrastYellow
                        )
                    )
                    Text(
                        text = "'${uiState.searchQuery}'에 해당하는 실제 장소를 찾을 수 없습니다.\n건물명, 역 이름, 상호 등을 더 정확히 입력해 보세요.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = HighContrastWhite,
                            fontSize = 14.sp
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }

            // 목적지 목록 (각 항목 최소 64dp 터치 타깃)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                uiState.destinations.forEach { item ->
                    DestinationCardItem(
                        item = item,
                        currentLocation = currentGps,
                        onClick = { viewModel.selectDestination(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationCardItem(
    item: DestinationItem,
    currentLocation: LocationPoint? = null,
    onClick: () -> Unit
) {
    // 현재 위치와의 거리 산출
    val distanceMeters = currentLocation?.let { calculateDistanceMeters(it, item.location) }
    val distanceText = distanceMeters?.let { meters ->
        if (meters < 1000) "${meters.toInt()}m" else "${String.format("%.1f", meters / 1000.0)}km"
    }
    val isWalkable = (distanceMeters ?: 0.0) <= 5000.0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(CardBackground, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, Color(0xFF444444)), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                val distDesc = distanceText?.let { ", 현재 위치에서 $it" } ?: ""
                contentDescription = "목적지 ${item.name}. 주소: ${item.address}$distDesc. ${if (item.isFavorite) "즐겨찾기 항목." else ""} 선택하려면 두 번 탭하세요."
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Default.Place,
            contentDescription = null,
            tint = if (item.isFavorite) HighContrastYellow else TextSecondary,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = HighContrastWhite
                    ),
                    modifier = Modifier.weight(1f, fill = false)
                )

                // 거리 뱃지
                if (distanceText != null) {
                    val badgeColor = if (isWalkable) Color(0xFF00E676) else Color(0xFFFF5252)
                    Row(
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .border(1.dp, badgeColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isWalkable) "🚶 $distanceText" else "⚠️ $distanceText (초과)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = badgeColor,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.address,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            )
        }
    }
}

private fun calculateDistanceMeters(p1: LocationPoint, p2: LocationPoint): Double {
    val r = 6371000.0
    val lat1 = Math.toRadians(p1.lat)
    val lat2 = Math.toRadians(p2.lat)
    val dLat = Math.toRadians(p2.lat - p1.lat)
    val dLon = Math.toRadians(p2.lon - p1.lon)
    val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2).let { it * it }
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}

