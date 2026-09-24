package kr.safecross.mobile.ui.screens.crossingassist

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.safecross.mobile.accessibility.HapticFeedbackHelper
import kr.safecross.mobile.accessibility.VoiceAnnouncer
import kr.safecross.mobile.decision.CrossingAssistDecisionState
import kr.safecross.mobile.perception.VerifiedCrossingContext
import kr.safecross.mobile.ui.theme.CardBackground
import kr.safecross.mobile.ui.theme.HighContrastBlack
import kr.safecross.mobile.ui.theme.HighContrastWhite
import kr.safecross.mobile.ui.theme.HighContrastYellow
import kr.safecross.mobile.ui.theme.WarningBannerBackground
import kr.safecross.mobile.ui.theme.WarningBorder

@Composable
fun CrossingAssistScreen(
    viewModel: CrossingAssistViewModel,
    crossingContext: VerifiedCrossingContext?,
    voiceAnnouncer: VoiceAnnouncer?,
    hapticHelper: HapticFeedbackHelper?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    // 1. 카메라 권한 런처 (사용자 명시 요청 기반, SR-F-040)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onCameraPermissionGranted(crossingContext)
        } else {
            viewModel.onCameraPermissionDenied()
        }
    }

    // 2. 화면 진입 시 권한 확인
    LaunchedEffect(Unit) {
        val hasCamPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCamPermission) {
            viewModel.onCameraPermissionGranted(crossingContext)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 3. 발화 및 햅틱 효과 처리
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CrossingAssistEffect.SpeakGuidance -> {
                    val queueMode = if (effect.queueFlush) {
                        android.speech.tts.TextToSpeech.QUEUE_FLUSH
                    } else {
                        android.speech.tts.TextToSpeech.QUEUE_ADD
                    }
                    voiceAnnouncer?.speak(effect.text, queueMode)
                    effect.hapticType?.let { hapticHelper?.vibrate(it) }
                }
                is CrossingAssistEffect.FinishScreen -> {
                    onClose()
                }
            }
        }
    }

    // 4. 수명주기 해제 안전장치 (앱 백그라운드, 화면 종료, 권한 취소 시 카메라 완전 해제, SR-F-049)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) {
                viewModel.stopAssistance()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopAssistance()
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
            // A. 상단 안전 주의 및 법적 한계 배너
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WarningBannerBackground, RoundedCornerShape(8.dp))
                    .border(1.dp, WarningBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics {
                        contentDescription = "주의. 본 추정은 보조 수단이며 절대 안전을 보장하지 않습니다. 주변 상황과 소리를 직접 확인하세요."
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = HighContrastYellow,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "주의: 본 추정은 보조 수단이며 안전을 보장하지 않습니다.",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = HighContrastYellow,
                        fontSize = 14.sp
                    )
                )
            }

            // B. 저시력자를 위한 선택적 Preview (신호등 조준 가이드 박스 및 실시간 뷰파인더 오버레이)
            if (uiState.hasCameraPermission && uiState.isCameraBound) {
                val previewBorderColor = when {
                    uiState.isSignalInReticle && uiState.detectedSignalColor == kr.safecross.mobile.perception.ObservedSignalState.RED -> Color(0xFFFF1744)
                    uiState.isSignalInReticle -> Color(0xFF00E676)
                    uiState.detectedSignalColor == kr.safecross.mobile.perception.ObservedSignalState.RED -> Color(0xFFFF1744)
                    uiState.detectedSignalColor == kr.safecross.mobile.perception.ObservedSignalState.GREEN -> Color(0xFF00E676)
                    uiState.tiltGuidance.isSuitable -> Color(0xFF00E5FF)
                    else -> HighContrastYellow
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(Color(0xFF212121), RoundedCornerShape(12.dp))
                        .border(2.5.dp, previewBorderColor, RoundedCornerShape(12.dp))
                        .semantics {
                            contentDescription = if (uiState.isSignalInReticle) {
                                "카메라 프리뷰 화면: 신호등이 가이드 박스 안에 정확히 조준되었습니다."
                            } else if (uiState.tiltGuidance.isSuitable) {
                                "카메라 프리뷰 화면: 각도가 적합합니다. 화면 가운데 가이드 박스 안에 신호등을 맞춰주세요."
                            } else {
                                "카메라 프리뷰 화면: ${uiState.tiltGuidance.instruction}"
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                viewModel.cameraPipeManager.bind(
                                    lifecycleOwner = lifecycleOwner,
                                    previewView = this,
                                    onFrame = { frame -> viewModel.processFrame(frame) }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 1. 신호등 조준 가이드 박스(뷰파인더 프레임) 및 각도 피드백 오버레이
                    TrafficLightReticleOverlay(
                        reticleBox = uiState.reticleBox,
                        tiltGuidance = uiState.tiltGuidance,
                        isSignalLocked = uiState.isSignalInReticle,
                        detectedSignalColor = uiState.detectedSignalColor
                    )

                    // 2. 신호등 검출 위치 바운딩 박스 오버레이
                    val box = uiState.detectedSignalBox
                    val signalColor = uiState.detectedSignalColor
                    if (box != null && (signalColor == kr.safecross.mobile.perception.ObservedSignalState.RED || signalColor == kr.safecross.mobile.perception.ObservedSignalState.GREEN)) {
                        val color = if (signalColor == kr.safecross.mobile.perception.ObservedSignalState.RED) Color(0xFFFF1744) else Color(0xFF00E676)
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val rectLeft = box.left * w
                            val rectTop = box.top * h
                            val rectWidth = ((box.right - box.left) * w).coerceAtLeast(20f)
                            val rectHeight = ((box.bottom - box.top) * h).coerceAtLeast(20f)

                            drawRect(
                                color = color,
                                topLeft = androidx.compose.ui.geometry.Offset(rectLeft, rectTop),
                                size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                            )
                        }
                    }

                    // 3. 실시간 신호등 감지 상태 플로팅 뱃지 (우측 상단)
                    if (uiState.detectedSignalColor != null && uiState.detectedSignalColor != kr.safecross.mobile.perception.ObservedSignalState.UNKNOWN) {
                        val isRed = uiState.detectedSignalColor == kr.safecross.mobile.perception.ObservedSignalState.RED
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(Color(0xEE1A1A1A), RoundedCornerShape(8.dp))
                                .border(1.5.dp, if (isRed) Color(0xFFFF1744) else Color(0xFF00E676), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isRed) "🔴 적색 감지" else "🟢 녹색 감지",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isRed) Color(0xFFFF5252) else Color(0xFF00E676),
                                    fontSize = 13.sp
                                )
                            )
                        }
                    }
                }
            }

            // C. 기기 기울기 및 방향 가이던스 카드 (SR-F-070, TRD 4.6)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .border(
                        1.dp,
                        if (uiState.tiltGuidance.isSuitable) HighContrastYellow else Color(0xFFFF9800),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "카메라 방향 안내: ${uiState.tiltGuidance.instruction}"
                        liveRegion = LiveRegionMode.Polite
                    }
            ) {
                Text(
                    text = "카메라 각도 안내",
                    style = MaterialTheme.typography.titleMedium,
                    color = HighContrastYellow
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.tiltGuidance.instruction,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = HighContrastWhite,
                        fontSize = 20.sp
                    )
                )
            }

            // D. 신호 추정 상태 뱃지 (텍스트 + 아이콘 + 색상 다중화)
            DecisionStateBadge(
                decisionState = uiState.decisionState,
                statusMessage = uiState.statusMessage,
                crosswalkDetected = uiState.crosswalkDetected
            )

            // E. 횡단보도 탐색 상태 카드
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF666666), RoundedCornerShape(12.dp))
                    .padding(14.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (uiState.crosswalkDetected) "횡단보도가 카메라 시야 내에 감지되었습니다." else "횡단보도를 탐색 중입니다."
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (uiState.crosswalkDetected) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (uiState.crosswalkDetected) Color(0xFF00E676) else Color(0xFFBDBDBD),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (uiState.crosswalkDetected) "횡단보도 형상 감지됨" else "횡단보도 탐색 중",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        color = HighContrastWhite
                    )
                )
            }

            // [실시간 인지 진단 HUD]
            if (uiState.debugDiagnosticText != null) {
                Surface(
                    color = Color(0xDD000000),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "🛠️ 실시간 인지 진단 HUD",
                                color = Color(0xFF81C784),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.debugDiagnosticText ?: "",
                            color = Color(0xFFE0E0E0),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // G. 진단 로그 공유 버튼
            Button(
                onClick = {
                    val logs = kr.safecross.mobile.perception.PerceptionFlightRecorder.readRecentLogs(context, 150)
                    val sendIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, logs)
                        type = "text/plain"
                    }
                    val shareIntent = android.content.Intent.createChooser(sendIntent, "신호 인식 진단 로그 공유")
                    context.startActivity(shareIntent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
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
                    text = "신호 인식 진단 로그 공유/저장",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF81D4FA)
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // F. 대형 "횡단 보조 즉시 종료" 버튼 (최소 64dp, Red, SR-F-049)
            Button(
                onClick = { viewModel.stopAssistance() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "카메라 횡단 보조를 즉시 종료하고 내비게이션으로 돌아갑니다."
                    },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = HighContrastWhite
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "횡단 보조 즉시 종료",
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
 * 횡단 보조 상태 뱃지
 */
@Composable
fun DecisionStateBadge(
    decisionState: CrossingAssistDecisionState,
    statusMessage: String? = null,
    crosswalkDetected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (icon, color, stateTitle) = when (decisionState) {
        CrossingAssistDecisionState.IDLE -> Triple(Icons.Default.PlayArrow, Color(0xFF757575), "대기 중")
        CrossingAssistDecisionState.APPROACH -> Triple(Icons.Default.PlayArrow, HighContrastYellow, "횡단보도 접근 중")
        CrossingAssistDecisionState.STOP_REQUIRED -> Triple(Icons.Default.Close, Color(0xFFFF5252), "정지 준비 (멈춤)")
        CrossingAssistDecisionState.SCANNING -> Triple(Icons.Default.PlayArrow, HighContrastYellow, "신호 탐색 중")
        CrossingAssistDecisionState.RED_ESTIMATE -> Triple(Icons.Default.Close, Color(0xFFFF5252), "🔴 적색 정지 신호 (보행 멈춤)")
        CrossingAssistDecisionState.GREEN_CANDIDATE -> Triple(Icons.Default.Info, Color(0xFFFFD54F), "녹색 신호 분석 중")
        CrossingAssistDecisionState.GREEN_ESTIMATE -> Triple(Icons.Default.CheckCircle, Color(0xFF00E676), "🟢 보행 신호 (녹색 감지됨)")
        CrossingAssistDecisionState.UNKNOWN -> {
            if (crosswalkDetected) {
                Triple(Icons.Default.Warning, Color(0xFFFF9800), "신호 미인식 (무신호 주의)")
            } else {
                Triple(Icons.Default.Warning, Color(0xFFFF9800), "신호 직접 확인 요망")
            }
        }
    }

    val finalDescription = statusMessage ?: decisionState.description

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(16.dp))
            .border(2.dp, color, RoundedCornerShape(16.dp))
            .padding(20.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "현재 판정 상태: $stateTitle. $finalDescription"
                liveRegion = LiveRegionMode.Assertive
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stateTitle,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                color = color,
                fontSize = 24.sp
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = finalDescription,
            style = MaterialTheme.typography.bodyLarge.copy(
                color = HighContrastWhite,
                fontSize = 16.sp
            )
        )
    }
}

/**
 * 카메라 프리뷰 상단 신호등 조준 뷰파인더 가이드 박스(Target Reticle Frame) 및 실시간 각도 안내 오버레이
 */
@Composable
fun TrafficLightReticleOverlay(
    reticleBox: kr.safecross.mobile.perception.NormalizedBox,
    tiltGuidance: kr.safecross.mobile.sensor.TiltGuidance,
    isSignalLocked: Boolean,
    detectedSignalColor: kr.safecross.mobile.perception.ObservedSignalState?,
    modifier: Modifier = Modifier
) {
    val reticleColor = when {
        isSignalLocked && detectedSignalColor == kr.safecross.mobile.perception.ObservedSignalState.RED -> Color(0xFFFF1744)
        isSignalLocked -> Color(0xFF00E676)
        tiltGuidance.isSuitable -> Color(0xFF00E5FF)
        else -> HighContrastYellow
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. 뷰파인더 4모서리 코너 브래킷 및 중앙 타깃 십자선
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val left = reticleBox.left * w
            val top = reticleBox.top * h
            val right = reticleBox.right * w
            val bottom = reticleBox.bottom * h
            val reticleWidth = right - left
            val reticleHeight = bottom - top

            val strokeWidth = if (isSignalLocked) 7f else 4.5f
            val cornerLen = (reticleWidth * 0.20f).coerceIn(24f, 48f)

            // 은은한 가이드 박스 윤곽선
            drawRect(
                color = reticleColor.copy(alpha = if (isSignalLocked) 0.45f else 0.25f),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(reticleWidth, reticleHeight),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
            )

            // 좌상단 L 브래킷
            drawLine(reticleColor, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Offset(left + cornerLen, top), strokeWidth)
            drawLine(reticleColor, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Offset(left, top + cornerLen), strokeWidth)

            // 우상단 L 브래킷
            drawLine(reticleColor, androidx.compose.ui.geometry.Offset(right, top), androidx.compose.ui.geometry.Offset(right - cornerLen, top), strokeWidth)
            drawLine(reticleColor, androidx.compose.ui.geometry.Offset(right, top), androidx.compose.ui.geometry.Offset(right, top + cornerLen), strokeWidth)

            // 좌하단 L 브래킷
            drawLine(reticleColor, androidx.compose.ui.geometry.Offset(left, bottom), androidx.compose.ui.geometry.Offset(left + cornerLen, bottom), strokeWidth)
            drawLine(reticleColor, androidx.compose.ui.geometry.Offset(left, bottom), androidx.compose.ui.geometry.Offset(left, bottom - cornerLen), strokeWidth)

            // 우하단 L 브래킷
            drawLine(reticleColor, androidx.compose.ui.geometry.Offset(right, bottom), androidx.compose.ui.geometry.Offset(right - cornerLen, bottom), strokeWidth)
            drawLine(reticleColor, androidx.compose.ui.geometry.Offset(right, bottom), androidx.compose.ui.geometry.Offset(right, bottom - cornerLen), strokeWidth)

            // 중앙 타깃 십자 표식
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            val crosshairLen = 12f
            drawLine(reticleColor.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(cx - crosshairLen, cy), androidx.compose.ui.geometry.Offset(cx + crosshairLen, cy), 2.5f)
            drawLine(reticleColor.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(cx, cy - crosshairLen), androidx.compose.ui.geometry.Offset(cx, cy + crosshairLen), 2.5f)
            drawCircle(reticleColor, radius = 3.5f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        }

        // 2. 가이드 상단/하단 직관적 안내 뱃지
        when {
            isSignalLocked -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .background(Color(0xEE000000), RoundedCornerShape(8.dp))
                        .border(1.5.dp, reticleColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "🎯 신호등 조준 완료 (락온)",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = reticleColor,
                            fontSize = 13.sp
                        )
                    )
                }
            }
            tiltGuidance == kr.safecross.mobile.sensor.TiltGuidance.TILT_UP -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .background(Color(0xEE000000), RoundedCornerShape(8.dp))
                        .border(1.5.dp, HighContrastYellow, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "▲ 카메라를 올려주세요 (정면 조준)",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = HighContrastYellow,
                            fontSize = 13.sp
                        )
                    )
                }
            }
            tiltGuidance == kr.safecross.mobile.sensor.TiltGuidance.TILT_DOWN -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .background(Color(0xEE000000), RoundedCornerShape(8.dp))
                        .border(1.5.dp, HighContrastYellow, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "▼ 카메라를 내려주세요 (정면 조준)",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = HighContrastYellow,
                            fontSize = 13.sp
                        )
                    )
                }
            }
            tiltGuidance == kr.safecross.mobile.sensor.TiltGuidance.LEVEL_PHONE -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .background(Color(0xEE000000), RoundedCornerShape(8.dp))
                        .border(1.5.dp, HighContrastYellow, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "↔ 스마트폰을 수평으로 세워주세요",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = HighContrastYellow,
                            fontSize = 13.sp
                        )
                    )
                }
            }
            tiltGuidance.isSuitable -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .background(Color(0xEE000000), RoundedCornerShape(8.dp))
                        .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "✔ 각도 적합 · 신호등을 네모 안에 맞추세요",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF),
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }
    }
}

