package kr.safecross.mobile.ui.screens.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.domain.model.WalkingMode
import kr.safecross.mobile.ui.theme.CardBackground
import kr.safecross.mobile.ui.theme.HighContrastBlack
import kr.safecross.mobile.ui.theme.HighContrastWhite
import kr.safecross.mobile.ui.theme.HighContrastYellow
import kr.safecross.mobile.ui.theme.WarningBannerBackground
import kr.safecross.mobile.ui.theme.WarningBorder
import kr.safecross.mobile.ui.screens.route.RealRouteMapView
import androidx.compose.ui.platform.LocalContext
import kr.safecross.mobile.navigation.NavigationFlightRecorder

@Composable
fun NavigationScreen(
    route: PedestrianRoute,
    viewModel: NavigationViewModel,
    voiceAnnouncer: VoiceAnnouncer?,
    onStopNavigation: () -> Unit,
    modifier: Modifier = Modifier,
    hapticFeedbackHelper: kr.safecross.mobile.accessibility.HapticFeedbackHelper? = null,
    onOpenCrossingAssist: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val navLogSummary by NavigationFlightRecorder.latestSummary.collectAsState()

    LaunchedEffect(route) {
        NavigationFlightRecorder.init(context)
        viewModel.setRoute(route)
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NavigationEffect.SpeakGuidance -> {
                    val queueMode = if (effect.queueFlush) {
                        android.speech.tts.TextToSpeech.QUEUE_FLUSH
                    } else {
                        android.speech.tts.TextToSpeech.QUEUE_ADD
                    }
                    voiceAnnouncer?.speak(effect.text, queueMode)
                    effect.hapticType?.let { hapticFeedbackHelper?.vibrate(it) }
                }
                is NavigationEffect.ShowOffRouteAlert -> {
                    voiceAnnouncer?.speak(effect.message)
                }
                is NavigationEffect.ShowGpsDegradedAlert -> {
                    voiceAnnouncer?.speak(effect.message)
                }
                is NavigationEffect.NavigationFinished -> {
                    onStopNavigation()
                }
                is NavigationEffect.TriggerCrossingAssist -> {
                    onOpenCrossingAssist()
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
            // 1. 보행 중 상단 접근성 한계 배너 및 실시간 GPS 수신 강도(%) 헤더
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 접근성 한계 안내 칩
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(WarningBannerBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, WarningBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = "주의. 본 경로는 턱낮춤(2cm 이하)과 점자블록 및 음향신호기를 보장하지 않습니다."
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = HighContrastYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "점자블록·음향신호기 미보장",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = HighContrastYellow
                        ),
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 실시간 GPS 수신 강도 % 뱃지
                val navSignal = uiState.gpsSignalStrengthPercent
                val navAccuracy = uiState.gpsAccuracyMeters
                val navColor = when {
                    navSignal >= 80 -> Color(0xFF4CAF50)
                    navSignal >= 60 -> Color(0xFF29B6F6)
                    navSignal >= 40 -> Color(0xFFFF9800)
                    else -> Color(0xFFFF5252)
                }
                Row(
                    modifier = Modifier
                        .background(navColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .border(1.dp, navColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = "GPS 수신 강도 ${navSignal}퍼센트, 정확도 ${String.format("%.1f", navAccuracy)}미터"
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📡 GPS ${navSignal}%" + if (navAccuracy > 0f) " (±${String.format("%.0f", navAccuracy)}m)" else "",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = navColor
                        )
                    )
                }
            }

            // 2. 4단계 보행 모드 상태 배지 (색상만으로 전달 금지, 텍스트 라벨 + 아이콘 병행)
            WalkingModeStatusBadge(walkingMode = uiState.walkingMode)

            // 3. 저시력자를 위한 전용 초고대비 대형 방향 안내 표시기 (80dp 심볼, 38sp 대형 거리, 24sp 행동)
            kr.safecross.mobile.ui.screens.navigation.components.LowVisionDirectionIndicator(
                action = uiState.currentDirectionAction,
                distanceMeters = uiState.distanceToNextManeuverMeters,
                currentManeuver = uiState.currentManeuver
            )

            // 3-1. 실시간 나침반/신체 진행 방향 정대(Orientation Alignment) 카드
            if (uiState.alignmentPromptMessage.isNotEmpty()) {
                val isAligned = uiState.isOrientationAligned
                val statusColor = if (isAligned) Color(0xFF00E676) else HighContrastYellow
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardBackground, RoundedCornerShape(16.dp))
                        .border(2.dp, statusColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "진행 방향 정대 상태. ${uiState.alignmentPromptMessage}"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isAligned) "🟢 경로 방향 정대 완료" else "🧭 몸 방향 회전 필요",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = statusColor
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = uiState.alignmentPromptMessage,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = HighContrastWhite
                        )
                    )
                }
            }

            // 3-2. 맞게 가고 있는지 실시간으로 확인하는 정밀 세부 지도 카드 (진행방향 위로 연동)
            val currentHeading = if (uiState.currentHeadingDegrees != 0f) {
                uiState.currentHeadingDegrees
            } else {
                viewModel.calculateTargetBearing()?.toFloat() ?: 0f
            }

            DetailedNavigationMapCard(
                route = route,
                currentLocation = uiState.currentLocation,
                currentManeuverIndex = uiState.currentManeuverIndex,
                isOffRoute = uiState.isOffRoute,
                headingDegrees = currentHeading,
                isHeadingUp = true
            )

            // 4. 보행 단계 진행 번호 및 안전 지침 안내
            val currentManeuver = uiState.currentManeuver
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(16.dp))
                    .border(2.dp, HighContrastYellow, RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "보행 상세. 현재 ${uiState.currentManeuverIndex + 1}단계 중 전체 ${route.maneuvers.size}단계. ${uiState.walkingMode.safetyGuidance}"
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "경로 진행 (${uiState.currentManeuverIndex + 1}/${route.maneuvers.size}단계)",
                    style = MaterialTheme.typography.titleMedium,
                    color = HighContrastYellow
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.walkingMode.safetyGuidance,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFFD54F)
                    )
                )
            }

            // 4. 다음 단계로 시뮬레이션 이동 버튼 (테스트 및 진행용, 최소 48dp)
            OutlinedButton(
                onClick = { viewModel.advanceToNextManeuver() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "다음 보행 단계로 진행합니다."
                    },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF666666)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HighContrastWhite)
            ) {
                Text(
                    text = "다음 안내 지점으로 진행",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
                )
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // 5. 주의사항 및 음성 다시 듣기 버튼 (최소 64dp)
            OutlinedButton(
                onClick = { viewModel.repeatCurrentGuidance() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "현재 보행 안내와 안전 주의사항을 음성으로 다시 듣습니다."
                    },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, HighContrastYellow),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HighContrastYellow)
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "안내 음성 다시 듣기",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )
            }

            // 6. 보행신호 확인 버튼 (사용자 명시 동작 후만 카메라 활성화, SR-F-040)
            Button(
                onClick = onOpenCrossingAssist,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "보행신호 확인. 카메라를 켜고 횡단보도와 보행 신호를 확인합니다."
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
                    tint = HighContrastBlack,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "보행신호 확인 (카메라 횡단 보조)",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = HighContrastBlack
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 7. 실시간 경로 분석 진단 로그 및 원클릭 공유 버튼
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E2638), RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFF3B4660), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "📊 실시간 경로 분석 상태",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF81D4FA),
                        fontSize = 13.sp
                    )
                )

                if (navLogSummary.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = navLogSummary,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = Color(0xFFE0E0E0),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val logs = NavigationFlightRecorder.readRecentLogs(context, 150)
                        val sendIntent = android.content.Intent().apply {
                            action = android.content.Intent.ACTION_SEND
                            putExtra(android.content.Intent.EXTRA_TEXT, logs)
                            type = "text/plain"
                        }
                        val shareIntent = android.content.Intent.createChooser(sendIntent, "경로 분석 진단 로그 공유")
                        context.startActivity(shareIntent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF263238),
                        contentColor = HighContrastWhite
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF81D4FA)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "경로 분석 진단 로그 공유/저장",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF81D4FA)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 8. 보행 안내 종료 버튼 (최소 64dp)
            Button(
                onClick = { viewModel.stopNavigation() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "보행 내비게이션을 즉시 종료하고 메인 화면으로 돌아갑니다."
                    },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = HighContrastWhite
                )
            ) {
                Text(
                    text = "보행 안내 종료",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    )
                )
            }
        }
    }
}

/**
 * 4단계 보행 모드 상태 배지 컴포넌트 (텍스트 + 아이콘 + semantics 병행)
 */
@Composable
fun WalkingModeStatusBadge(
    walkingMode: WalkingMode,
    modifier: Modifier = Modifier
) {
    val icon = when (walkingMode) {
        WalkingMode.IDLE -> Icons.Default.PlayArrow
        WalkingMode.WALKING -> Icons.Default.PlayArrow
        WalkingMode.APPROACHING_CROSSING -> Icons.Default.Notifications
        WalkingMode.CROSSING -> Icons.Default.Warning
    }
    val badgeColor = when (walkingMode) {
        WalkingMode.IDLE -> Color(0xFF757575)
        WalkingMode.WALKING -> Color(0xFF00E676)
        WalkingMode.APPROACHING_CROSSING -> HighContrastYellow
        WalkingMode.CROSSING -> Color(0xFFFF5252)
    }


    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(12.dp))
            .border(2.dp, badgeColor, RoundedCornerShape(12.dp))
            .padding(14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "보행 상태: ${walkingMode.label}. ${walkingMode.description}"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = badgeColor,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = walkingMode.label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = badgeColor
                )
            )
            Text(
                text = walkingMode.description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    color = HighContrastWhite
                )
            )
        }
    }
}

/**
 * 실시간 보행 경로 및 내 위치를 상세 도로/건물 지도 위에 표출하는 세부 지도 카드.
 */
@Composable
fun DetailedNavigationMapCard(
    route: PedestrianRoute,
    currentLocation: kr.safecross.mobile.domain.model.LocationPoint?,
    currentManeuverIndex: Int,
    isOffRoute: Boolean,
    headingDegrees: Float = 0f,
    isHeadingUp: Boolean = true,
    modifier: Modifier = Modifier
) {
    val originName = route.maneuvers.firstOrNull()?.instruction ?: "출발지"
    val destName = route.maneuvers.lastOrNull()?.instruction ?: "도착지"
    val borderStrokeColor = if (isOffRoute) Color(0xFFFF1744) else Color(0xFF2979FF)
    val statusChipText = if (isOffRoute) "⚠️ 경로 이탈 주의" else "🔵 경로 정상 진행 중"
    val statusChipColor = if (isOffRoute) Color(0xFFFF1744) else Color(0xFF00E676)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(16.dp))
            .border(2.dp, borderStrokeColor, RoundedCornerShape(16.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "실시간 보행 세부 지도. $statusChipText. 도로, 건물, 현재 위치 및 보행 경로가 지도 위에 표시됩니다."
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🗺️ 실시간 세부 보행 지도",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = HighContrastWhite,
                    fontSize = 16.sp
                )
            )

            Row(
                modifier = Modifier
                    .background(statusChipColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                    .border(1.dp, statusChipColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusChipText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = statusChipColor,
                        fontSize = 12.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 고정밀 국토교통부 VWorld 세부 지도 뷰어 (진행방향 위로 회전 연동)
        RealRouteMapView(
            route = route,
            originName = originName,
            destinationName = destName,
            currentLocation = currentLocation,
            currentManeuverIndex = currentManeuverIndex,
            isOffRoute = isOffRoute,
            headingDegrees = headingDegrees,
            isHeadingUp = isHeadingUp,
            showLiveTrackingControls = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🧭 진행방향 위로 회전 | 📍 내 위치 | 🟢/🔴 출발/도착",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    color = Color(0xFFB0BEC5)
                )
            )
        }
    }
}
