package uk.co.fuelprices.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.fuelprices.R

// DM Mono — the web app's monospace brand font (fueltracker.uk), bundled at the two weights the
// web loads: Regular (400) and Medium (500).
val DmMono = FontFamily(
    Font(R.font.dm_mono_regular, FontWeight.Normal),
    Font(R.font.dm_mono_medium, FontWeight.Medium),
)

// The web nav's accent green (#22C55E) — also the app's canonical E10 green.
private val BrandGreen = Color(0xFF22C55E)

/**
 * The "fuel tracker.uk" wordmark used as the top-bar title, mirroring the web nav mark: the logo
 * on the left, "fuel" in medium-weight brand green, "tracker.uk" in the theme's muted grey — all
 * in DM Mono. The 8dp gaps match the web nav's flex `gap: 8px` (so there is no literal space in
 * the text; the spacing is the layout gap, exactly as on the web).
 */
@Composable
fun BrandTitle(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand_logo),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
        Text(
            "fuel",
            color = BrandGreen,
            fontFamily = DmMono,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
            letterSpacing = (-0.4).sp, // web: -0.02em at this size
        )
        Text(
            "tracker.uk",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = DmMono,
            fontWeight = FontWeight.Normal,
            fontSize = 20.sp,
            letterSpacing = (-0.4).sp,
        )
    }
}
