package kr.safecross.mobile.ui.screens.settings

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
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import kr.safecross.mobile.domain.model.ROUTE_DISCLAIMER_TEXT
import kr.safecross.mobile.ui.theme.CardBackground
import kr.safecross.mobile.ui.theme.HighContrastBlack
import kr.safecross.mobile.ui.theme.HighContrastWhite
import kr.safecross.mobile.ui.theme.HighContrastYellow
import kr.safecross.mobile.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    voiceAnnouncer: VoiceAnnouncer?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.onScreenStarted()
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.SpeakAnnouncement -> {
                    voiceAnnouncer?.speak(effect.text)
                }
                is SettingsEffect.NavigateBack -> {
                    onNavigateBack()
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 상단 타이틀
            Text(
                text = "환경설정",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = HighContrastYellow
                )
            )

            // 1. 고대비 화면 모드 토글
            SettingSwitchCard(
                title = "고대비 모드",
                description = "노란색과 검은색의 최고 대비 색상을 화면에 적용합니다.",
                isChecked = uiState.isHighContrastEnabled,
                onCheckedChange = { viewModel.toggleHighContrast(it) }
            )

            // 2. TTS 음성 속도 선택 (드래그 슬라이더 배제! 라디오 버튼 그룹)
            SpeechRateSettingCard(
                selectedRate = uiState.speechRate,
                onSelectRate = { viewModel.setSpeechRate(it) }
            )

            // 3. 음향신호기 자동 감지 알림 토글
            SettingSwitchCard(
                title = "음향신호기 자동 알림",
                description = "횡단보도 30m 이내 접근 시 음향신호기 구비 여부를 안내합니다.",
                isChecked = uiState.isAcousticSignalAlertEnabled,
                onCheckedChange = { viewModel.toggleAcousticSignalAlert(it) }
            )

            // 4. 햅틱 진동 피드백 토글 및 세기 선택 (SR-F-073, TRD 4.8)
            SettingSwitchCard(
                title = "햅틱 진동 피드백",
                description = "신호 추정 상태 및 안전 경고를 진동 패턴으로 전달합니다.",
                isChecked = uiState.isVibrationEnabled,
                onCheckedChange = { viewModel.toggleVibration(it) }
            )

            if (uiState.isVibrationEnabled) {
                VibrationIntensitySettingCard(
                    selectedIntensity = uiState.vibrationIntensity,
                    onSelectIntensity = { viewModel.setVibrationIntensity(it) }
                )
            }

            // 5. 법적 고지 및 면책 전문 버튼 (최소 64dp)
            OutlinedButton(
                onClick = { viewModel.showDisclaimerDialog(true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "법적 고지 및 면책 조항 전문을 화면에 표시합니다."
                    },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, HighContrastYellow),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HighContrastYellow)
            ) {
                Text(
                    text = "법적 고지 및 면책 조항 보기",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // 5. 설정 저장 및 닫기 버튼 (최소 64dp)
            Button(
                onClick = { viewModel.saveAndClose() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "설정을 저장하고 이전 화면으로 돌아갑니다."
                    },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HighContrastYellow,
                    contentColor = HighContrastBlack
                )
            ) {
                Text(
                    text = "설정 저장 및 닫기",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    )
                )
            }
        }
    }

    // 법적 고지 다이얼로그
    if (uiState.showDisclaimerDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showDisclaimerDialog(false) },
            title = {
                Text(
                    text = "법적 고지 및 면책 조항",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = HighContrastYellow
                    )
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = ROUTE_DISCLAIMER_TEXT,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            color = HighContrastWhite
                        )
                    )
                    Text(
                        text = "버전: ${uiState.appVersion}\n공급자: SK Open API TMAP 보행자 안내 연계",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.showDisclaimerDialog(false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "면책 조항 대화상자를 닫습니다."
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HighContrastYellow,
                        contentColor = HighContrastBlack
                    )
                ) {
                    Text(
                        text = "닫기",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            },
            containerColor = CardBackground,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun SettingSwitchCard(
    title: String,
    description: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, Color(0xFF444444)), RoundedCornerShape(12.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                contentDescription = "$title 설정. $description 현재 상태: ${if (isChecked) "켜짐" else "꺼짐"}. 전환하려면 두 번 탭하세요."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = HighContrastWhite
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            )
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(56.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = HighContrastBlack,
                checkedTrackColor = HighContrastYellow,
                uncheckedThumbColor = HighContrastWhite,
                uncheckedTrackColor = Color(0xFF555555)
            )
        )
    }
}

@Composable
private fun SpeechRateSettingCard(
    selectedRate: Float,
    onSelectRate: (Float) -> Unit
) {
    val rates = listOf(
        0.8f to "느리게 (0.8배속)",
        1.0f to "보통 (1.0배속)",
        1.2f to "빠르게 (1.2배속)"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, Color(0xFF444444)), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "음성 안내 속도",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = HighContrastWhite
            )
        )
        Text(
            text = "원하는 낭독 속도 단계를 선택하세요.",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                color = TextSecondary
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        rates.forEach { (rate, label) ->
            val isSelected = selectedRate == rate
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelectRate(rate) }
                    )
                    .semantics {
                        contentDescription = "$label. ${if (isSelected) "선택됨" else "선택 안 됨"}."
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = HighContrastYellow,
                        unselectedColor = HighContrastWhite
                    )
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) HighContrastYellow else HighContrastWhite
                    ),
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun VibrationIntensitySettingCard(
    selectedIntensity: kr.safecross.mobile.accessibility.VibrationIntensity,
    onSelectIntensity: (kr.safecross.mobile.accessibility.VibrationIntensity) -> Unit
) {
    val intensities = listOf(
        kr.safecross.mobile.accessibility.VibrationIntensity.LOW,
        kr.safecross.mobile.accessibility.VibrationIntensity.MEDIUM,
        kr.safecross.mobile.accessibility.VibrationIntensity.HIGH
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, Color(0xFF444444)), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "진동 세기 선택",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = HighContrastYellow
            )
        )
        Text(
            text = "신호 및 안전 경고 알림 시 진동의 진폭을 조절합니다.",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = HighContrastWhite
            ),
            modifier = Modifier.padding(vertical = 6.dp)
        )

        intensities.forEach { intensity ->
            val isSelected = intensity == selectedIntensity
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelectIntensity(intensity) }
                    )
                    .semantics {
                        contentDescription = "${intensity.label} 진동 세기. ${if (isSelected) "선택됨" else "선택 안 됨"}."
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = HighContrastYellow,
                        unselectedColor = HighContrastWhite
                    )
                )
                Text(
                    text = intensity.label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) HighContrastYellow else HighContrastWhite
                    ),
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}
