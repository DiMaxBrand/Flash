package com.dimax.flash.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dimax.flash.R
import com.dimax.flash.ui.hints.CameraScanIllustration
import com.dimax.flash.ui.hints.NfcChipIllustration
import com.dimax.flash.ui.hints.TapPhonesIllustration
import com.dimax.flash.ui.theme.OceanAqua
import com.dimax.flash.ui.theme.ThemeRepository
import kotlinx.coroutines.launch

private const val PAGES = 4

/**
 * The welcome: four short steps (what Flash is, touching phones, the camera instead, and hints for people who
 * are not comfortable with phones). The details live in the hints, so this stays short. It is shown once, to
 * everybody (see ThemeRepository.welcomeShown).
 */
@Composable
fun OnboardingScreen(
    themeRepository: ThemeRepository,
    onComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { PAGES })
    var hintsOn by remember { mutableStateOf(false) }
    val last = pagerState.currentPage == PAGES - 1

    fun finish() {
        scope.launch {
            if (hintsOn) themeRepository.setShowHints(true)
            themeRepository.setWelcomeShown()
            onComplete()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            WelcomePage(page = page, hintsOn = hintsOn, onHintsChange = { hintsOn = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(PAGES) { index ->
                val current = index == pagerState.currentPage
                Box(
                    Modifier
                        .size(if (current) 10.dp else 8.dp)
                        .background(
                            if (current) OceanAqua else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f),
                            CircleShape
                        )
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (last) Spacer(Modifier.size(1.dp)) else {
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.welcome_skip)) }
            }
            Button(
                onClick = {
                    if (last) finish() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = OceanAqua),
                modifier = Modifier.height(52.dp)
            ) {
                Text(
                    text = stringResource(if (last) R.string.welcome_start else R.string.welcome_next),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF003333)
                )
            }
        }
    }
}

@Composable
private fun WelcomePage(page: Int, hintsOn: Boolean, onHintsChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        when (page) {
            0 -> {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displayMedium,
                    color = OceanAqua
                )
                TapPhonesIllustration()
            }
            1 -> TapPhonesIllustration()
            2 -> CameraScanIllustration()
            else -> NfcChipIllustration()
        }
        Text(
            text = stringResource(
                when (page) {
                    0 -> R.string.welcome_step1_title
                    1 -> R.string.welcome_step2_title
                    2 -> R.string.welcome_step3_title
                    else -> R.string.welcome_step4_title
                }
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(
                when (page) {
                    0 -> R.string.welcome_step1_text
                    1 -> R.string.welcome_step2_text
                    2 -> R.string.welcome_step3_text
                    else -> R.string.welcome_step4_text
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            textAlign = TextAlign.Center
        )
        if (page == PAGES - 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.welcome_hints_switch),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.size(12.dp))
                Switch(checked = hintsOn, onCheckedChange = onHintsChange)
            }
        }
    }
}
