package dev.neri.iptv

import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
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
import androidx.media3.common.C as MediaC
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

// "Filmes | Lançamentos 2025" -> "Lançamentos 2025"
private fun shortCategory(name: String) = if ('|' in name) name.substringAfter('|').trim() else name

// ---------- Grade de filmes / séries ----------

@Composable
fun VodBrowser(vm: MainViewModel) {
    val categories = vm.vodCategoriesFor(vm.section)
    val all = if (vm.section == Section.MOVIES) vm.movies else vm.seriesList
    val noun = if (vm.section == Section.MOVIES) "filmes" else "séries"
    val searching = vm.vodQuery.isNotBlank()
    val category = vm.vodCategory?.let { id -> categories.firstOrNull { it.id == id } }
    val title = when {
        searching -> "Busca em ${vm.section.label}"
        category != null -> shortCategory(category.name)
        else -> vm.section.label
    }

    var drawer by remember { mutableStateOf(false) }
    // Fechar a gaveta devolve o foco aos títulos.
    BackHandler(enabled = drawer) {
        drawer = false
        vm.focusPlaying = true
    }

    Box(Modifier.fillMaxSize().background(C.Shell)) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = T.Title.copy(fontSize = 26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (searching || category != null) {
                    val count = vm.vodItems().size
                    Text(if (count == 1) "1 título · Voltar para sair" else "$count títulos · ← categorias · Voltar sai", style = T.Subtitle)
                }
            }
            StatusLine(vm)
            Spacer(Modifier.width(16.dp))
            TvTextField(vm.vodQuery, { vm.vodQuery = it }, placeholder = "Buscar $noun", imeAction = ImeAction.Search, modifier = Modifier.width(320.dp))
        }
        when {
            vm.vodLoading && all.isEmpty() ->
                Text("Carregando $noun…", style = T.Subtitle.copy(fontSize = 16.sp), modifier = Modifier.padding(48.dp))
            searching || category != null -> {
                val items = remember(vm.section, vm.vodCategory, vm.vodQuery, all) { vm.vodItems() }
                if (items.isEmpty()) {
                    Text("Nada encontrado.", style = T.Subtitle, modifier = Modifier.padding(48.dp))
                } else {
                    Box(Modifier.padding(horizontal = 44.dp)) { PosterGrid(vm, items) }
                }
            }
            all.isEmpty() -> Text("Nada por aqui.", style = T.Subtitle, modifier = Modifier.padding(48.dp))
            else -> VodRows(vm, categories, all)
        }
    }
    // Borda invisível à esquerda: ← no primeiro título chega nela e abre a gaveta de categorias,
    // do mesmo jeito que os grupos na TV ao vivo.
    if (!drawer) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(2.dp)
                .fillMaxHeight()
                .onFocusChanged { if (it.isFocused) drawer = true }
                .focusable(),
        )
    }
    if (drawer) {
        CategoryDrawer(vm, categories, all.size, Modifier.align(Alignment.CenterStart)) { drawer = false }
    }
    }
}

// Gaveta de categorias (abre com ←). "Todas" volta para as fileiras.
@Composable
private fun CategoryDrawer(vm: MainViewModel, categories: List<Category>, total: Int, modifier: Modifier, close: () -> Unit) {
    val counts = remember(vm.section, vm.movies, vm.seriesList) { vm.vodCounts() }
    val options = remember(categories) { listOf<Category?>(null) + categories }
    val selected = options.indexOfFirst { it?.id == vm.vodCategory }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 4).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Column(
        modifier
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xFA0D0F12), Color(0xF00D0F12), Color(0xB30D0F12), Color.Transparent)))
            .padding(start = 32.dp, top = 28.dp, bottom = 20.dp, end = 140.dp),
    ) {
        Text("CATEGORIAS", style = T.Label, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.width(320.dp)) {
            itemsIndexed(options) { i, c ->
                TvRow(
                    onClick = {
                        if (c == null) {
                            vm.vodCategory = null
                            vm.vodQuery = ""
                            vm.vodFocusId = null
                            vm.focusPlaying = true
                        } else {
                            vm.vodQuery = ""
                            vm.openCategory(c.id)
                        }
                        close()
                    },
                    selected = c?.id == vm.vodCategory,
                    radius = 5,
                    modifier = if (i == selected) Modifier.focusRequester(focus) else Modifier,
                ) {
                    Row(Modifier.height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            c?.let { shortCategory(it.name) } ?: "Todas (fileiras)",
                            style = T.Row.copy(color = androidx.tv.material3.LocalContentColor.current),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${c?.let { counts[it.id] ?: 0 } ?: total}", style = T.Meta.copy(fontSize = 12.sp, color = C.Muted))
                    }
                }
            }
        }
    }
}

private const val ROW_LIMIT = 30

// Fileiras no estilo Google TV: Continuar assistindo + uma fileira por categoria.
@Composable
private fun VodRows(vm: MainViewModel, categories: List<Category>, all: List<VodItem>) {
    val byCategory = remember(all) { all.groupBy { it.categoryId } }
    val rows = remember(categories, byCategory) { categories.filter { !byCategory[it.id].isNullOrEmpty() } }
    val recents = vm.recents
    val offset = if (recents.isNotEmpty()) 1 else 0
    // Volta o foco para o título que estava aberto (ou o primeiro da tela).
    val targetRow = rows.indexOfFirst { c -> byCategory[c.id]!!.take(ROW_LIMIT).any { it.id == vm.vodFocusId } }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (targetRow + offset).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.focusPlaying, rows.size) {
        if (vm.focusPlaying && rows.isNotEmpty()) {
            // As fileiras levam alguns quadros para montar: tenta até conseguir.
            repeat(30) {
                withFrameNanos { }
                if (runCatching { focus.requestFocus() }.isSuccess) {
                    vm.focusPlaying = false
                    return@LaunchedEffect
                }
            }
        }
    }

    LazyColumn(state = state, contentPadding = PaddingValues(bottom = 48.dp)) {
        if (recents.isNotEmpty()) {
            item(key = "recentes") {
                RowTitle("Continuar assistindo")
                LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(recents.take(20), key = { _, p -> p.item.url }) { i, progress ->
                        RecentCard(
                            progress,
                            onClick = { vm.openRecent(progress) },
                            onLongClick = { vm.removeRecent(progress) },
                            modifier = Modifier.width(130.dp).then(if (targetRow < 0 && i == 0) Modifier.focusRequester(focus) else Modifier),
                        )
                    }
                }
            }
        }
        itemsIndexed(rows, key = { _, c -> c.id }) { r, c ->
            val items = byCategory[c.id]!!
            val shown = items.take(ROW_LIMIT)
            val target = if (r == targetRow) shown.indexOfFirst { it.id == vm.vodFocusId } else if (targetRow < 0 && recents.isEmpty() && r == 0) 0 else -1
            val rowState = rememberLazyListState(initialFirstVisibleItemIndex = target.coerceAtLeast(0))
            RowTitle("${shortCategory(c.name)}  ·  ${items.size}")
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                itemsIndexed(shown, key = { _, it -> it.id }) { i, item ->
                    PosterCard(
                        item,
                        onClick = { vm.openVod(item) },
                        modifier = Modifier.width(130.dp).then(if (i == target) Modifier.focusRequester(focus) else Modifier),
                    )
                }
                if (items.size > ROW_LIMIT) {
                    item(key = "todos") { SeeAllCard(items.size) { vm.openCategory(c.id) } }
                }
            }
        }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(text, style = T.Title.copy(fontSize = 17.sp), modifier = Modifier.padding(start = 52.dp, top = 14.dp))
}

// Último cartão da fileira: abre a categoria inteira em grade.
@Composable
private fun SeeAllCard(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.width(130.dp).aspectRatio(2f / 3f),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(containerColor = C.Widget, focusedContainerColor = C.CardHover),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, C.Separator), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, C.FocusRing), shape = shape),
        ),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Ver todos", style = T.ChannelName)
            Text("$count títulos", style = T.Meta)
        }
    }
}

@Composable
private fun PosterGrid(vm: MainViewModel, items: List<VodItem>) {
    // Sem título marcado (categoria recém-aberta, busca), o foco vai para o primeiro.
    val target = items.indexOfFirst { it.id == vm.vodFocusId }.coerceAtLeast(0)
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = target)
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.focusPlaying) {
        if (vm.focusPlaying) {
            state.scrollToItem(target)
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            vm.focusPlaying = false
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(130.dp),
        state = state,
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
            PosterCard(item, onClick = { vm.openVod(item) }, modifier = if (i == target) Modifier.focusRequester(focus) else Modifier)
        }
    }
}

@Composable
private fun PosterCard(item: VodItem, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
            colors = ClickableSurfaceDefaults.colors(containerColor = C.Widget, focusedContainerColor = C.Widget),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, C.Separator), shape = shape),
                focusedBorder = Border(BorderStroke(3.dp, C.FocusRing), shape = shape),
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Aparece só se a capa não carregar.
                Text(
                    item.name,
                    style = T.Meta.copy(fontSize = 12.sp, color = C.Muted),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
                AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                item.rating?.let {
                    Text(
                        "★ $it",
                        style = T.Tag.copy(color = Color(0xFFFBBF24), fontSize = 11.sp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.name,
            style = T.ChannelName.copy(fontSize = 13.sp, color = if (focused) C.Heading else C.Body),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- Detalhe da série ----------

@Composable
fun SeriesDetail(vm: MainViewModel) {
    val series = vm.openSeries ?: return
    val info = vm.seriesInfo
    val episodes = info?.episodes?.get(vm.season).orEmpty()

    Row(Modifier.fillMaxSize().background(C.Shell).padding(horizontal = 48.dp, vertical = 27.dp)) {
        Box(
            Modifier.width(240.dp).aspectRatio(2f / 3f).background(C.Widget, RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(model = series.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(series.name, style = T.Title.copy(fontSize = 28.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(series.year, series.genre, series.rating?.let { "★ $it" }).joinToString("  ·  ")
            if (meta.isNotEmpty()) Text(meta, style = T.Subtitle.copy(fontSize = 14.sp))
            series.plot?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = T.Row.copy(fontSize = 14.sp, lineHeight = 20.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            if (info == null) {
                Text(if (vm.vodLoading) "Carregando episódios…" else vm.error ?: "Sem episódios.", style = T.Subtitle)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                info.seasons.forEach { s ->
                    Pill(onClick = { vm.season = s }, active = s == vm.season) {
                        Text(if (s == 0) "Extras" else "Temporada $s")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("${episodes.size} EPISÓDIOS", style = T.Label, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
            EpisodeList(vm, episodes)
        }
    }
}

@Composable
private fun EpisodeList(vm: MainViewModel, episodes: List<Episode>) {
    val target = episodes.indexOfFirst { it.id == vm.vodFocusId }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (target - 2).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.focusPlaying, episodes) {
        if (vm.focusPlaying && episodes.isNotEmpty()) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
            vm.focusPlaying = false
        }
    }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(episodes, key = { _, e -> e.id }) { i, e ->
            var focused by remember { mutableStateOf(false) }
            TvRow(
                onClick = { vm.playEpisode(episodes, i) },
                modifier = (if (i == target) Modifier.focusRequester(focus) else Modifier).onFocusChanged { focused = it.isFocused },
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.number}", style = T.Title.copy(color = C.Selection), modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(e.title.substringAfterLast(" - "), style = T.ChannelName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e.duration?.let { Text(it, style = T.Meta) }
                        if (focused && e.plot != null) {
                            Text(e.plot, style = T.Meta.copy(fontSize = 12.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Player de filmes e episódios ----------

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen(vm: MainViewModel, playback: VodPlayback) {
    val context = LocalContext.current
    val userAgent = vm.current?.userAgent ?: USER_AGENT
    val player = remember(userAgent) { TvPlayer(context, userAgent) }
    val item = playback.current
    var loaded by remember { mutableStateOf<Playable?>(null) }

    fun save(target: Playable?) {
        val duration = player.exo.duration
        if (target != null && duration != MediaC.TIME_UNSET) vm.saveProgress(target, player.exo.currentPosition, duration)
    }

    // Ao sair do player (Voltar ou fim da tela) grava onde parou antes de liberar o player.
    DisposableEffect(player) {
        onDispose {
            save(loaded)
            player.release()
        }
    }

    var showControls by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(0L) }
    LaunchedEffect(item.url) {
        // Trocou de episódio: guarda o anterior antes de carregar o próximo.
        save(loaded)
        val start = if (loaded == null) playback.startMs else 0L
        player.play(item.url, start)
        loaded = item
        showControls = true
        while (true) {
            delay(15_000)
            if (player.exo.isPlaying) save(item)
        }
    }
    // Filme pausa (em vez de parar) para continuar do mesmo ponto ao voltar para o app.
    LifecycleStartEffect(player) {
        player.resume()
        onStopOrDispose {
            save(loaded)
            player.exo.pause()
        }
    }

    val position by produceState(Triple(0L, 0L, false), player) {
        while (true) {
            val duration = player.exo.duration.takeIf { it != MediaC.TIME_UNSET } ?: 0L
            value = Triple(player.exo.currentPosition, duration, player.exo.isPlaying)
            delay(500)
        }
    }
    val (current, duration, isPlaying) = position
    LaunchedEffect(lastInput, isPlaying) {
        if (isPlaying) {
            delay(4_000)
            showControls = false
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    fun seek(delta: Long) {
        val target = (player.exo.currentPosition + delta).coerceAtLeast(0)
        player.exo.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val handled = when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        when {
                            player.error != null -> player.retry()
                            player.buffering -> player.reconnect()
                            else -> player.togglePause()
                        }
                        true
                    }
                    Key.DirectionLeft, Key.MediaRewind -> { seek(-10_000); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seek(10_000); true }
                    Key.ChannelUp, Key.MediaNext -> { vm.nextVod(1); true }
                    Key.ChannelDown, Key.MediaPrevious -> { vm.nextVod(-1); true }
                    // ↑ ↓ mexem no volume do app.
                    Key.DirectionUp -> { player.changeVolume(5); true }
                    Key.DirectionDown -> { player.changeVolume(-5); true }
                    Key.VolumeMute -> { player.toggleMute(); true }
                    else -> false
                }
                if (handled) {
                    showControls = true
                    lastInput = System.currentTimeMillis()
                }
                handled
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

        if (showControls || !isPlaying || player.error != null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item.subtitle?.let { Text(it, style = T.Subtitle.copy(fontSize = 14.sp)) }
                Text(item.title, style = T.Title.copy(fontSize = 24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isPlaying) "▶" else "❚❚", style = T.Title.copy(color = C.Selection), modifier = Modifier.width(36.dp))
                    Text(formatDuration(current), style = T.Meta.copy(fontSize = 13.sp, color = C.Heading))
                    ProgressBar(
                        if (duration > 0) (current.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        Modifier.weight(1f).padding(horizontal = 14.dp),
                    )
                    Text(formatDuration(duration), style = T.Meta.copy(fontSize = 13.sp, color = C.Heading))
                }
                val status = when {
                    player.error != null -> "${player.error} · OK para tentar de novo"
                    player.buffering -> "Carregando…"
                    else -> buildString {
                        append("OK pausa · ← → 10 s · ↑ ↓ volume")
                        if (playback.queue.size > 1) append(" · CH+/CH− episódio ${playback.index + 1} de ${playback.queue.size}")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        status,
                        style = T.Meta.copy(fontSize = 12.sp, color = if (player.error != null) C.Live else C.Body),
                        modifier = Modifier.weight(1f),
                    )
                    VolumeLine(player)
                }
            }
        }
    }
}

@Composable
fun RecentCard(progress: WatchProgress, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    var focused by remember { mutableStateOf(false) }
    val item = progress.item
    Column(modifier) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
            colors = ClickableSurfaceDefaults.colors(containerColor = C.Widget, focusedContainerColor = C.Widget),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, C.Separator), shape = shape),
                focusedBorder = Border(BorderStroke(3.dp, C.FocusRing), shape = shape),
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    item.title,
                    style = T.Meta.copy(fontSize = 12.sp, color = C.Muted),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
                AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // Faixa escura com a barra de progresso no pé do pôster.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(start = 10.dp, end = 10.dp, top = 24.dp, bottom = 10.dp),
                ) {
                    ProgressBar((progress.position.toFloat() / progress.duration).coerceIn(0f, 1f), Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            item.subtitle?.let { "$it · ${item.title}" } ?: item.title,
            style = T.ChannelName.copy(fontSize = 13.sp, color = if (focused) C.Heading else C.Body),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Parou em ${formatDuration(progress.position)} de ${formatDuration(progress.duration)}",
            style = T.Meta.copy(color = C.Muted),
        )
    }
}

// ---------- Continuar ou começar do início ----------

@Composable
fun ResumeScreen(vm: MainViewModel, prompt: ResumePrompt) {
    val progress = prompt.progress
    val item = prompt.queue[prompt.index]
    val focus = remember { FocusRequester() }
    LaunchedEffect(prompt) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(C.Shell)) {
        // Capa ampliada e escurecida ao fundo, como o hero do detalhe no IPTVnator.
        AsyncImage(
            model = item.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.18f),
        )
        Row(
            Modifier.align(Alignment.CenterStart).padding(horizontal = 96.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(220.dp).aspectRatio(2f / 3f).background(C.Widget, RoundedCornerShape(8.dp))) {
                AsyncImage(model = item.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(40.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.subtitle?.let { Text(it, style = T.Subtitle.copy(fontSize = 15.sp)) }
                Text(item.title, style = T.Title.copy(fontSize = 28.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "Você parou em ${formatDuration(progress.position)} de ${formatDuration(progress.duration)}",
                    style = T.Subtitle.copy(fontSize = 15.sp),
                )
                ProgressBar((progress.position.toFloat() / progress.duration).coerceIn(0f, 1f), Modifier.width(420.dp))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill(onClick = { vm.resume(fromStart = false) }, active = true, modifier = Modifier.focusRequester(focus)) {
                        Text("▶  Continuar de ${formatDuration(progress.position)}")
                    }
                    Pill(onClick = { vm.resume(fromStart = true) }) { Text("↺  Começar do início") }
                }
            }
        }
    }
}
