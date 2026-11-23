package com.ashe.cinemabox.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ashe.cinemabox.telegram.AuthState
import com.ashe.cinemabox.ui.theme.Brand
import com.ashe.cinemabox.ui.theme.Cine
import com.ashe.cinemabox.ui.theme.CineButton
import com.ashe.cinemabox.ui.theme.KeyButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Title-safe area of the design: 96×54 px at 1920×1080. */
@Composable
private fun SafeScreen(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Cine.Background).padding(horizontal = 48.dp, vertical = 27.dp),
        content = content,
    )
}

@Composable
fun QrLogin(state: AuthState.Qr) {
    val qr = remember(state.link) { qrBitmap(state.link) }
    SafeScreen {
        Brand()
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(70.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(
                    bitmap = qr,
                    contentDescription = "Código QR para vincular tu cuenta",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .size(280.dp)
                        .background(Cine.Text, RoundedCornerShape(14.dp))
                        .padding(22.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).background(Cine.Accent, CircleShape))
                    Text("El código se renueva solo", fontSize = 14.sp, color = Cine.TextMuted)
                }
                Text(UNOFFICIAL_NOTICE, fontSize = 11.sp, color = Cine.TextFaint, textAlign = TextAlign.Center,
                    lineHeight = 15.sp, modifier = Modifier.width(280.dp))
            }
            Column(Modifier.width(390.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Conecta tu cuenta", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp, lineHeight = 34.sp)
                    Text("Escanea el código con tu celular para ver tus videos en la tele.",
                        fontSize = 15.sp, color = Cine.TextMuted, lineHeight = 20.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Step(1, "Abre Telegram en tu celular")
                    Step(2, "Ve a Ajustes → Dispositivos")
                    Step(3, "Toca Vincular dispositivo y apunta a este código")
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(32.dp).background(Cine.Raised, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
    }
}

private val letterRows = listOf("1234567890", "qwertyuiop", "asdfghjklñ", "zxcvbnm.-_")
private val symbolRows = listOf("1234567890", "!@#$%^&*()", "~`+=[]{}\\|", ";:'\"<>?/,¿")

@Composable
fun PasswordLogin(state: AuthState.Password, onSubmit: (String) -> Unit, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstKey.requestFocus() }
    BackHandler(onBack = onBack)
    val submit = { if (!state.checking) onSubmit(password) }

    SafeScreen {
        Brand()
        Text("Atrás para volver al código QR", fontSize = 12.sp, color = Cine.TextMuted,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp))
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Verificación en dos pasos", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
                Text("Tu cuenta tiene una contraseña adicional. Escríbela para continuar.",
                    fontSize = 14.sp, color = Cine.TextMuted)
            }
            Column(Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp)
                        .background(Cine.Field, RoundedCornerShape(8.dp))
                        .border(1.dp, Cine.Border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (password.isEmpty()) {
                        Box(Modifier.width(2.dp).height(24.dp).background(Cine.Accent, RoundedCornerShape(1.dp)))
                        Text("Contraseña", fontSize = 18.sp, color = Cine.TextFaint)
                    } else {
                        Text(
                            if (visible) password else "•".repeat(password.length),
                            fontSize = if (visible) 20.sp else 22.sp,
                            letterSpacing = if (visible) 0.sp else 5.sp,
                            maxLines = 1,
                        )
                        Box(Modifier.width(2.dp).height(24.dp).background(Cine.Accent, RoundedCornerShape(1.dp)))
                    }
                }
                when {
                    state.error != null -> Text(state.error, fontSize = 12.sp, color = Cine.Error)
                    state.hint.isNotBlank() -> Text("Pista: ${state.hint}", fontSize = 12.sp, color = Cine.TextMuted)
                }
            }
            Column(Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (if (symbols) symbolRows else letterRows).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { i, char ->
                            val label = if (shift) char.uppercase() else char.toString()
                            val keyModifier = if (rowIndex == 1 && i == 0) Modifier.focusRequester(firstKey) else Modifier
                            KeyButton(label, { password += label }, keyModifier.weight(1f))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyButton(if (shift) "MAYÚS" else "Mayús", { shift = !shift }, Modifier.weight(2f))
                    KeyButton(if (symbols) "abc" else "#+=", { symbols = !symbols }, Modifier.weight(2f))
                    KeyButton("Espacio", { password += " " }, Modifier.weight(2f))
                    KeyButton("Borrar", { password = password.dropLast(1) }, Modifier.weight(2f))
                    KeyButton(if (visible) "Ocultar" else "Mostrar", { visible = !visible }, Modifier.weight(2f))
                    KeyButton(if (state.checking) "Comprobando…" else "Continuar", submit, Modifier.weight(2f))
                }
            }
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    SafeScreen {
        Brand()
        Text(message, fontSize = 16.sp, color = Cine.TextMuted, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(message) { focus.requestFocus() }
    SafeScreen {
        Brand()
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 620.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("No pudimos conectar", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(message, fontSize = 14.sp, color = Cine.TextMuted, textAlign = TextAlign.Center, lineHeight = 19.sp)
            Spacer(Modifier.height(7.dp))
            CineButton("Reintentar", onRetry, Modifier.focusRequester(focus))
        }
    }
}

internal fun qrBitmap(content: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val dark = 0xFF111114.toInt()
    val light = 0xFFF3F2EF.toInt()
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) dark else light
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
