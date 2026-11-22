package dev.neri.iptv

import androidx.annotation.OptIn
import androidx.compose.foundation.background
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

// O player ao vivo só existe nesta seção: ao ir para Filmes/Séries ele é liberado,
// o que importa em contas com 1 conexão simultânea.
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

    Column(Modifier.fillMaxSize().background(C.Shell)) {
        if (!vm.fullscreen) TopBar(vm, "TV", playlist.name)
        Row(Modifier.fillMaxSize()) {
            if (!vm.fullscreen) ChannelSidebar(vm, Modifier.width(320.dp).fillMaxHeight())
            PlayerPane(vm, player, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

// ---------- Lista lateral (busca, filtro único, canais) ----------

@Composable
private fun ChannelSidebar(vm: MainViewModel, modifier: Modifier) {
    val list = remember(vm.filter, vm.query, vm.channels, vm.favorites) { vm.visibleChannels() }
    var picking by remember { mutableStateOf(false) }
    val searching = vm.query.isNotBlank()

    Column(modifier.background(C.Rail).padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
        TvTextField(vm.query, { vm.query = it }, placeholder = "Buscar canal", imeAction = ImeAction.Search)
        Spacer(Modifier.height(8.dp))
        // Um único seletor no lugar das abas Todos/Grupos/Favoritos.
        TvRow(onClick = { picking = !picking }, selected = picking, radius = 12) {
            Row(Modifier.height(52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (searching) "Resultados da busca" else vm.filterTitle,
                        style = T.ChannelName.copy(fontSize = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${list.size} canais", style = T.Meta)
                }
                Text(if (picking) "▴" else "▾", style = T.Title.copy(color = C.Body))
            }
        }
        Spacer(Modifier.height(8.dp))
        key(picking, searching) {
            if (picking && !searching) {
                FilterList(vm) { picking = false }
            } else {
                ChannelList(vm, list)
            }
        }
    }
}

@Composable
private fun FilterList(vm: MainViewModel, done: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    val options = remember(vm.groups, vm.favorites) {
        listOf<Pair<LiveFilter, String>>(LiveFilter.All to "Todos os canais", LiveFilter.Favorites to "★  Favoritos") +
            vm.groups.map { LiveFilter.Group(it) to it }
    }
    val selected = options.indexOfFirst { it.first == vm.filter }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 3).coerceAtLeast(0))
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(options) { i, (f, label) ->
            val count = when (f) {
                LiveFilter.All -> vm.channels.size
                LiveFilter.Favorites -> vm.favoritesCount
                is LiveFilter.Group -> vm.groupSize(f.name)
            }
            TvRow(
                onClick = { vm.applyFilter(f); done() },
                selected = f == vm.filter,
                radius = 8,
                modifier = if (i == selected) Modifier.focusRequester(focus) else Modifier,
            ) {
                Row(Modifier.height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = T.Row.copy(color = androidx.tv.material3.LocalContentColor.current), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("$count", style = T.Meta.copy(fontSize = 12.sp, color = C.Muted))
                }
            }
        }
    }
}

@Composable
private fun ChannelList(vm: MainViewModel, list: List<Channel>) {
    val header: String? = null
    if (list.isEmpty() && header == null) {
        Text(
            if (vm.filter == LiveFilter.Favorites && vm.query.isBlank()) "Nenhum favorito ainda.\nSegure OK num canal para favoritar." else "Nenhum canal encontrado.",
            style = T.Subtitle,
            modifier = Modifier.padding(12.dp),
        )
        return
    }
    val playingIndex = list.indexOfFirst { it.url == vm.playing?.url }
    val target = playingIndex.coerceAtLeast(0)
    val offset = if (header != null) 1 else 0
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (target + offset - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.focusPlaying) {
        if (vm.focusPlaying) {
            state.scrollToItem((target + offset - 3).coerceAtLeast(0))
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            vm.focusPlaying = false
        }
    }

    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (header != null) {
            item {
                TvRow(
                    onClick = { vm.applyFilter(LiveFilter.All) },
                    radius = 8,
                    modifier = if (list.isEmpty()) Modifier.focusRequester(focus) else Modifier,
                ) {
                    Text("‹  Todos os grupos", style = T.Row, modifier = Modifier.height(40.dp).padding(horizontal = 12.dp, vertical = 11.dp))
                }
            }
        }
        itemsIndexed(list, key = { i, c -> "$i|${c.url}" }) { i, c ->
            ChannelItem(
                vm = vm,
                channel = c,
                number = i + 1,
                selected = i == playingIndex,
                onClick = { vm.select(list, i) },
                modifier = if (i == target) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

// Item no formato do channel-list-item do IPTVnator: logo 44dp, nome, programa atual e barra de progresso.
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
            Modifier.height(68.dp).padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(channel, number, 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$number. ${channel.name}",
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
        Modifier.size(size.dp).background(C.Widget, RoundedCornerShape(10.dp)),
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

// ---------- Player ----------

@OptIn(UnstableApi::class)
@Composable
private fun PlayerPane(vm: MainViewModel, player: TvPlayer, modifier: Modifier) {
    val channel = vm.playing
    val now = channel?.let { vm.nowFor(it) }
    val next = channel?.let { vm.nextFor(it) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(true) }
    LaunchedEffect(channel?.url, showInfo) {
        if (showInfo) {
            delay(4_000)
            showInfo = false
        }
    }
    LaunchedEffect(vm.fullscreen) {
        if (vm.fullscreen) {
            showInfo = true
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }

    Column(modifier.background(Color.Black)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(2.dp, if (focused && !vm.fullscreen) C.FocusRing else Color.Transparent)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.ChannelUp -> { showInfo = true; vm.zap(1); true }
                        Key.ChannelDown -> { showInfo = true; vm.zap(-1); true }
                        Key.DirectionUp -> if (vm.fullscreen) { showInfo = true; vm.zap(1); true } else false
                        Key.DirectionDown -> if (vm.fullscreen) { showInfo = true; vm.zap(-1); true } else false
                        // Em tela cheia, ← → mexem no volume.
                        Key.DirectionLeft -> if (vm.fullscreen) { showInfo = true; player.changeVolume(-5); true } else false
                        Key.DirectionRight -> if (vm.fullscreen) { showInfo = true; player.changeVolume(5); true } else false
                        Key.VolumeMute -> { showInfo = true; player.toggleMute(); true }
                        Key.MediaPlayPause, Key.MediaPause, Key.MediaPlay -> { showInfo = true; player.togglePause(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            when {
                                player.error != null -> player.retry()
                                player.buffering && !player.paused && vm.fullscreen -> player.reconnect()
                                player.paused -> player.unpause()
                                !vm.fullscreen -> vm.fullscreen = true
                                // Em tela cheia, OK pausa; despausar volta para o ao vivo.
                                else -> { showInfo = true; player.togglePause() }
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
            when {
                channel == null ->
                    Text("Escolha um canal na lista", style = T.Subtitle, modifier = Modifier.align(Alignment.Center))
                vm.fullscreen && (showInfo || player.paused || player.buffering || player.error != null) ->
                    InfoOverlay(vm, player, channel, now, next, Modifier.align(Alignment.BottomStart))
                player.paused ->
                    Text(
                        "❚❚  Pausado · OK no vídeo volta para o ao vivo",
                        style = T.Subtitle.copy(color = C.Heading),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                player.error != null || player.buffering ->
                    Text(
                        player.error?.let { "$it · OK para tentar de novo" } ?: "Carregando…",
                        style = T.Subtitle.copy(color = if (player.error != null) C.Live else C.Body),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
            }
        }
        if (!vm.fullscreen && channel != null) NowNextCard(vm, player, channel, now, next)
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

@Composable
private fun InfoOverlay(
    vm: MainViewModel,
    player: TvPlayer,
    channel: Channel,
    now: Programme?,
    next: Programme?,
    modifier: Modifier,
) {
    Row(
        modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .widthIn(max = 760.dp)
            .background(C.Widget.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .border(1.dp, C.Separator, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(channel, vm.playingNumber, 64)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${vm.playingNumber}", style = T.Title.copy(color = C.Selection, fontSize = 22.sp))
                Spacer(Modifier.width(10.dp))
                Text(channel.name, style = T.Title.copy(fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(channel.group, style = T.Subtitle)
            if (now != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveTag()
                    Spacer(Modifier.width(8.dp))
                    Text(now.title, style = T.ChannelName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(now.start), style = T.Meta)
                    ProgressBar(progress(now, vm.now), Modifier.padding(horizontal = 8.dp).width(240.dp))
                    Text(formatTime(now.stop), style = T.Meta)
                }
            }
            if (next != null) Text("A seguir: ${next.title} às ${formatTime(next.start)}", style = T.Meta.copy(fontSize = 12.sp))
            when {
                player.error != null -> Text("${player.error} · OK para tentar de novo", style = T.Subtitle.copy(color = C.Live))
                player.paused -> Text("❚❚  Pausado · OK volta para o ao vivo", style = T.Subtitle.copy(color = C.Selection))
                player.buffering -> Text("Carregando…", style = T.Subtitle)
            }
            VolumeLine(player)
        }
    }
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

// "Agora / A seguir" embaixo do vídeo (substitui o painel do guia).
@Composable
private fun NowNextCard(vm: MainViewModel, player: TvPlayer, channel: Channel, now: Programme?, next: Programme?) {
    Row(
        Modifier.fillMaxWidth().background(C.Content).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(channel, vm.playingNumber, 48)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, style = T.ChannelName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (channel.url in vm.favorites) Text("  ★", style = T.Meta.copy(color = C.Selection, fontSize = 13.sp))
            }
            if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveTag()
                    Spacer(Modifier.width(8.dp))
                    Text(now.title, style = T.Meta.copy(fontSize = 12.sp, color = C.Heading), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ProgressBar(progress(now, vm.now), Modifier.width(220.dp))
            } else {
                Text(channel.group, style = T.Meta)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (next != null) {
                Text("A SEGUIR · ${formatTime(next.start)}", style = T.Label)
                Text(next.title, style = T.Meta.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
            Text("OK no vídeo: tela cheia · lá, OK pausa e ← → volume", style = T.Meta.copy(color = C.Muted))
        }
    }
}
