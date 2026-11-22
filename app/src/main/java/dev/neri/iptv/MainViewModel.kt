package dev.neri.iptv

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

enum class Section(val label: String) {
    LIVE("TV"),
    SERIES("Séries"),
    MOVIES("Filmes"),
}

// Filtro único da lista de canais: substitui as abas Todos/Grupos/Favoritos.
sealed interface LiveFilter {
    data object All : LiveFilter
    data object Favorites : LiveFilter
    data class Group(val name: String) : LiveFilter
}

sealed interface Screen {
    data object Hub : Screen          // Início: TV, Séries, Filmes, Configurações
    data object Watch : Screen        // dentro de TV, Séries ou Filmes
    data object Settings : Screen     // listas
    data class Form(val editing: Playlist?) : Screen
}

data class PlaylistForm(
    val name: String,
    val url: String,
    val epgUrl: String,
    val userAgent: String,
    val autoUpdate: Boolean,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private var xtream: Xtream? = null
    private var byGroup: Map<String, List<Channel>> = emptyMap()
    private var m3uEpgUrl: String? = null
    private var playingList: List<Channel> = emptyList()
    private var loadJob: Job? = null
    private var epgJob: Job? = null
    private var shortEpgJob: Job? = null
    private var vodJob: Job? = null
    private var moviesLoaded = false
    private var seriesLoaded = false

    var playlists by mutableStateOf(store.playlists())
        private set
    var screen by mutableStateOf<Screen>(Screen.Hub)
        private set
    var current by mutableStateOf<Playlist?>(null)
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var groups by mutableStateOf<List<String>>(emptyList())
        private set
    var favorites by mutableStateOf(store.favorites)
        private set
    var epg by mutableStateOf(Epg.EMPTY)
        private set
    var shortEpg by mutableStateOf<Map<String, List<Programme>>>(emptyMap())
        private set
    var epgLoading by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var progress by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    // Ao vivo
    var section by mutableStateOf(Section.LIVE)
        private set
    var filter by mutableStateOf<LiveFilter>(LiveFilter.All)
    var query by mutableStateOf("")
    var playing by mutableStateOf<Channel?>(null)
        private set
    var playingNumber by mutableStateOf(0)
        private set
    var fullscreen by mutableStateOf(false)
    var showEpg by mutableStateOf(true)
    // Pede para a lista da tela atual devolver o foco ao item ativo.
    var focusPlaying by mutableStateOf(false)
    var now by mutableStateOf(System.currentTimeMillis())
        private set

    // Filmes e séries
    var movieCategories by mutableStateOf<List<Category>>(emptyList())
        private set
    var movies by mutableStateOf<List<VodItem>>(emptyList())
        private set
    var seriesCategories by mutableStateOf<List<Category>>(emptyList())
        private set
    var seriesList by mutableStateOf<List<VodItem>>(emptyList())
        private set
    var vodLoading by mutableStateOf(false)
        private set
    var vodCategory by mutableStateOf<String?>(null)
    var vodQuery by mutableStateOf("")
    var vodFocusId by mutableStateOf<String?>(null)
    var openSeries by mutableStateOf<VodItem?>(null)
        private set
    var seriesInfo by mutableStateOf<SeriesInfo?>(null)
        private set
    var season by mutableStateOf(1)
    var vodPlayback by mutableStateOf<VodPlayback?>(null)
        private set
    var recents by mutableStateOf<List<WatchProgress>>(emptyList())
        private set
    var resumePrompt by mutableStateOf<ResumePrompt?>(null)
        private set

    val epgUrl get() = current?.epgUrl ?: m3uEpgUrl
    val hasEpg get() = epgUrl != null || xtream != null
    val isXtream get() = xtream != null
    // No Início, Voltar sai do app (comportamento padrão da TV).
    val canGoBack get() = screen != Screen.Hub && !(screen == Screen.Settings && current == null)
    val hasVod get() = xtream != null || movies.isNotEmpty() || seriesList.isNotEmpty()

    init {
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                now = System.currentTimeMillis()
            }
        }
        val last = store.lastPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }
        if (last != null) open(last) else screen = Screen.Settings
    }

    // ---------- Ao vivo ----------

    fun visibleChannels(): List<Channel> {
        val q = normalize(query)
        // Buscando, procura em todos os canais.
        if (q.isNotEmpty()) return channels.filter { normalize(it.name).contains(q) }
        return when (val f = filter) {
            LiveFilter.All -> channels
            LiveFilter.Favorites -> channels.filter { it.url in favorites }
            is LiveFilter.Group -> byGroup[f.name].orEmpty()
        }
    }

    fun groupSize(name: String) = byGroup[name]?.size ?: 0

    val favoritesCount get() = channels.count { it.url in favorites }

    val filterTitle get() = when (val f = filter) {
        LiveFilter.All -> "Todos os canais"
        LiveFilter.Favorites -> "Favoritos"
        is LiveFilter.Group -> f.name
    }

    fun applyFilter(f: LiveFilter) {
        filter = f
        focusPlaying = true
    }

    fun programmes(channel: Channel) = epg.programmes(channel).ifEmpty { shortEpg[channel.url].orEmpty() }

    fun nowFor(channel: Channel) = programmes(channel).firstOrNull { now >= it.start && now < it.stop }

    fun nextFor(channel: Channel) = programmes(channel).firstOrNull { it.start >= now }

    // OK num canal novo toca na prévia; OK no que já está tocando abre a tela cheia.
    fun select(list: List<Channel>, index: Int) {
        if (list[index].url == playing?.url) fullscreen = true else setPlaying(list, index)
    }

    fun zap(delta: Int) {
        val index = playingList.indexOfFirst { it.url == playing?.url }
        if (index >= 0) setPlaying(playingList, index + delta)
    }

    fun toggleFavorite(channel: Channel) {
        favorites = if (channel.url in favorites) favorites - channel.url else favorites + channel.url
        store.favorites = favorites
    }

    // ---------- Seções, filmes e séries ----------

    fun openSection(s: Section) {
        screen = Screen.Watch
        focusPlaying = true
        if (section == s) return
        section = s
        vodCategory = null
        vodQuery = ""
        vodFocusId = null
        openSeries = null
        focusPlaying = true
        val xt = xtream ?: return
        when {
            s == Section.MOVIES && !moviesLoaded -> loadVod(Section.MOVIES) {
                val (cats, items) = xt.movies(force = false)
                movieCategories = cats
                movies = items
                moviesLoaded = true
            }
            s == Section.SERIES && !seriesLoaded -> loadVod(Section.SERIES) {
                val (cats, items) = xt.series(force = false)
                seriesCategories = cats
                seriesList = items
                seriesLoaded = true
            }
        }
    }

    // "Ver todos" numa fileira: abre a categoria em grade.
    fun openCategory(id: String) {
        vodCategory = id
        vodFocusId = null
        focusPlaying = true
    }

    fun vodCategoriesFor(s: Section) = if (s == Section.MOVIES) movieCategories else seriesCategories

    fun vodItems(): List<VodItem> {
        val q = normalize(vodQuery)
        val base = if (section == Section.MOVIES) movies else seriesList
        val inCategory = vodCategory?.let { id -> base.filter { it.categoryId == id } } ?: base
        return if (q.isEmpty()) inCategory else inCategory.filter { normalize(it.name).contains(q) }
    }

    fun vodCounts(): Map<String, Int> =
        (if (section == Section.MOVIES) movies else seriesList).groupingBy { it.categoryId }.eachCount()

    fun openVod(item: VodItem) {
        vodFocusId = item.id
        val url = item.url
        if (url != null) {
            startPlayback(listOf(Playable(item.name, item.year ?: item.genre, url, item.poster)), 0)
            return
        }
        val xt = xtream ?: return
        openSeries = item
        seriesInfo = null
        focusPlaying = true
        vodJob?.cancel()
        vodJob = viewModelScope.launch {
            vodLoading = true
            try {
                val info = xt.seriesInfo(item.id)
                seriesInfo = info
                season = info.seasons.firstOrNull { it > 0 } ?: info.seasons.firstOrNull() ?: 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Não consegui abrir a série: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                vodLoading = false
            }
        }
    }

    fun playEpisode(episodes: List<Episode>, index: Int) {
        val series = openSeries?.name
        val poster = openSeries?.poster
        val queue = episodes.map {
            Playable("T${it.season} E${it.number} · ${it.title.substringAfterLast(" - ")}", series, it.url, poster)
        }
        vodFocusId = episodes[index].id
        startPlayback(queue, index)
    }

    // ---------- Recentes / continuar assistindo ----------

    fun openRecent(progress: WatchProgress) {
        vodFocusId = progress.item.url
        startPlayback(listOf(progress.item), 0)
    }

    fun removeRecent(progress: WatchProgress) {
        updateRecents(recents.filter { it.item.url != progress.item.url })
    }

    fun resume(fromStart: Boolean) {
        val prompt = resumePrompt ?: return
        resumePrompt = null
        vodPlayback = VodPlayback(prompt.queue, prompt.index, if (fromStart) 0 else prompt.progress.position)
    }

    // Chamado pelo player a cada 15 s e ao sair. Abaixo de 15 s não vale guardar;
    // depois de 95% o filme conta como assistido e sai dos recentes.
    fun saveProgress(item: Playable, position: Long, duration: Long) {
        if (duration <= 0 || position < 15_000) return
        val rest = recents.filter { it.item.url != item.url }
        updateRecents(
            if (position >= duration * 0.95) rest
            else listOf(WatchProgress(item, position, duration, System.currentTimeMillis())) + rest,
        )
    }

    private fun startPlayback(queue: List<Playable>, index: Int) {
        val saved = recents.firstOrNull { it.item.url == queue[index].url }
        if (saved != null) resumePrompt = ResumePrompt(queue, index, saved) else vodPlayback = VodPlayback(queue, index)
    }

    private fun updateRecents(list: List<WatchProgress>) {
        val id = current?.id ?: return
        recents = list.take(50)
        store.saveRecents(id, recents)
    }

    fun nextVod(delta: Int) {
        val p = vodPlayback ?: return
        val i = p.index + delta
        if (i in p.queue.indices) vodPlayback = p.copy(index = i, startMs = 0)
    }

    // ---------- Listas ----------

    fun newPlaylist() {
        clearMessages()
        screen = Screen.Form(null)
    }

    fun edit(p: Playlist) {
        clearMessages()
        screen = Screen.Form(p)
    }

    fun openSettings() {
        clearMessages()
        screen = Screen.Settings
    }

    // Volta para o Início (hub). Sem lista aberta, o Início é a tela de listas.
    fun goHome() {
        fullscreen = false
        vodPlayback = null
        resumePrompt = null
        screen = if (current == null) Screen.Settings else Screen.Hub
    }

    // adb shell am start -n dev.neri.iptv/.MainActivity --es playlist_url "<url>" [--es name "<nome>"]
    fun addFromIntent(url: String, name: String?) =
        save(null, PlaylistForm(name.orEmpty(), url, "", "", autoUpdate = false))

    fun save(editing: Playlist?, form: PlaylistForm) {
        val url = form.url.trim()
        if (url.isEmpty()) {
            error = "Informe a URL da lista"
            return
        }
        val base = editing ?: Playlist(
            id = UUID.randomUUID().toString(),
            name = "",
            url = url,
            epgUrl = null,
            userAgent = null,
            autoUpdate = false,
            addedAt = System.currentTimeMillis(),
            updatedAt = 0,
            channelCount = 0,
        )
        val p = base.copy(
            name = form.name.trim().ifEmpty { nameFromUrl(url) },
            url = url,
            epgUrl = form.epgUrl.trim().ifEmpty { null },
            userAgent = form.userAgent.trim().ifEmpty { null },
            autoUpdate = form.autoUpdate,
        )
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            clearMessages()
            try {
                val download = editing == null || editing.url != p.url || editing.userAgent != p.userAgent
                val saved = if (download) {
                    store.xtream(p)?.let {
                        progress = "Conferindo a conta…"
                        it.checkAccount()
                        it.clearCache()
                    }
                    val count = loadLive(p, force = true, apply = false)
                    p.copy(channelCount = count, updatedAt = System.currentTimeMillis())
                } else {
                    p
                }
                upsert(saved)
                if (editing == null) {
                    current = null
                    open(saved)
                } else {
                    // Força recarregar na próxima vez que abrir.
                    if (current?.id == saved.id) current = null
                    screen = Screen.Settings
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Não consegui carregar a lista: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
                progress = null
            }
        }
    }

    fun refresh(p: Playlist, quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) {
                loading = true
                clearMessages()
            }
            try {
                val isCurrent = current?.id == p.id
                store.xtream(p)?.clearCache()
                val count = loadLive(p, force = true, apply = isCurrent)
                val updated = p.copy(channelCount = count, updatedAt = System.currentTimeMillis())
                upsert(updated)
                if (isCurrent) {
                    current = updated
                    loadEpg(updated, force = !quiet)
                    // Recarrega a seção de VOD aberta com os dados novos.
                    val open = section
                    val where = screen
                    if (open != Section.LIVE) {
                        section = Section.LIVE
                        openSection(open)
                        screen = where
                    }
                }
                if (!quiet) notice = "\"${p.name}\" atualizada: $count canais ao vivo"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!quiet) error = "Não consegui atualizar: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (!quiet) {
                    loading = false
                    progress = null
                }
            }
        }
    }

    fun delete(p: Playlist) {
        store.delete(p)
        playlists = playlists.filter { it.id != p.id }
        store.savePlaylists(playlists)
        if (store.lastPlaylistId == p.id) store.lastPlaylistId = null
        if (current?.id == p.id) {
            current = null
            channels = emptyList()
            playing = null
        }
    }

    fun open(p: Playlist) {
        if (current?.id == p.id && channels.isNotEmpty()) {
            enterHub()
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            clearMessages()
            try {
                val count = loadLive(p, force = false, apply = true)
                val latest = p.copy(channelCount = count)
                if (latest != p) upsert(latest)
                current = latest
                store.lastPlaylistId = latest.id
                recents = store.recents(latest.id)
                section = Section.LIVE
                val last = store.lastChannel(latest.id)
                val index = channels.indexOfFirst { it.url == last }
                if (index >= 0) setPlaying(channels, index) else playing = null
                enterHub()
                loadEpg(latest, force = false)
                if (latest.autoUpdate && latest.updatedAt > 0) refresh(latest, quiet = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Não consegui abrir a lista: ${e.message ?: e.javaClass.simpleName}"
                screen = Screen.Settings
            } finally {
                loading = false
                progress = null
            }
        }
    }

    fun back() {
        when {
            resumePrompt != null -> {
                resumePrompt = null
                focusPlaying = true
            }
            vodPlayback != null -> {
                vodPlayback = null
                focusPlaying = true
            }
            fullscreen -> {
                fullscreen = false
                focusPlaying = true
            }
            screen is Screen.Form -> screen = Screen.Settings
            screen == Screen.Settings -> if (current != null) screen = Screen.Hub
            openSeries != null -> {
                openSeries = null
                focusPlaying = true
            }
            section != Section.LIVE && vodQuery.isNotEmpty() -> vodQuery = ""
            section != Section.LIVE && vodCategory != null -> {
                vodFocusId = null
                vodCategory = null
                focusPlaying = true
            }
            section == Section.LIVE && query.isNotEmpty() -> query = ""
            else -> screen = Screen.Hub
        }
    }

    // ---------- Internos ----------

    // Carrega os canais ao vivo: pela API se for Xtream, senão pelo M3U (cache ou download).
    private suspend fun loadLive(p: Playlist, force: Boolean, apply: Boolean): Int {
        val xt = store.xtream(p)
        if (xt != null) {
            progress = "Baixando canais…"
            val live = xt.live(force)
            if (apply) {
                xtream = xt
                m3uEpgUrl = null
                resetVod()
                applyLive(live)
            }
            return live.size
        }
        val m3u = (if (force) null else store.loadCached(p))
            ?: store.download(p) { bytes -> progress = "Baixando lista… ${bytes / 1_000_000} MB" }
        if (apply) {
            xtream = null
            m3uEpgUrl = m3u.epgUrl
            resetVod()
            applyM3u(m3u)
        }
        return m3u.channels.count { it.kind == Kind.LIVE }
    }

    private fun applyLive(list: List<Channel>) {
        channels = list
        byGroup = list.groupBy { it.group }
        groups = byGroup.keys.toList()
        if (playingList.isNotEmpty()) playingList = channels
    }

    // Numa lista M3U comum, filmes e episódios soltos viram itens de VOD agrupados pelo group-title.
    private fun applyM3u(m3u: M3u) {
        applyLive(m3u.channels.filter { it.kind == Kind.LIVE })
        fun vod(kind: Kind): Pair<List<Category>, List<VodItem>> {
            val items = m3u.channels.filter { it.kind == kind }.map {
                VodItem(id = it.url, name = it.name, poster = it.logo, categoryId = it.group, url = it.url)
            }
            return items.map { it.categoryId }.distinct().map { Category(it, it) } to items
        }
        vod(Kind.MOVIE).let { (cats, items) -> movieCategories = cats; movies = items }
        vod(Kind.SERIES).let { (cats, items) -> seriesCategories = cats; seriesList = items }
        moviesLoaded = true
        seriesLoaded = true
    }

    private fun resetVod() {
        movieCategories = emptyList()
        movies = emptyList()
        seriesCategories = emptyList()
        seriesList = emptyList()
        moviesLoaded = false
        seriesLoaded = false
        openSeries = null
        seriesInfo = null
        shortEpg = emptyMap()
    }

    private fun loadVod(s: Section, block: suspend () -> Unit) {
        vodJob?.cancel()
        vodJob = viewModelScope.launch {
            vodLoading = true
            progress = if (s == Section.MOVIES) "Baixando filmes…" else "Baixando séries…"
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Não consegui carregar ${s.label.lowercase()}: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                vodLoading = false
                progress = null
            }
        }
    }

    private fun enterHub() {
        query = ""
        fullscreen = false
        screen = Screen.Hub
    }

    private fun setPlaying(list: List<Channel>, index: Int) {
        if (list.isEmpty()) return
        val i = index.mod(list.size)
        val channel = list[i]
        playingList = list
        playing = channel
        playingNumber = i + 1
        current?.let { store.setLastChannel(it.id, channel.url) }
        loadShortEpg(channel)
    }

    // Servidores Xtream sem xmltv.php: busca o guia do canal na hora em que ele toca.
    private fun loadShortEpg(channel: Channel) {
        val xt = xtream ?: return
        val id = channel.streamId ?: return
        if (!epg.isEmpty && epg.programmes(channel).isNotEmpty()) return
        val known = shortEpg[channel.url]
        if (known != null && known.any { it.stop > now }) return
        shortEpgJob?.cancel()
        shortEpgJob = viewModelScope.launch {
            runCatching { xt.shortEpg(id) }.onSuccess { shortEpg = shortEpg + (channel.url to it) }
        }
    }

    private fun loadEpg(p: Playlist, force: Boolean) {
        val url = p.epgUrl ?: m3uEpgUrl
        epgJob?.cancel()
        if (url == null) {
            epg = Epg.EMPTY
            return
        }
        epgJob = viewModelScope.launch {
            epgLoading = true
            try {
                epg = store.loadEpg(p, url, channels, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Não consegui carregar o guia: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                epgLoading = false
            }
        }
    }

    private fun upsert(p: Playlist) {
        playlists = if (playlists.any { it.id == p.id }) playlists.map { if (it.id == p.id) p else it } else playlists + p
        store.savePlaylists(playlists)
    }

    private fun clearMessages() {
        error = null
        notice = null
    }

    private fun nameFromUrl(url: String): String {
        val uri = Uri.parse(url)
        val segment = uri.lastPathSegment?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() && it != "get" && it != "player_api" }
        return segment ?: uri.host ?: "Lista"
    }
}
