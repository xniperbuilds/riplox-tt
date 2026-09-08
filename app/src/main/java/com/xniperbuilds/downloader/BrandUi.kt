package com.xniperbuilds.downloader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xniperbuilds.downloader.ui.theme.SpaceGrotesk

// ============================================================================
// Riplox brand UI — shared composables (home + popups sab yahi use karein)
// Steel Blue: #3E4E68 → #0A101B  ·  light steel text #DDE4EF / #9AA6B8
// ============================================================================

/** Brand tile (Rip-Arrow on steel gradient) — top bars / headers ke liye. */
@Composable
fun RiploxTile(size: Int = 40) {
    Image(
        painter = painterResource(R.drawable.ic_riplox_tile),
        contentDescription = null,
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
    )
}

/** Branded top bar: tile + Riplox wordmark (+ optional right action). */
@Composable
fun RiploxHeader(
    subtitle: String? = null,
    tileSize: Int = 40,
    rightIcon: String? = null,
    onRight: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RiploxTile(tileSize)
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column {
            Text(
                "Riplox TT",
                fontSize = if (subtitle == null) 27.sp else 21.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SpaceGrotesk,
                letterSpacing = (-1).sp,
                color = Color(0xFFDDE4EF)
            )
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = Color(0xFF9AA6B8))
            }
        }
        Spacer(Modifier.weight(1f))
        if (rightIcon != null && onRight != null) {
            Text(
                rightIcon,
                fontSize = 22.sp,
                color = Color(0xFF9AA6B8),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRight)
                    .padding(8.dp)
            )
        }
    }
}

/**
 * Steel-gradient primary CTA (brand ka main button).
 *
 * Its gradient follows the accent the user has unlocked and chosen (see [Accent]). On the stock
 * accent the colours are byte-for-byte the ones the app shipped with, so nobody who ignores the
 * streak feature sees any change at all. This button is the reason accents are worth offering:
 * it is on screen the whole time, unlike the streak card's own buttons.
 */
@Composable
fun GradientButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit
) {
    val accent = Accent.current(LocalContext.current)
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(accent.primary, accent.gradientEnd)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
    }
}
