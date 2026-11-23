package com.ashe.cinemabox.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import com.ashe.cinemabox.R

// Design is drawn at 1920×1080 px; TV renders 960×540 dp, so every design value is halved.
object Cine {
    val Background = Color(0xFF181826)
    val Field = Color(0xFF232338)
    val Key = Color(0xFF292940)
    val Raised = Color(0xFF3B3B58)
    val Border = Color(0xFF34343C)
    val Text = Color(0xFFF2F2F7)
    val TextSoft = Color(0xFFD6D5DA)
    val TextMuted = Color(0xFFA3A3BD)
    val TextFaint = Color(0xFF7E7D86)
    val OnLight = Color(0xFF111114)
    val Accent = Color(0xFF3FD07F)
    val Error = Color(0xFFFF8A7A)
}

@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    R.font.figtree,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Figtree = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700), figtree(800))
@OptIn(ExperimentalTextApi::class)
private fun variableFont(resource: Int, weight: Int) = Font(resource, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
val Manrope = FontFamily((listOf(400, 500, 600, 700, 800).map { variableFont(R.font.manrope, it) }))
val Urbanist = FontFamily((listOf(400, 500, 600, 700, 800).map { variableFont(R.font.urbanist, it) }))

@Composable
fun CineboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = Cine.Background, surface = Cine.Background)) {
        CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontFamily = Manrope, color = Cine.Text),
            content = content,
        )
    }
}

@Composable
fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(14.dp).background(Cine.Accent, RoundedCornerShape(4.dp)))
        Text("Cinebox", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp)
    }
}

/**
 * Focused elements grow 5% with a small overshoot and hand their bounds to the shared [FocusLens],
 * which draws the travelling accent frame. Without a lens host, a static accent border is used.
 */
@Composable
fun Modifier.focusRing(focused: Boolean, shape: Shape, outline: Boolean = true): Modifier {
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, tween(240, easing = Motion.Glide), label = "focusScale")
    val emphasis by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "focusEmphasis")
    val hasLens = LocalFocusLens.current != null
    val elevation = with(LocalDensity.current) { 10.dp.toPx() }
    val base = lensTarget(focused, shape).graphicsLayer {
        scaleX = scale
        scaleY = scale
        shadowElevation = elevation * emphasis
        this.shape = shape
    }
    return if (focused && !hasLens && outline) base.border(1.dp, Cine.Text.copy(alpha = .8f), shape) else base
}

@Composable
fun CineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable (Color) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .focusRing(focused, shape)
            .background(if (focused) Cine.Text else Cine.Raised, shape)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .height(36.dp)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        leading?.invoke(if (focused) Cine.OnLight else Cine.Text)
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
            color = if (focused) Cine.OnLight else Cine.Text.copy(alpha = if (enabled) 1f else 0.5f),
        )
    }
}

@Composable
fun KeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = if (label.length > 1) 13.sp else 15.sp,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .height(38.dp)
            .focusRing(focused, shape)
            .background(if (focused) Cine.Text else Cine.Key, shape)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = if (focused) Cine.OnLight else Cine.Text)
    }
}
