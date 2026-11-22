package dev.neri.iptv

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ptBR = Locale.forLanguageTag("pt-BR")
private val hourFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("EEE, d 'de' MMM", ptBR)

fun formatTime(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(hourFormat)

private fun progress(p: Programme, now: Long) =
    ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)

@Composable
fun WatchScreen(vm: MainViewModel) {
    val vod = vm.vodPlayback
    val prompt = vm.resumePrompt
    when {
        prompt != null -> ResumeScreen(vm, prompt)
        vod != null -> VodPlayerScreen(vm, vod)
        vm.section == Section.LIVE -> LiveSection(vm)
        vm.openSeries != null -> SeriesDetail(vm)
        else -> VodBrowser(vm)
    }
}

private enum class LivePanel { NONE, CHANNELS, GROUPS }

/**
 * TV ao vivo no jeito de TV: o canal fica sempre em tela cheia.
 * ↑ ↓ / CH± trocam de canal · OK abre a lista de canais · ← abre os grupos · → mostra as informações.
 * O player só existe nesta tela: ao sair ele é liberado (contas com 1 conexão simultânea).
 */
@OptIn(UnstableApi::class)
@Composable
private fun LiveSection(vm: MainViewModel) {
    val playlist = vm.current ?: return
    val context = LocalContext.current
    val player = remember(playlist.id, playlist.userAgent) { TvPlayer(context, playlist.userAgent ?: USER_AGENT) }
    DisposableEffect(player) { onDispose { player.release() } }

    val channel = vm.playing
    LaunchedEffect(player, channel?.url) { channel?.let { player.play(it.url) } }
    // Para o stream quando a TV vai para outro app e retoma ao voltar.
    LifecycleStartEffect(player) {
        if (!player.paused) player.resume()
        onStopOrDispose { player.stop() }
    }

    // Sem canal ainda, já abre a lista para escolher.
    var panel by remember { mutableStateOf(if (vm.playing == null) LivePanel.CHANNELS else LivePanel.NONE) }
    var infoTick by remember { mutableIntStateOf(1) }
    var showInfo by remember { mutableStateOf(true) }
    fun info() {
        showInfo = true
        infoTick++
    }
    LaunchedEffect(infoTick) {
        delay(5_000)
        showInfo = false
    }
    val videoFocus = remember { FocusRequester() }
    LaunchedEffect(panel) {
        if (panel == LivePanel.NONE) {
            withFrameNanos { }
            runCatching { videoFocus.requestFocus() }
        }
    }
    // Voltar com a lista aberta só fecha a lista.
    BackHandler(enabled = panel != LivePanel.NONE) { panel = LivePanel.NONE }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(videoFocus)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionUp, Key.ChannelUp -> { vm.zap(1); info(); true }
                        Key.DirectionDown, Key.ChannelDown -> { vm.zap(-1); info(); true }
                        Key.DirectionLeft -> { panel = LivePanel.GROUPS; true }
                        Key.DirectionRight, Key.Info -> { info(); true }
                        Key.Menu -> { panel = LivePanel.CHANNELS; true }
                        Key.VolumeMute -> { player.toggleMute(); info(); true }
                        Key.MediaPlayPause, Key.MediaPause, Key.MediaPlay -> { player.togglePause(); info(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            when {
                                player.error != null -> player.retry()
                                player.paused -> player.unpause()
                                else -> panel = LivePanel.CHANNELS
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
        ) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        keepScreenOn = true
                        isFocusable = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setEnableComposeSurfaceSyncWorkaround(true)
                        this.player = player.exo
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Mensagem no centro: erro, pausa ou carregando.
        val center = when {
            channel == null -> null
            player.error != null -> "${player.error}\nOK para tentar de novo"
            player.paused -> "❚❚  Pausado\nOK volta para o ao vivo"
            player.buffering -> "Carregando…"
            else -> null
        }
        if (center != null && panel == LivePanel.NONE) {
            Text(
                center,
                style = T.Title.copy(fontSize = 20.sp, color = if (player.error != null) C.Live else C.Heading),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 28.dp, vertical = 18.dp),
            )
        }

        if (channel != null && panel == LivePanel.NONE && (showInfo || player.paused || player.error != null)) {
            InfoBanner(vm, player, channel, Modifier.align(Alignment.BottomCenter))
        }

        if (panel != LivePanel.NONE) {
            ChannelPanel(
                vm = vm,
                player = player,
                startInGroups = panel == LivePanel.GROUPS,
                onPicked = {
                    panel = LivePanel.NONE
                    info()
                },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

// ---------- Lista sobreposta: grupos + canais + atalhos ----------

@Composable
private fun ChannelPanel(
    vm: MainViewModel,
    player: TvPlayer,
    startInGroups: Boolean,
    onPicked: () -> Unit,
    modifier: Modifier,
) {
    val groupFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val options = remember(vm.groups) {
        listOf<Pair<LiveFilter, String>>(LiveFilter.All to "Todos os canais", LiveFilter.Favorites to "★  Favoritos") +
            vm.groups.map { LiveFilter.Group(it) to it }
    }
    val list = remember(vm.filter, vm.query, vm.channels, vm.favorites) { vm.visibleChannels() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { if (startInGroups || list.isEmpty()) groupFocus.requestFocus() else channelFocus.requestFocus() }
    }

    Row(
        modifier
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xF70D0F12), Color(0xEB0D0F12), Color(0xB30D0F12), Color.Transparent)))
            .padding(start = 32.dp, top = 28.dp, bottom = 20.dp, end = 120.dp),
    ) {
        // Grupos: só passar o foco já mostra os canais do grupo.
        Column(Modifier.width(250.dp)) {
            Text("GRUPOS", style = T.Label, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            val selected = options.indexOfFirst { it.first == vm.filter }.coerceAtLeast(0)
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 4).coerceAtLeast(0))
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(options) { i, (f, label) ->
                    val count = when (f) {
                        LiveFilter.All -> vm.channels.size
                        LiveFilter.Favorites -> vm.favoritesCount
                        is LiveFilter.Group -> vm.groupSize(f.name)
                    }
                    TvRow(
                        onClick = {
                            vm.filter = f
                            runCatching { channelFocus.requestFocus() }
                        },
                        selected = f == vm.filter,
                        radius = 5,
                        modifier = (if (i == selected) Modifier.focusRequester(groupFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused && f != vm.filter) vm.filter = f },
                    ) {
                        Row(Modifier.height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = T.Row.copy(color = androidx.tv.material3.LocalContentColor.current), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$count", style = T.Meta.copy(fontSize = 12.sp, color = C.Muted))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.width(420.dp)) {
            QuickActions(vm, player)
            Spacer(Modifier.height(12.dp))
            Text(
                "${if (vm.query.isBlank()) vm.filterTitle.uppercase() else "BUSCA"} · ${list.size} CANAIS",
                style = T.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
            if (list.isEmpty()) {
                Text(
                    if (vm.filter == LiveFilter.Favorites && vm.query.isBlank()) "Nenhum favorito ainda.\nSegure OK num canal para favoritar." else "Nenhum canal encontrado.",
                    style = T.Subtitle,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                val playingIndex = list.indexOfFirst { it.url == vm.playing?.url }
                val target = playingIndex.coerceAtLeast(0)
                val state = rememberLazyListState(initialFirstVisibleItemIndex = (target - 3).coerceAtLeast(0))
                key(vm.filter, vm.query) {
                    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(list, key = { i, c -> "$i|${c.url}" }) { i, c ->
                            ChannelItem(
                                vm = vm,
                                channel = c,
                                number = i + 1,
                                selected = i == playingIndex,
                                onClick = {
                                    vm.select(list, i)
                                    onPicked()
                                },
                                modifier = if (i == target) Modifier.focusRequester(channelFocus) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Atalhos no topo da lista: pausa, favorito, volume e busca.
@Composable
private fun QuickActions(vm: MainViewModel, player: TvPlayer) {
    val channel = vm.playing
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(onClick = { player.togglePause() }, active = player.paused, enabled = channel != null) {
                Text(if (player.paused) "▶  Ao vivo" else "❚❚  Pausar")
            }
            if (channel != null) {
                val fav = channel.url in vm.favorites
                Pill(onClick = { vm.toggleFavorite(channel) }, active = fav) { Text(if (fav) "★" else "☆") }
            }
            Pill(onClick = { player.changeVolume(-10) }) { Text("−") }
            Pill(onClick = { player.toggleMute() }, active = player.muted) {
                Text(if (player.muted) "Mudo" else "${player.volume}%")
            }
            Pill(onClick = { player.changeVolume(10) }) { Text("+") }
        }
        TvTextField(vm.query, { vm.query = it }, placeholder = "Buscar canal", imeAction = ImeAction.Search)
    }
}

// Item da lista: logo, número, nome e programa atual.
@Composable
private fun ChannelItem(
    vm: MainViewModel,
    channel: Channel,
    number: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val now = vm.nowFor(channel)
    TvRow(onClick = onClick, onLongClick = { vm.toggleFavorite(channel) }, selected = selected, modifier = modifier) {
        Row(
            Modifier.height(64.dp).padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$number", style = T.Meta.copy(color = C.Muted, fontSize = 12.sp), modifier = Modifier.width(34.dp))
            Logo(channel, number, 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        style = T.ChannelName.copy(color = if (selected) C.Selection else C.Heading),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.url in vm.favorites) Text("  ★", style = T.Meta.copy(color = C.Selection, fontSize = 13.sp))
                }
                if (now != null) {
                    Text(now.title, style = T.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressBar(progress(now, vm.now), Modifier.width(84.dp))
                }
            }
        }
    }
}

@Composable
fun Logo(channel: Channel, number: Int, size: Int) {
    Box(
        Modifier.size(size.dp).background(C.Widget, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (channel.logo != null) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        } else {
            Text("$number", style = T.Meta.copy(color = C.Muted, fontSize = 12.sp))
        }
    }
}

@Composable
fun ProgressBar(value: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).background(C.Separator, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(value).height(3.dp).background(C.Selection, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun LiveTag() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(C.Live, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text("AO VIVO", style = T.Tag.copy(color = C.Live))
    }
}

// Faixa de informações embaixo, ao trocar de canal (como nas TVs).
@Composable
private fun InfoBanner(vm: MainViewModel, player: TvPlayer, channel: Channel, modifier: Modifier) {
    val now = vm.nowFor(channel)
    val next = vm.nextFor(channel)
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000), Color(0xF2000000))))
            .padding(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(channel, vm.playingNumber, 72)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${vm.playingNumber}", style = T.Title.copy(color = C.Selection, fontSize = 24.sp))
                Spacer(Modifier.width(12.dp))
                Text(channel.name, style = T.Title.copy(fontSize = 24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (channel.url in vm.favorites) Text("  ★", style = T.Title.copy(color = C.Selection))
            }
            if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveTag()
                    Spacer(Modifier.width(8.dp))
                    Text(now.title, style = T.ChannelName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(now.start), style = T.Meta)
                    ProgressBar(progress(now, vm.now), Modifier.padding(horizontal = 8.dp).width(280.dp))
                    Text(formatTime(now.stop), style = T.Meta)
                }
            } else {
                Text(channel.group, style = T.Subtitle)
            }
            if (next != null) Text("A seguir: ${next.title} às ${formatTime(next.start)}", style = T.Meta.copy(fontSize = 12.sp))
        }
        Spacer(Modifier.width(24.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VolumeLine(player)
            Text("OK  canais   ·   ←  grupos   ·   ↑ ↓  trocar", style = T.Meta.copy(color = C.Muted))
        }
    }
}

// Progresso ("Baixando filmes… 12 MB") e mensagens de erro/sucesso da última ação.
@Composable
fun StatusLine(vm: MainViewModel, modifier: Modifier = Modifier) {
    val (text, color) = when {
        vm.progress != null -> vm.progress!! to C.Selection
        vm.error != null -> vm.error!! to C.Live
        vm.notice != null -> vm.notice!! to C.Success
        else -> return
    }
    Text(text, style = T.Meta.copy(fontSize = 12.sp, color = color), maxLines = 2, modifier = modifier)
}

// Volume atual: "Volume 70%" com barrinha, ou "Mudo".
@Composable
fun VolumeLine(player: TvPlayer, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (player.muted) "Mudo" else "Volume ${player.volume}%",
            style = T.Meta.copy(fontSize = 12.sp, color = if (player.muted) C.Muted else C.Body),
            modifier = Modifier.width(90.dp),
        )
        ProgressBar(if (player.muted) 0f else player.volume / 100f, Modifier.width(120.dp))
    }
}

