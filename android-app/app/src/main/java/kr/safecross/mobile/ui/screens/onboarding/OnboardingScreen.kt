package kr.safecross.mobile.ui.screens.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kr.safecross.mobile.ui.theme.CardBackground
import kr.safecross.mobile.ui.theme.HighContrastBlack
import kr.safecross.mobile.ui.theme.HighContrastWhite
import kr.safecross.mobile.ui.theme.HighContrastYellow
import kr.safecross.mobile.ui.theme.TextSecondary

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    voiceAnnouncer: VoiceAnnouncer?,
    onNavigateToDestination: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.onScreenStarted()
        viewModel.effects.collect { effect ->
            when (effect) {
                is OnboardingEffect.SpeakAnnouncement -> {
                    voiceAnnouncer?.speak(effect.text)
                }
                is OnboardingEffect.NavigateToDestination -> {
                    onNavigateToDestination()
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 앱 타이틀
            Text(
                text = "Safe Cross KR",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = HighContrastYellow
                ),
                modifier = Modifier.semantics {
                    contentDescription = "Safe Cross KR 시각장애인 보행 보조 내비게이션"
                }
            )

            // 안내 카드 (글꼴 200% 확대 시에도 줄임표 없이 스크롤되어 표시)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(16.dp))
                    .border(2.dp, Color(0xFF444444), RoundedCornerShape(16.dp))
                    .padding(20.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "앱 소개 안내. ${uiState.welcomeMessage}"
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "시각장애인 보행 보조 안내",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = HighContrastYellow
                    )
                )
                Text(
                    text = uiState.welcomeMessage,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 18.sp,
                        lineHeight = 28.sp,
                        color = HighContrastWhite
                    )
                )
            }

            // 주요 기능 요약
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBackground, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "주요 접근성 기능",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = HighContrastWhite
                    )
                )
                Text(
                    text = "• TalkBack 전용 스크린 리더 음성 최적화\n• 노란색/검은색 고대비 화면 기본 적용\n• 횡단보도 30m 접근 시 음향 및 진동 알림\n• 계단 제외 및 보행 위험 구역 면책 안내",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = TextSecondary
                    )
                )
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // 음성 다시 듣기 버튼 (최소 64dp)
            OutlinedButton(
                onClick = { viewModel.repeatSpeech() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "앱 시작 안내 음성을 다시 청취합니다."
                    },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, HighContrastYellow),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HighContrastYellow)
            ) {
                Text(
                    text = "안내 음성 다시 듣기",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                )
            }

            // 시작하기 버튼 (최소 64dp)
            Button(
                onClick = { viewModel.completeOnboarding() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Safe Cross KR 보행 보조를 시작하고 목적지 선택 화면으로 이동합니다."
                    },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HighContrastYellow,
                    contentColor = HighContrastBlack
                )
            ) {
                Text(
                    text = "Safe Cross KR 시작하기",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    )
                )
            }
        }
    }
}
