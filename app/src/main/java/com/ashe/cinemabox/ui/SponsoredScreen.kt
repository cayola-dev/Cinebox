package com.ashe.cinemabox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ashe.cinemabox.library.LibraryRepository
import com.ashe.cinemabox.library.ReportOutcome
import com.ashe.cinemabox.library.SponsoredAd
import com.ashe.cinemabox.telegram.TdException
import com.ashe.cinemabox.telegram.tdErrorMessage
import com.ashe.cinemabox.ui.theme.Brand
import com.ashe.cinemabox.ui.theme.Cine
import com.ashe.cinemabox.ui.theme.CineButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface ReportState {
    data object Idle : ReportState
    data object Sending : ReportState
    data class Choose(val outcome: ReportOutcome.ChooseOption) : ReportState
    data class Done(val message: String) : ReportState
}

/** The TV has no reliable browser, so the sponsor's link is handed to the phone as a QR code. */
@Composable
fun SponsoredScreen(ad: SponsoredAd, library: LibraryRepository, onBack: () -> Unit) {
    val qr = remember(ad.url) { ad.url.takeIf { it.isNotBlank() }?.let(::qrBitmap) }
    val isTelegramLink = remember(ad.url) {
        val url = ad.url.lowercase()
        url.startsWith("tg:") || Regex("^https?://(www\\.)?(t\\.me|telegram\\.me)/").containsMatchIn(url)
    }
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<ReportState>(ReportState.Idle) }
    val firstAction = remember { FocusRequester() }
    LaunchedEffect(report) { runCatching { firstAction.requestFocus() } }

    fun sendReport(optionId: ByteArray) {
        report = ReportState.Sending
        scope.launch {
            report = try {
                when (val outcome = library.reportSponsored(ad, optionId)) {
                    ReportOutcome.Done -> ReportState.Done("Gracias. El anuncio fue reportado.")
                    is ReportOutcome.ChooseOption -> ReportState.Choose(outcome)
                    is ReportOutcome.Message -> ReportState.Done(outcome.text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReportState.Done(if (e is TdException) tdErrorMessage(e.code, e.message) else "No se pudo enviar el reporte.")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Cine.Background).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Brand()
        Text("Atrás para volver a Inicio", fontSize = 12.sp, color = Cine.TextMuted,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp))
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (qr != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(qr, "Código QR del enlace del anuncio", filterQuality = FilterQuality.None,
                        modifier = Modifier.size(220.dp).background(Cine.Text, RoundedCornerShape(12.dp)).padding(18.dp))
                    Text(ad.url, fontSize = 11.sp, color = Cine.TextMuted, maxLines = 2, modifier = Modifier.width(220.dp))
                }
            }
            Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ad.isRecommended) "Recomendado" else "Patrocinado", fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = Cine.Accent)
                Text(ad.title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 30.sp)
                if (ad.text.isNotBlank()) Text(ad.text, fontSize = 14.sp, color = Cine.TextSoft, lineHeight = 19.sp)
                if (ad.sponsorInfo.isNotBlank()) Text(ad.sponsorInfo, fontSize = 11.sp, color = Cine.TextMuted)
                if (ad.additionalInfo.isNotBlank()) Text(ad.additionalInfo, fontSize = 11.sp, color = Cine.TextMuted)
                Text(
                    if (isTelegramLink) "Escanea el código con tu celular para abrir este canal o bot en Telegram."
                    else "Escanea el código con tu celular para abrir el sitio web.",
                    fontSize = 13.sp, color = Cine.Text, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(6.dp))
                when (val state = report) {
                    ReportState.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CineButton("Volver", onBack, Modifier.focusRequester(firstAction))
                        if (ad.canBeReported) CineButton("Reportar anuncio", { sendReport(ByteArray(0)) })
                    }
                    ReportState.Sending -> Text("Enviando…", fontSize = 13.sp, color = Cine.TextMuted)
                    is ReportState.Choose -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.outcome.title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        state.outcome.options.forEachIndexed { index, option ->
                            CineButton(option.text, { sendReport(option.id) },
                                if (index == 0) Modifier.focusRequester(firstAction) else Modifier)
                        }
                        CineButton("Cancelar", { report = ReportState.Idle })
                    }
                    is ReportState.Done -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(state.message, fontSize = 13.sp, color = Cine.TextSoft)
                        CineButton("Volver", onBack, Modifier.focusRequester(firstAction))
                    }
                }
            }
        }
    }
}
