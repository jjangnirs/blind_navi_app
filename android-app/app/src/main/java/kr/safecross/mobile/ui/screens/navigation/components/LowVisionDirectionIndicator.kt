package kr.safecross.mobile.ui.screens.navigation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.safecross.mobile.domain.model.DirectionAction
import kr.safecross.mobile.domain.model.Maneuver
import kr.safecross.mobile.ui.theme.HighContrastBlack
import kr.safecross.mobile.ui.theme.HighContrastWhite
import kr.safecross.mobile.ui.theme.HighContrastYellow

/**
 * 저시력 시각장애인을 위한 초고대비 대형 방향 안내 표시기 (TRD 4.8, SR-F-070 준수).
 *
 * - 4dp 두께의 고대비 노란 테두리와 순수 흑색 배경(#000000)
 * - 80dp 크기의 직관적인 초대형 방향 화살표 심볼
 * - 36sp ExtraBold 남은 거리 및 22sp 동작 명칭
 * - 스크린리더(TalkBack) 병행 접근성 제공
 */
@Composable
fun LowVisionDirectionIndicator(
    action: DirectionAction,
    distanceMeters: Int,
    currentManeuver: Maneuver?,
    modifier: Modifier = Modifier
) {
    val descriptionText = "저시력 방향 안내. ${action.label}, 남은 거리 ${distanceMeters}미터. ${currentManeuver?.instruction ?: ""}"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(HighContrastBlack, RoundedCornerShape(20.dp))
            .border(4.dp, HighContrastYellow, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = descriptionText
            }
    ) {
        // 상단: '저시력 방향 표시기' 접근성 라벨 헤더
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(HighContrastYellow, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "저시력 방향 가이드",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = HighContrastBlack,
                        fontSize = 13.sp
                    )
                )
            }

            // 기호 심볼 보조
            Text(
                text = action.symbolChar,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 24.sp,
                    color = HighContrastYellow
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 메인 영역: 좌측 대형 화살표 원형 배지 + 우측 대형 거리 및 동작
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. 80dp 초대형 고대비 방향 화살표 심볼
            LargeDirectionSymbol(action = action)

            Spacer(modifier = Modifier.width(18.dp))

            // 2. 우측 텍스트 (남은 거리 + 동작명)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // 남은 거리 (36sp ExtraBold)
                val distanceText = if (distanceMeters > 0) {
                    "${distanceMeters}m"
                } else {
                    "지금 바로"
                }
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black,
                        color = HighContrastYellow,
                        lineHeight = 42.sp
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 방향 동작 명칭 (22sp ExtraBold)
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = HighContrastWhite
                    )
                )
            }
        }

        // 하단 지시 문구 (선명한 대비)
        if (!currentManeuver?.instruction.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF424242), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = currentManeuver?.instruction ?: "",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HighContrastWhite
                    )
                )
            }
        }
    }
}

/**
 * 80dp 초대형 고대비 방향 심볼
 */
@Composable
private fun LargeDirectionSymbol(
    action: DirectionAction,
    modifier: Modifier = Modifier
) {
    val (icon, rotation, bgColor, iconColor) = when (action) {
        DirectionAction.STRAIGHT -> Quad(Icons.Default.KeyboardArrowUp, 0f, HighContrastYellow, HighContrastBlack)
        DirectionAction.LEFT -> Quad(Icons.AutoMirrored.Filled.ArrowBack, 0f, HighContrastYellow, HighContrastBlack)
        DirectionAction.RIGHT -> Quad(Icons.AutoMirrored.Filled.ArrowForward, 0f, HighContrastYellow, HighContrastBlack)
        DirectionAction.SLIGHT_LEFT -> Quad(Icons.Default.KeyboardArrowUp, -45f, HighContrastYellow, HighContrastBlack)
        DirectionAction.SLIGHT_RIGHT -> Quad(Icons.Default.KeyboardArrowUp, 45f, HighContrastYellow, HighContrastBlack)
        DirectionAction.CROSSWALK -> Quad(Icons.Default.Warning, 0f, Color(0xFFFF5252), HighContrastWhite)
        DirectionAction.UTURN -> Quad(Icons.Default.Refresh, 180f, HighContrastYellow, HighContrastBlack)
        DirectionAction.DESTINATION -> Quad(Icons.Default.Place, 0f, Color(0xFF00E676), HighContrastBlack)
        else -> Quad(Icons.Default.KeyboardArrowUp, 0f, HighContrastYellow, HighContrastBlack)
    }

    Box(
        modifier = modifier
            .size(84.dp)
            .background(bgColor, CircleShape)
            .border(3.dp, HighContrastWhite, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier
                .size(56.dp)
                .rotate(rotation)
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
