package dev.neri.iptv

import androidx.compose.foundation.Image
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

@Composable
fun HomeScreen(vm: MainViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize().background(C.Shell)) {
        // Cabeçalho escuro como a tela de boas-vindas do IPTVnator.
        Row(
            Modifier.fillMaxWidth().background(C.Rail).padding(horizontal = 48.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.banner), null, Modifier.size(width = 96.dp, height = 54.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Telvo", style = T.Title.copy(fontSize = 24.sp))
                Text(
                    "Escolha uma lista ou adicione uma nova pela URL",
                    style = T.Subtitle.copy(fontSize = 15.sp),
                )
            }
            Pill(onClick = vm::newPlaylist, active = true) { Text("+  Adicionar por URL") }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 20.dp)) {
            Text("LISTAS", style = T.Label, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            if (vm.loading && vm.progress == null) {
                Text("Carregando…", style = T.Subtitle, modifier = Modifier.padding(12.dp))
            } else {
                StatusLine(vm, Modifier.padding(12.dp))
            }
            if (vm.playlists.isEmpty()) {
                Text(
                    "Nenhuma lista ainda. Use \"Adicionar por URL\" ou envie pelo adb (veja o README).",
                    style = T.Subtitle,
                    modifier = Modifier.padding(12.dp),
                )
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
                    Text(p.name, style = T.ChannelName, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
