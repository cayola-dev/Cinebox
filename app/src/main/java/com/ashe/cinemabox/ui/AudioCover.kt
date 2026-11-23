package com.ashe.cinemabox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

/** Offline, resolution-independent artwork. No bitmap allocation or perpetual animation per card. */
@Composable
fun AudioCover(seed: String, modifier: Modifier = Modifier) {
    Box(modifier.drawWithCache {
        val accents = listOf(Color(0xFF66D8BA), Color(0xFFAB9CF4), Color(0xFF7FBCEB), Color(0xFFDCA7C6))
        val accent = accents[(seed.hashCode() and Int.MAX_VALUE) % accents.size]
        val background = Brush.linearGradient(listOf(Color(0xFF292C46), Color(0xFF171B2D)), Offset.Zero, Offset(size.width, size.height))
        val glow = Brush.radialGradient(listOf(accent.copy(alpha = .22f), Color.Transparent),
            Offset(size.width * .3f, size.height * .25f), size.maxDimension * .8f)
        val center = Offset(size.width * .5f, size.height * .48f)
        val radius = size.minDimension * .35f
        val note = Path().apply {
            moveTo(center.x - radius * .05f, center.y + radius * .28f)
            lineTo(center.x - radius * .05f, center.y - radius * .42f)
            lineTo(center.x + radius * .42f, center.y - radius * .51f)
            lineTo(center.x + radius * .42f, center.y + radius * .13f)
            lineTo(center.x + radius * .30f, center.y + radius * .13f)
            lineTo(center.x + radius * .30f, center.y - radius * .25f)
            lineTo(center.x + radius * .07f, center.y - radius * .20f)
            lineTo(center.x + radius * .07f, center.y + radius * .28f)
            close()
        }
        onDrawBehind {
            drawRect(background)
            drawRect(glow)
            drawCircle(Color(0xFF121625), radius, center)
            for (i in 1..5) drawCircle(accent.copy(alpha = .08f), radius * (.51f + i * .085f), center, style = Stroke(radius * .012f))
            drawCircle(accent.copy(alpha = .12f), radius * .49f, center)
            drawPath(note, accent)
            drawOval(accent, Offset(center.x - radius * .23f, center.y + radius * .17f), androidx.compose.ui.geometry.Size(radius * .30f, radius * .20f))
            drawOval(accent, Offset(center.x + radius * .12f, center.y + radius * .03f), androidx.compose.ui.geometry.Size(radius * .30f, radius * .20f))
            // Quiet waveform accent, deliberately static: it does not pretend to visualize the audio.
            for (i in 0..16) {
                val height = size.height * (.025f + ((i * 7 + 3) % 9) * .003f)
                val x = size.width * (.34f + i * .02f)
                drawLine(accent.copy(alpha = .35f), Offset(x, size.height * .91f - height), Offset(x, size.height * .91f),
                    size.width * .004f, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    })
}
