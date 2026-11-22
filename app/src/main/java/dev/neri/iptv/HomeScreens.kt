package dev.neri.iptv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.Icon
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("pt-BR"))
private val dateTimeFormat = SimpleDateFormat("dd/MM 'às' HH:mm", Locale.forLanguageTag("pt-BR"))

// Barra do topo das telas internas: "‹ Início", título e status.
@Composable
fun TopBar(vm: MainViewModel, title: String, subtitle: String? = null, showBack: Boolean = true, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showBack) Pill(onClick = vm::goHome) { Text("‹  Início") }
        Column(Modifier.weight(1f)) {
            Text(title, style = T.Title.copy(fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = T.Subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        StatusLine(vm)
        trailing()
    }
}

// ---------- Início ----------

private val Accent = Color(0xFF3D5AFE)
private val Ink = Color(0xFF0A0B10)
private val clockFormat = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR"))
private val longDateFormat = SimpleDateFormat("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-BR"))

// Fundo escuro com o brilho azul do lado direito.
@Composable
fun GlowBackground(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Accent.copy(alpha = 0.55f), Accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.72f),
                        radius = size.width * 0.6f,
                    ),
                )
            },
    ) { content() }
}

@Composable
fun HubScreen(vm: MainViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    GlowBackground {
        Row(Modifier.fillMaxSize()) {
            NavRail(vm, active = RailItem.HOME)
            Column(Modifier.fillMaxSize().padding(end = 48.dp, top = 28.dp, bottom = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(Accent, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        Text("▶", style = TextStyle(fontSize = 18.sp, color = Color.White))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Telvo", style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White), modifier = Modifier.weight(1f))
                    StatusLine(vm)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                        HubCard("TV ao vivo", "${vm.channels.size} canais", Icons.Filled.PlayArrow, "Ao vivo", Modifier.focusRequester(first)) {
                            vm.openSection(Section.LIVE)
                        }
                        HubCard(
                            "Séries", if (vm.seriesList.isEmpty()) "Temporadas e episódios" else "${vm.seriesList.size} séries",
                            Icons.Filled.List, "Séries", enabled = vm.hasVod,
                        ) { vm.openSection(Section.SERIES) }
                        HubCard(
                            "Filmes", if (vm.movies.isEmpty()) "Catálogo completo" else "${vm.movies.size} filmes",
                            Icons.Filled.Star, "Filmes", enabled = vm.hasVod,
                        ) { vm.openSection(Section.MOVIES) }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(clockFormat.format(Date(vm.now)), style = TextStyle(fontSize = 84.sp, fontWeight = FontWeight.Light, color = Color.White))
                        Text(
                            longDateFormat.format(Date(vm.now)).replaceFirstChar { it.uppercase() },
                            style = TextStyle(fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f)),
                        )
                    }
                }
                if (vm.recents.isNotEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text("Continuar assistindo", style = T.ChannelName)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                        items(vm.recents.take(20), key = { it.item.url }) { progress ->
                            RecentCard(
                                progress,
                                onClick = { vm.openRecent(progress) },
                                onLongClick = { vm.removeRecent(progress) },
                                modifier = Modifier.width(110.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

// Cartão alto de vidro: no foco cresce, acende o azul, mostra o selo e o traço embaixo.
@Composable
private fun HubCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .size(width = if (focused) 210.dp else 172.dp, height = if (focused) 280.dp else 236.dp)
                .onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.06f),
                focusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.White.copy(alpha = 0.03f),
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = shape),
                focusedBorder = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)), shape = shape),
            ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (focused) Brush.verticalGradient(listOf(Accent.copy(alpha = 0.8f), Color(0xFF1A2260), Color(0xFF121526)))
                        else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent)),
                    ),
            ) {
                if (focused) {
                    Text(
                        badge,
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(Accent, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.4f), modifier = Modifier.size(26.dp))
                    Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f)))
                    Text(subtitle, style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // Traço azul embaixo do cartão em foco.
        Box(Modifier.size(width = 52.dp, height = 4.dp).background(if (focused) Accent else Color.Transparent, RoundedCornerShape(2.dp)))
    }
}

enum class RailItem { SEARCH, HOME, TV, FAVORITES, SERIES, MOVIES, SETTINGS }

// Barra de ícones redondos (menu principal), navegável pelo controle.
@Composable
fun NavRail(vm: MainViewModel, active: RailItem) {
    Column(
        Modifier.fillMaxHeight().width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        val hasList = vm.current != null
        RailButton(Icons.Filled.Home, active == RailItem.HOME, hasList) { vm.goHome() }
        RailButton(Icons.Filled.PlayArrow, active == RailItem.TV, hasList) {
            vm.applyFilter(LiveFilter.All)
            vm.openSection(Section.LIVE)
        }
        RailButton(Icons.Filled.Favorite, active == RailItem.FAVORITES, hasList) {
            vm.applyFilter(LiveFilter.Favorites)
            vm.openSection(Section.LIVE)
        }
        RailButton(Icons.Filled.Settings, active == RailItem.SETTINGS, true) { vm.openSettings() }
    }
}

@Composable
private fun RailButton(icon: ImageVector, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) Accent else Color.White.copy(alpha = 0.07f),
            contentColor = if (active) Color.White else C.Body,
            focusedContainerColor = if (active) Accent else Color.White.copy(alpha = 0.2f),
            focusedContentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.03f),
            disabledContentColor = C.Muted,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, C.FocusRing), shape = RoundedCornerShape(8.dp)),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

// ---------- Configurações (listas) ----------

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    GlowBackground {
    Row(Modifier.fillMaxSize()) {
    NavRail(vm, active = RailItem.SETTINGS)
    Column(Modifier.fillMaxSize()) {
        TopBar(vm, "Configurações", "Suas listas IPTV", showBack = false) {
            Pill(onClick = vm::newPlaylist, active = true) { Text("+  Adicionar lista") }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            if (vm.loading && vm.progress == null) {
                Text("Carregando…", style = T.Subtitle, modifier = Modifier.padding(12.dp))
            }
            if (vm.playlists.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Bem-vindo ao Telvo", style = T.Title.copy(fontSize = 22.sp))
                    Text("Adicione a URL M3U ou Xtream (get.php) do seu provedor para começar.", style = T.Subtitle)
                    Spacer(Modifier.height(16.dp))
                    Pill(onClick = vm::newPlaylist, active = true, modifier = Modifier.focusRequester(first)) { Text("+  Adicionar lista") }
                }
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(vm.playlists, key = { _, p -> p.id }) { i, p ->
                    PlaylistRow(vm, p, if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
    }
    }
    }
}

@Composable
private fun PlaylistRow(vm: MainViewModel, p: Playlist, modifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvRow(onClick = { vm.open(p) }, selected = p.id == vm.current?.id, modifier = modifier.weight(1f)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).background(C.Widget, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("M3U", style = T.Tag.copy(color = C.ProviderM3u))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (p.id == vm.current?.id) "${p.name}  ·  em uso" else p.name,
                        style = T.ChannelName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val updated = if (p.updatedAt > 0) " · Atualizada ${dateTimeFormat.format(Date(p.updatedAt))}" else ""
                    Text(
                        "Canais: ${p.channelCount} · Adicionada em ${dateFormat.format(Date(p.addedAt))}$updated",
                        style = T.Meta.copy(fontSize = 12.sp),
                    )
                }
            }
        }
        Pill(onClick = { vm.refresh(p) }, enabled = !vm.loading) { Text("Atualizar") }
        Pill(onClick = { vm.edit(p) }) { Text("Editar") }
        DeletePill { vm.delete(p) }
    }
}

// Sem diálogos na TV: o primeiro OK pede confirmação, o segundo exclui.
@Composable
private fun DeletePill(onConfirm: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Pill(
        onClick = { if (confirming) onConfirm() else confirming = true },
        active = confirming,
        modifier = Modifier.onFocusChanged { if (!it.isFocused) confirming = false },
    ) {
        Text(if (confirming) "Confirmar exclusão" else "Excluir")
    }
}

@Composable
fun FormScreen(vm: MainViewModel, editing: Playlist?) {
    var url by rememberSaveable { mutableStateOf(editing?.url.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var epgUrl by rememberSaveable { mutableStateOf(editing?.epgUrl.orEmpty()) }
    var userAgent by rememberSaveable { mutableStateOf(editing?.userAgent.orEmpty()) }
    var autoUpdate by rememberSaveable { mutableStateOf(editing?.autoUpdate ?: false) }
    val save = { vm.save(editing, PlaylistForm(name, url, epgUrl, userAgent, autoUpdate)) }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Shell)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 96.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column {
            Text(if (editing == null) "Adicionar lista por URL" else "Configurações da lista", style = T.Title.copy(fontSize = 22.sp))
            Text("Listas M3U/M3U8. O guia (EPG) aceita XMLTV, inclusive .xml.gz.", style = T.Subtitle)
        }
        TvTextField(
            url, { url = it },
            label = "URL da lista",
            placeholder = "http://servidor/lista.m3u",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            modifier = Modifier.width(760.dp).focusRequester(first),
        )
        TvTextField(
            name, { name = it },
            label = "Nome",
            placeholder = "Se vazio, usa o nome do arquivo",
            imeAction = ImeAction.Next,
            modifier = Modifier.width(760.dp),
        )
        TvTextField(
            epgUrl, { epgUrl = it },
            label = "URL do guia EPG · opcional",
            placeholder = "Se vazio, usa o url-tvg da própria lista",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            modifier = Modifier.width(760.dp),
        )
        TvTextField(
            userAgent, { userAgent = it },
            label = "User-Agent · opcional",
            placeholder = USER_AGENT,
            onDone = save,
            modifier = Modifier.width(760.dp),
        )
        TvRow(onClick = { autoUpdate = !autoUpdate }, modifier = Modifier.width(760.dp), radius = 5) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Atualizar a lista sempre que abrir", style = T.Row, modifier = Modifier.weight(1f))
                Text(if (autoUpdate) "Ligado" else "Desligado", style = T.Row.copy(color = if (autoUpdate) C.Selection else C.Muted))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(onClick = save, enabled = !vm.loading, active = true) {
                Text(if (vm.loading) "Baixando lista…" else "Salvar")
            }
            Pill(onClick = vm::back) { Text("Cancelar") }
        }
        vm.error?.let { Text(it, style = T.Subtitle.copy(color = C.Live)) }
        Spacer(Modifier.height(24.dp))
    }
}
