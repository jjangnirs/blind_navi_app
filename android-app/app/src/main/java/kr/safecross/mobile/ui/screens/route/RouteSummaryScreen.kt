package kr.safecross.mobile.ui.screens.route

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.safecross.mobile.accessibility.VoiceAnnouncer
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.domain.model.PedestrianRoute
import kr.safecross.mobile.ui.theme.CardBackground
import kr.safecross.mobile.ui.theme.HighContrastBlack
import kr.safecross.mobile.ui.theme.HighContrastWhite
import kr.safecross.mobile.ui.theme.HighContrastYellow
import kr.safecross.mobile.ui.theme.TextSecondary
import kr.safecross.mobile.ui.theme.WarningBannerBackground
import kr.safecross.mobile.ui.theme.WarningBorder

@Composable
fun RouteSummaryScreen(
    viewModel: RouteSummaryViewModel,
    voiceAnnouncer: VoiceAnnouncer?,
    onNavigateToNavigation: (PedestrianRoute) -> Unit,
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // ViewModel의 Effect를 수신하여 TTS 음성 낭독 및 화면 전이 수행
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is RouteSummaryEffect.SpeakDisclaimer -> {
                    voiceAnnouncer?.speak(effect.text)
                }
                is RouteSummaryEffect.NavigateToNavigation -> {
                    onNavigateToNavigation(effect.route)
                }
                is RouteSummaryEffect.ShowToast -> {}
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = HighContrastBlack
    ) {
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    color = HighContrastYellow,
                    strokeWidth = 6.dp
                )
            }
        } else if (uiState.errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = uiState.errorMessage ?: "오류가 발생했습니다.",
                    style = MaterialTheme.typography.titleLarge,
                    color = HighContrastWhite
                )
            }
        } else {
            val route = uiState.route
            if (route != null) {
                RouteSummaryContent(
                    route = route,
                    originName = uiState.originName,
                    destinationName = uiState.destinationName,
                    onRepeatSpeech = { viewModel.repeatDisclaimerSpeech() },
                    onConfirmAndStart = { viewModel.onConfirmAndStartNavigation() },
                    onNavigateBack = onNavigateBack
                )
            }
        }
    }
}

@Composable
private fun RouteSummaryContent(
    route: PedestrianRoute,
    originName: String,
    destinationName: String,
    onRepeatSpeech: () -> Unit,
    onConfirmAndStart: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var showDisclaimerDialog by remember { mutableStateOf(false) }

    // 고지문 상세 팝업 다이얼로그
    if (showDisclaimerDialog) {
        DisclaimerDialog(
            disclaimerText = route.disclaimer,
            onDismiss = { showDisclaimerDialog = false },
            onRepeatSpeech = onRepeatSpeech
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 뒤로가기 버튼 (최소 48dp 터치 타깃)
        OutlinedButton(
            onClick = onNavigateBack,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "목적지 선택 화면으로 돌아갑니다."
                },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, TextSecondary),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Text(
                text = "← 목적지 다시 선택",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
            )
        }

        // 1. [요청 반영] 한 줄 컴팩트 보행 안전 및 접근성 한계 고지 바 (누르면 팝업 표시)
        CompactDisclaimerBar(
            onClick = { showDisclaimerDialog = true }
        )

        // 2. [요청 반영] 고지창이 축소된 공간을 활용한 보행 경로 지도 뷰 (Route Map View)
        RouteMapCard(
            route = route,
            originName = originName,
            destinationName = destinationName,
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        )

        // 3. 경로 요약 상세 정보 카드 (출발지/목적지/거리/시간 포함)
        RouteSummaryDetailsCard(
            route = route,
            originName = originName,
            destinationName = destinationName
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 4. 음성 고지 다시 듣기 버튼 (최소 64dp)
        OutlinedButton(
            onClick = onRepeatSpeech,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "경로 안내 주의사항과 접근성 한계 고지문을 음성으로 다시 듣습니다."
                },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(2.dp, HighContrastYellow),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = HighContrastYellow
            )
        ) {
            Text(
                text = "주의사항 다시 듣기 (음성)",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            )
        }

        // 5. 확인 후 안내 시작 버튼 (최소 64dp)
        Button(
            onClick = onConfirmAndStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "경로 주의사항을 확인했습니다. 보행 안내를 시작합니다."
                },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HighContrastYellow,
                contentColor = HighContrastBlack
            )
        ) {
            Text(
                text = "확인 후 보행 안내 시작",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp
                )
            )
        }
    }
}

/**
 * 1줄 컴팩트 안전 고지 바 (누르면 상세 팝업 오픈)
 */
@Composable
fun CompactDisclaimerBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .background(WarningBannerBackground, RoundedCornerShape(10.dp))
            .border(1.5.dp, WarningBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .semantics {
                role = Role.Button
                liveRegion = LiveRegionMode.Polite
                contentDescription = "보행 안전 및 접근성 한계 고지. 터치하면 상세 면책 고지 팝업이 열립니다."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = HighContrastYellow,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "보행 안전 및 접근성 한계 고지",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = HighContrastYellow
                ),
                maxLines = 1
            )
        }
        Text(
            text = "상세보기 ▾",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = HighContrastWhite,
                fontSize = 13.sp
            )
        )
    }
}

/**
 * 면책 고지 상세 팝업 다이얼로그
 */
@Composable
fun DisclaimerDialog(
    disclaimerText: String,
    onDismiss: () -> Unit,
    onRepeatSpeech: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E222B),
        titleContentColor = HighContrastYellow,
        textContentColor = HighContrastWhite,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = HighContrastYellow,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "보행 안전 및 접근성 한계 고지",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = disclaimerText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = HighContrastWhite
                    )
                )
                Text(
                    text = "• 계단 제외 옵션은 일반적인 완경사/경사로 기준이며, 휠체어 단차(2cm 이하)나 점자블록·음향신호기의 설치 여부를 보증하지 않습니다.\n• 보행 중에는 반드시 주변 차량과 현장 소리를 주의 깊게 확인하세요.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = TextSecondary
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HighContrastYellow,
                    contentColor = HighContrastBlack
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = "확인", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    onRepeatSpeech()
                },
                border = BorderStroke(1.dp, HighContrastYellow),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HighContrastYellow),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = "음성으로 듣기")
            }
        }
    )
}

/**
 * 실제 도로망, 건물 및 정밀 보행 경로선이 표시되는 실시간 지도 컴포넌트
 */
@Composable
fun RouteMapCard(
    route: PedestrianRoute,
    originName: String = "출발지",
    destinationName: String = "목적지",
    modifier: Modifier = Modifier
) {
    val durationMinutes = (route.totalDurationSeconds + 59) / 60
    val cwCount = route.maneuvers.count { it.facilityType == "횡단보도" || it.turnType == 211 }

    Box(
        modifier = modifier
            .background(Color(0xFF131722), RoundedCornerShape(12.dp))
            .border(1.5.dp, Color(0xFF2C3242), RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = "실시간 보행 경로 지도. 총 거리 ${route.totalDistanceMeters}미터, 소요시간 약 ${durationMinutes}분 경로가 시각화되어 있습니다."
            }
    ) {
        // 실제 거리/건물/도로망이 표시되는 인터랙티브 리얼 지도 뷰어
        RealRouteMapView(
            route = route,
            originName = originName,
            destinationName = destinationName,
            modifier = Modifier.fillMaxSize()
        )

        // 상단 오버레이: 지도 타이틀 및 거리/소요시간 칩
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xDD131722), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF3B4660), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "🗺️ 실시간 보행 경로 지도",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = HighContrastWhite,
                        fontSize = 11.sp
                    )
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xDD131722), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF3B4660), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${route.totalDistanceMeters}m (약 ${durationMinutes}분)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = HighContrastYellow,
                        fontSize = 11.sp
                    )
                )
            }
        }

        // 하단 오버레이: 지도 범례 (Legend)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color(0xDD131722), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF3B4660), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🟢 출발",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = HighContrastWhite,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Text(
                text = "🔴 도착",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = HighContrastWhite,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            if (cwCount > 0) {
                Text(
                    text = "🟠 횡단보도 ${cwCount}곳",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = HighContrastWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

@Composable
private fun RouteSummaryDetailsCard(
    route: PedestrianRoute,
    originName: String,
    destinationName: String,
    modifier: Modifier = Modifier
) {
    val durationMinutes = (route.totalDurationSeconds + 59) / 60

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF444444), RoundedCornerShape(12.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "경로 요약. 출발지 $originName, 목적지 $destinationName. 총 거리 ${route.totalDistanceMeters}미터, 예상 소요 시간 약 ${durationMinutes}분. 계단 제외 옵션 적용됨."
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "보행 경로 상세 (TMAP 기반)",
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary
        )

        // 출발지 정보
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "출발지 (GPS 위치)",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = originName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = HighContrastWhite
            )
        }

        // 목적지 정보
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "목적지",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = destinationName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = HighContrastYellow
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "총 거리",
                style = MaterialTheme.typography.titleMedium,
                color = HighContrastWhite
            )
            Text(
                text = "${route.totalDistanceMeters} m",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = HighContrastYellow
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "예상 소요 시간",
                style = MaterialTheme.typography.titleMedium,
                color = HighContrastWhite
            )
            Text(
                text = "약 $durationMinutes 분",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = HighContrastYellow
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "경로 옵션",
                style = MaterialTheme.typography.titleMedium,
                color = HighContrastWhite
            )
            Text(
                text = if (route.excludeStairs) "계단 제외 (단차 미보장)" else "일반 보행로",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (route.excludeStairs) HighContrastYellow else HighContrastWhite
            )
        }
    }
}
