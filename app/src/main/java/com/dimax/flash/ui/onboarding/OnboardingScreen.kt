package com.dimax.flash.ui.onboarding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dimax.flash.R
import com.dimax.flash.ui.theme.OceanAqua
import com.dimax.flash.ui.theme.ThemeRepository
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun OnboardingScreen(
    themeRepository: ThemeRepository,
    onComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayMedium,
            color = OceanAqua
        )

        TwoPhonesAnimation()

        Text(
            text = stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                scope.launch {
                    themeRepository.setOnboardingShown()
                    onComplete()
                }
            },
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = OceanAqua),
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(52.dp)
        ) {
            Text(
                text = stringResource(R.string.onboarding_button),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF003333)
            )
        }
    }
}

@Composable
private fun TwoPhonesAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "phones")
    val offset by infiniteTransition.animateFloat(
        initialValue = 60f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phone_offset"
    )
    val tapPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tap_pulse"
    )

    Canvas(modifier = Modifier.size(240.dp, 160.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val phoneW = 44f
        val phoneH = 80f
        val cornerR = 10f
        val strokeW = 3f

        // Left phone
        drawPhoneOutline(
            center = Offset(cx - offset - phoneW / 2f, cy),
            width = phoneW, height = phoneH, cornerRadius = cornerR,
            strokeWidth = strokeW, color = OceanAqua
        )
        // Right phone (mirrored)
        drawPhoneOutline(
            center = Offset(cx + offset + phoneW / 2f, cy),
            width = phoneW, height = phoneH, cornerRadius = cornerR,
            strokeWidth = strokeW, color = OceanAqua
        )

        // NFC pulse rings between them when close
        if (offset < 20f) {
            val alpha = ((20f - offset) / 20f).coerceIn(0f, 1f)
            for (i in 0..2) {
                val r = 12f + i * 14f + sin(tapPulse + i * PI.toFloat() / 1.5f) * 4f
                drawCircle(
                    color = OceanAqua.copy(alpha = alpha * (0.6f - i * 0.15f)),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2f)
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPhoneOutline(
    center: Offset,
    width: Float,
    height: Float,
    cornerRadius: Float,
    strokeWidth: Float,
    color: Color
) {
    val left  = center.x - width / 2f
    val top   = center.y - height / 2f
    val right = center.x + width / 2f
    val bot   = center.y + height / 2f

    val path = Path().apply {
        moveTo(left + cornerRadius, top)
        lineTo(right - cornerRadius, top)
        quadraticTo(right, top, right, top + cornerRadius)
        lineTo(right, bot - cornerRadius)
        quadraticTo(right, bot, right - cornerRadius, bot)
        lineTo(left + cornerRadius, bot)
        quadraticTo(left, bot, left, bot - cornerRadius)
        lineTo(left, top + cornerRadius)
        quadraticTo(left, top, left + cornerRadius, top)
        close()
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidth))

    // Home button / chin line
    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(center.x - 8f, bot - 10f),
        end   = Offset(center.x + 8f, bot - 10f),
        strokeWidth = strokeWidth
    )
}
