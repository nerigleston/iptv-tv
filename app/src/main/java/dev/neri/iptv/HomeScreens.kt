package dev.neri.iptv

import androidx.compose.foundation.Image
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
        Modifier.fillMaxWidth().background(C.Rail).padding(horizontal = 24.dp, vertical = 14.dp),
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

@Composable
fun HubScreen(vm: MainViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().background(C.Shell).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.banner), null, Modifier.size(width = 72.dp, height = 40.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Telvo", style = T.Title.copy(fontSize = 22.sp))
                Text(vm.current?.name.orEmpty(), style = T.Subtitle)
            }
            StatusLine(vm)
        }
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            HubCard("TV", "${vm.channels.size} canais ao vivo", "▶", C.Selection, Modifier.weight(1f).focusRequester(first)) {
                vm.openSection(Section.LIVE)
            }
            HubCard(
                "Séries", if (vm.seriesList.isEmpty()) "Temporadas e episódios" else "${vm.seriesList.size} séries",
                "▤", Color(0xFFC084FC), Modifier.weight(1f), enabled = vm.hasVod,
            ) { vm.openSection(Section.SERIES) }
            HubCard(
                "Filmes", if (vm.movies.isEmpty()) "Catálogo completo" else "${vm.movies.size} filmes",
                "◉", Color(0xFFFBBF24), Modifier.weight(1f), enabled = vm.hasVod,
            ) { vm.openSection(Section.MOVIES) }
            HubCard("Configurações", "Listas e conta", "⚙", C.Body, Modifier.weight(1f), onClick = vm::openSettings)
        }
        if (vm.recents.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text("Continuar assistindo", style = T.ChannelName)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(vm.recents.take(20), key = { it.item.url }) { progress ->
                    RecentCard(
                        progress,
                        onClick = { vm.openRecent(progress) },
                        onLongClick = { vm.removeRecent(progress) },
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HubCard(
    title: String,
    subtitle: String,
    icon: String,
    tint: Color,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(200.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = C.Widget,
            focusedContainerColor = C.CardHover,
            disabledContainerColor = C.Widget.copy(alpha = 0.4f),
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, C.Separator), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, C.FocusRing), shape = shape),
        ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.16f), Color.Transparent)))
                .padding(24.dp),
        ) {
            Text(icon, style = TextStyle(fontSize = 34.sp, color = tint))
            Spacer(Modifier.weight(1f))
            Text(title, style = T.Title.copy(fontSize = 22.sp, color = if (enabled) C.Heading else C.Muted))
            Text(subtitle, style = T.Subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    Column(Modifier.fillMaxSize().background(C.Shell)) {
        TopBar(vm, "Configurações", "Suas listas IPTV", showBack = vm.current != null) {
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

@Composable
private fun PlaylistRow(vm: MainViewModel, p: Playlist, modifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvRow(onClick = { vm.open(p) }, selected = p.id == vm.current?.id, modifier = modifier.weight(1f)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).background(C.Widget, RoundedCornerShape(10.dp)),
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
        TvRow(onClick = { autoUpdate = !autoUpdate }, modifier = Modifier.width(760.dp), radius = 10) {
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
