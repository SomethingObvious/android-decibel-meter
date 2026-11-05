package com.somethingobvious.decibel.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.somethingobvious.decibel.R

/** The meter body is graphite in both themes, so only the LCD changes: reflective by day, backlit at night. */
@Immutable
data class Lcd(
    val panel: Color,
    val ink: Color,
    val dim: Color,
    val faint: Color,
    /** From 70 dB, loud enough to notice. */
    val caution: Color,
    /** From 85 dB, where hearing damage starts to add up. */
    val harm: Color,
    /** The recess the glass sits in, darker at night so the backlit panel stands out of it. */
    val bezel: Color,
)

/** The colour a level is drawn in. A band name always goes with it, so colour is never the only signal. */
fun Lcd.zone(db: Double?): Color =
    when {
        db == null -> ink
        db >= LIMIT_DB -> harm
        db >= CAUTION_DB -> caution
        else -> ink
    }

object Palette {
    val body = Color(0xFF24272B)
    val led = Color(0xFFFF4D3D)
    val bodyRaised = Color(0xFF2E3237)
    val key = Color(0xFF3A3F45)
    val keyEdge = Color(0xFF565D65)
    val keySkirt = Color(0xFF0E1012)
    val amberTop = Color(0xFFF7B744)
    val amberSkirt = Color(0xFF7A4A05)
    val keyText = Color(0xFFE7EBE4)
    val keyDim = Color(0xFF9AA199)
    val keyChoice = Color(0xFFBCC3BA)
    val amber = Color(0xFFF0A020)
    val onAmber = Color(0xFF1B1300)
    val amberDeep = Color(0xFF6E4700)

    val day =
        Lcd(
            panel = Color(0xFFC8D2BE),
            ink = Color(0xFF1A2016),
            dim = Color(0xFF4E5946),
            faint = Color(0x331A2016),
            caution = Color(0xFF6E4700),
            harm = Color(0xFFA11F17),
            bezel = Color(0xFF141619),
        )
    val night =
        Lcd(
            panel = Color(0xFF10150E),
            ink = Color(0xFFCFE3B5),
            dim = Color(0xFF8A9C78),
            faint = Color(0x33CFE3B5),
            caution = Color(0xFFF0A020),
            harm = Color(0xFFFF6B4A),
            bezel = Color(0xFF08090A),
        )
}

private fun variable(id: Int, weight: Int) =
    Font(id, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private const val REGULAR = 400
private const val MEDIUM = 500
private const val BOLD = 700
private const val EXTRA_BOLD = 800

val LcdFont =
    FontFamily(
        variable(id = R.font.chivo_mono, weight = REGULAR),
        variable(id = R.font.chivo_mono, weight = MEDIUM),
        variable(id = R.font.chivo_mono, weight = BOLD),
        variable(id = R.font.chivo_mono, weight = EXTRA_BOLD),
    )

val KeyFont =
    FontFamily(
        variable(id = R.font.chivo, weight = REGULAR),
        variable(id = R.font.chivo, weight = MEDIUM),
        variable(id = R.font.chivo, weight = BOLD),
    )

val LocalLcd = staticCompositionLocalOf { Palette.day }

@Composable
fun DecibelTheme(content: @Composable () -> Unit) {
    val scheme =
        darkColorScheme(
            primary = Palette.amber,
            onPrimary = Palette.onAmber,
            background = Palette.body,
            onBackground = Palette.keyText,
            surface = Palette.body,
            onSurface = Palette.keyText,
            onSurfaceVariant = Palette.keyDim,
            surfaceContainerLow = Palette.bodyRaised,
            surfaceContainer = Palette.bodyRaised,
            surfaceContainerHigh = Palette.key,
            surfaceContainerHighest = Palette.key,
            inverseSurface = Palette.keyText,
            inverseOnSurface = Palette.body,
            // The snackbar action sits on the light inverse surface, where bright amber would wash out.
            inversePrimary = Palette.amberDeep,
            secondary = Palette.amber,
            onSecondary = Palette.onAmber,
            secondaryContainer = Palette.keyDim,
            onSecondaryContainer = Palette.body,
            tertiary = Palette.amber,
            onTertiary = Palette.onAmber,
            primaryContainer = Palette.key,
            onPrimaryContainer = Palette.keyText,
            surfaceVariant = Palette.key,
            surfaceBright = Palette.key,
            surfaceDim = Palette.body,
            surfaceContainerLowest = Palette.body,
            outlineVariant = Palette.key,
            outline = Palette.keyDim,
        )
    CompositionLocalProvider(LocalLcd provides if (isSystemInDarkTheme()) Palette.night else Palette.day) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

private fun TextStyle.inChivo() = copy(fontFamily = KeyFont)

// Sheets, buttons, fields and snackbars set their text in Chivo like the rest of the body.
private val typography =
    Typography().run {
        Typography(
            displayLarge = displayLarge.inChivo(),
            displayMedium = displayMedium.inChivo(),
            displaySmall = displaySmall.inChivo(),
            headlineLarge = headlineLarge.inChivo(),
            headlineMedium = headlineMedium.inChivo(),
            headlineSmall = headlineSmall.inChivo(),
            titleLarge = titleLarge.inChivo(),
            titleMedium = titleMedium.inChivo(),
            titleSmall = titleSmall.inChivo(),
            bodyLarge = bodyLarge.inChivo(),
            bodyMedium = bodyMedium.inChivo(),
            bodySmall = bodySmall.inChivo(),
            labelLarge = labelLarge.inChivo(),
            labelMedium = labelMedium.inChivo(),
            labelSmall = labelSmall.inChivo(),
        )
    }
