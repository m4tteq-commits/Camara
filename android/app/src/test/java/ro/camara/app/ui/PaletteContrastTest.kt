package ro.camara.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    @Test fun readableTextAcrossLightAndDarkPalettes() {
        for (palette in CamaraPalette.entries) for (dark in listOf(false, true)) {
            val colors = paletteScheme(palette, dark)
            val roles = mapOf(
                "page" to (colors.onBackground to colors.background),
                "card" to (colors.onSurface to colors.surfaceContainerLowest),
                "muted" to (colors.onSurfaceVariant to colors.surfaceContainerLow),
                "button" to (colors.onPrimary to colors.primary),
                "highlight" to (colors.onPrimaryContainer to colors.primaryContainer),
                "secondary highlight" to (colors.onSecondaryContainer to colors.secondaryContainer),
            )
            for ((role, pair) in roles) {
                val luminances = listOf(pair.first.luminance(), pair.second.luminance())
                val contrast = (luminances.max() + .05f) / (luminances.min() + .05f)
                assertTrue("${palette.name} dark=$dark $role has contrast $contrast", contrast >= 4.5f)
            }
        }
    }
}
