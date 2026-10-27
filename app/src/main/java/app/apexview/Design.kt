package app.apexview

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight

/**
 * The app's look, after Apple's TV app: black canvas, artwork carries the color, white type in a
 * few sizes, one easing curve. Separation comes from type and space, not lines and boxes.
 */
object Ink {
    val background = Color(0xFF000000)
    /** Raised surfaces: sheets, settings groups, cards without artwork. */
    val surface = Color(0xFF1C1C1E)
    val surfaceHigh = Color(0xFF2C2C2E)
    val primary = Color(0xFFFFFFFF)
    /** Apple's secondary label: white at 60%. */
    val secondary = Color(0x99EBEBF5)
    val tertiary = Color(0x4DEBEBF5)
    /** Only for LIVE and errors. */
    val live = Color(0xFFFF3B30)
}

/**
 * Apple's type ramp. Tracking is per size: large text tighter, small text slightly looser.
 */
object Type {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, color = Ink.primary)
    val title1 = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em, color = Ink.primary)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em, color = Ink.primary)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.005).em, color = Ink.primary)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, color = Ink.primary)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = Ink.secondary)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.005.em, color = Ink.secondary)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.01.em, color = Ink.secondary)
}

/** The one curve every transition uses: fast start, gentle settle. */
val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

object Motion {
    const val PRESS_MS = 120
    const val SCREEN_MS = 380
    const val FADE_MS = 220
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Ink.background,
            surface = Ink.surface,
            surfaceContainer = Ink.surface,
            surfaceContainerLow = Ink.surface,
            surfaceContainerHigh = Ink.surfaceHigh,
            primary = Ink.primary,
            onPrimary = Color.Black,
            onSurface = Ink.primary,
            onSurfaceVariant = Ink.secondary,
            secondaryContainer = Ink.surfaceHigh,
            onSecondaryContainer = Ink.primary,
        ),
        content = content,
    )
}

/**
 * Tappable with press feedback on touch-down: a slight shrink that eases back. No ripple,
 * which reads as Android-default rather than designed. With a TV remote (or a keyboard), the
 * focused one grows a little and gets a white outline in [focusShape], so it's clear where OK lands.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.97f,
    focusShape: Shape = FocusShape,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        when {
            pressed && enabled -> scaleTo
            focused -> FOCUS_SCALE
            else -> 1f
        },
        tween(Motion.PRESS_MS, easing = EaseOut),
        label = "press",
    )
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(Motion.PRESS_MS, easing = EaseOut), label = "focus")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawWithContent {
            drawContent()
            if (ring > 0f) {
                val width = FOCUS_RING.toPx()
                drawOutline(
                    focusShape.createOutline(size, layoutDirection, this),
                    Color.White,
                    alpha = ring,
                    style = Stroke(width),
                )
            }
        }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

private const val FOCUS_SCALE = 1.04f
private val FOCUS_RING = 3.dp
private val FocusShape = RoundedCornerShape(12.dp)

/** iOS-style slider: a round white thumb on a thin track, instead of Material's bar thumb. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    trackColor: Color = Color.White.copy(alpha = 0.25f),
) {
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(22.dp)
                    .shadow(3.dp, androidx.compose.foundation.shape.CircleShape)
                    .background(Color.White, androidx.compose.foundation.shape.CircleShape)
            )
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span == 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(4.dp).background(trackColor, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                )
            }
        },
    )
}
