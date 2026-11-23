package com.ashe.cinemabox.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ashe.cinemabox.BuildConfig
import com.ashe.cinemabox.library.Account
import com.ashe.cinemabox.library.Channel
import com.ashe.cinemabox.library.FileRepository
import com.ashe.cinemabox.library.LibraryRepository
import com.ashe.cinemabox.library.LibrarySettings
import com.ashe.cinemabox.library.formatSize
import com.ashe.cinemabox.music.MusicIdentifier
import com.ashe.cinemabox.telegram.TdException
import com.ashe.cinemabox.telegram.tdErrorMessage
import com.ashe.cinemabox.ui.theme.Cine
import com.ashe.cinemabox.ui.theme.CineButton
import com.ashe.cinemabox.ui.theme.Motion
import com.ashe.cinemabox.ui.theme.focusRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

const val UNOFFICIAL_NOTICE = "Cinebox es una app no oficial que usa la API de Telegram. No está afiliada a Telegram."

/** 07 · Ajustes. */
@Composable
fun SettingsScreen(
    library: LibraryRepository,
    files: FileRepository,
    settings: LibrarySettings,
    identifier: MusicIdentifier,
    account: Account?,
    onLogOut: () -> Unit,
) {
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(settings.channelIds) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            val current = account ?: library.account()
            settings.bindAccount(current.savedMessagesChatId)
            selected = settings.channelIds
            channels = library.channels()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (e is TdException) tdErrorMessage(e.code, e.message) else "No se pudieron cargar tus canales."
        }
    }

    Box(Modifier.fillMaxSize().background(Cine.Background).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("Ajustes", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
                modifier = Modifier.weight(1f).rise("settings", 0f))
            Text("Atrás para volver a Inicio", fontSize = 12.sp, color = Cine.TextMuted)
        }
        Row(Modifier.fillMaxSize().padding(top = 57.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(520.dp).rise("settings", 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Canales", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Cada canal aparece como una categoría en Inicio.", fontSize = 12.sp, color = Cine.TextMuted)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text("Canal", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Cine.TextMuted, modifier = Modifier.weight(1f))
                    Text("Mostrar en Cinebox", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Cine.TextMuted)
                }
                val list = channels
                when {
                    error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error!!, fontSize = 13.sp, color = Cine.Error)
                        CineButton("Reintentar", { attempt++ })
                    }
                    list == null -> Text("Cargando tus canales…", fontSize = 13.sp, color = Cine.TextMuted)
                    list.isEmpty() -> Text("No tienes canales. Únete o crea uno en tu celular.", fontSize = 13.sp, color = Cine.TextMuted)
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp),
                    ) {
                        itemsIndexed(list, key = { _, it -> it.chatId }) { index, channel ->
                            ChannelToggle(channel, channel.chatId in selected, focusFirst = index == 0) {
                                selected = if (channel.chatId in selected) selected - channel.chatId else selected + channel.chatId
                                settings.channelIds = selected
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().rise("settings", 2f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StorageCard(files)
                MusicCard(settings, identifier)
                AccountCard(account, onLogOut)
                AboutCard()
            }
        }
    }
}

@Composable
private fun ChannelToggle(channel: Channel, on: Boolean, focusFirst: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val requester = remember { FocusRequester() }
    if (focusFirst) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val shape = RoundedCornerShape(7.dp)
    val knob by animateFloatAsState(if (on) 1f else 0f, tween(300, easing = Motion.Pop), label = "knob")
    Row(
        Modifier.fillMaxWidth().height(44.dp)
            .focusRequester(requester)
            .focusRing(focused, shape)
            .background(if (focused) Cine.Raised else Color(0xFF16161A), shape)
            .clickable(interaction, indication = null, onClick = onToggle)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(channel.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (channel.isPublic) "Canal público" else "Canal privado") +
                    (if (channel.memberCount > 0) " · ${channel.memberCount} suscriptores" else ""),
                fontSize = 12.sp, color = Cine.TextMuted,
            )
        }
        Text(if (on) "Sí" else "No", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (on) Cine.Text else Cine.TextMuted, modifier = Modifier.width(20.dp))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.size(46.dp, 26.dp).background(if (on) Cine.Accent else Color(0xFF3A3A42), RoundedCornerShape(13.dp)).padding(3.dp)) {
            Box(Modifier.offset(x = 20.dp * knob).size(20.dp).background(if (on) Cine.OnLight else Cine.TextMuted, CircleShape))
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF16161A), RoundedCornerShape(10.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun StorageCard(files: FileRepository) {
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) { size = runCatching { files.cacheSize() }.getOrNull() }
    val limit = FileRepository.CACHE_LIMIT_BYTES
    val fraction by animateFloatAsState(((size ?: 0L).toFloat() / limit).coerceIn(0f, 1f), tween(600, easing = Motion.Glide), label = "cache")
    SettingsCard {
        Text("Almacenamiento", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(size?.let(::formatSize) ?: "—", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("de ${formatSize(limit)} en caché", fontSize = 12.sp, color = Cine.TextMuted, modifier = Modifier.padding(bottom = 5.dp))
        }
        Box(Modifier.fillMaxWidth().height(5.dp).background(Cine.Border, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color(0xFF9C9BA3), RoundedCornerShape(3.dp)))
        }
        Text("Videos descargados para verlos sin cortes. Se vuelven a bajar cuando los reproduces.",
            fontSize = 12.sp, color = Cine.TextMuted, lineHeight = 16.sp)
        CineButton(
            if (clearing) "Limpiando…" else "Limpiar caché" + (size?.takeIf { it > 0 }?.let { " · ${formatSize(it)}" } ?: ""),
            {
                if (!clearing) {
                    clearing = true
                    error = null
                    scope.launch {
                        try { files.clearCache() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { error = "No se pudo limpiar la caché. Inténtalo de nuevo." }
                        clearing = false
                        refresh++
                    }
                }
            },
        )
        error?.let { Text(it, fontSize = 12.sp, color = Cine.Error) }
    }
}

/** 6C: AcoustID identification switch and a reset for songs that were not recognised. */
@Composable
private fun MusicCard(settings: LibrarySettings, identifier: MusicIdentifier) {
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(settings.autoIdentify) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    SettingsCard {
        Text("Música", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        SwitchRow("Identificar canciones automáticamente", auto) {
            auto = !auto
            settings.autoIdentify = auto
        }
        Text("Al reproducir una canción por primera vez, Cinebox calcula su huella en la TV y la consulta en AcoustID " +
            "para mostrar título, artista, álbum y portada. Solo se envía la huella, nunca el audio.",
            fontSize = 12.sp, color = Cine.TextMuted, lineHeight = 16.sp)
        CineButton(if (working) "Borrando…" else "Volver a identificar", {
            if (!working) {
                working = true
                scope.launch {
                    message = try {
                        val count = identifier.forgetAttempts()
                        if (count == 0) "No había canciones pendientes." else "Se volverán a analizar $count canciones al reproducirlas."
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { "No se pudo borrar. Inténtalo de nuevo." }
                    working = false
                }
            }
        })
        message?.let { Text(it, fontSize = 12.sp, color = Cine.TextSoft) }
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(7.dp)
    val knob by animateFloatAsState(if (on) 1f else 0f, tween(300, easing = Motion.Pop), label = "switchKnob")
    Row(Modifier.fillMaxWidth().height(44.dp).focusRing(focused, shape).background(if (focused) Cine.Raised else Color(0xFF1E1E24), shape)
        .clickable(interaction, indication = null, onClick = onToggle).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(Modifier.size(46.dp, 26.dp).background(if (on) Cine.Accent else Color(0xFF3A3A42), RoundedCornerShape(13.dp)).padding(3.dp)) {
            Box(Modifier.offset(x = 20.dp * knob).size(20.dp).background(if (on) Cine.OnLight else Cine.TextMuted, CircleShape))
        }
    }
}

@Composable
private fun AccountCard(account: Account?, onLogOut: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    SettingsCard {
        Text("Cuenta", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).background(Color(0xFF3A3A42), CircleShape), contentAlignment = Alignment.Center) {
                Text(account?.initial ?: "", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Column {
                Text(account?.name ?: "", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (!account?.maskedPhone.isNullOrBlank()) Text(account!!.maskedPhone, fontSize = 12.sp, color = Cine.TextMuted)
            }
        }
        // A TV remote is easy to mis-press, so logging out takes a second confirmation.
        CineButton(if (confirming) "Pulsa otra vez para cerrar sesión" else "Cerrar sesión", {
            if (confirming) onLogOut() else confirming = true
        })
    }
}

@Composable
private fun AboutCard() {
    SettingsCard {
        Text("Acerca de", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Cinebox ${BuildConfig.VERSION_NAME}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(UNOFFICIAL_NOTICE, fontSize = 12.sp, color = Cine.TextSoft, lineHeight = 16.sp)
        Text("Esta app no contiene publicidad propia ni cobros.", fontSize = 12.sp, color = Cine.TextSoft, lineHeight = 16.sp)
    }
}
