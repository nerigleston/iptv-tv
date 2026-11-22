package dev.neri.iptv

import java.text.Normalizer

enum class Kind { LIVE, MOVIE, SERIES }

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val tvgName: String?,
    val kind: Kind = Kind.LIVE,
    // Só em listas Xtream: usado para buscar o EPG do canal na API.
    val streamId: String? = null,
)

data class Category(val id: String, val name: String)

// Filme, série ou episódio solto de uma lista M3U. Sem url = série Xtream (episódios vêm da API).
data class VodItem(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val rating: String? = null,
    val url: String? = null,
    val year: String? = null,
    val plot: String? = null,
    val genre: String? = null,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val plot: String?,
    val duration: String?,
)

data class SeriesInfo(val seasons: List<Int>, val episodes: Map<Int, List<Episode>>)

// O que está tocando no player de filmes/séries; a fila permite ir ao próximo episódio.
// startMs vale só para o primeiro item aberto (continuar de onde parou).
data class VodPlayback(val queue: List<Playable>, val index: Int, val startMs: Long = 0) {
    val current get() = queue[index]
}

data class Playable(val title: String, val subtitle: String?, val url: String, val poster: String? = null)

// Ponto em que o usuário parou um filme ou episódio (aba Recentes).
data class WatchProgress(
    val item: Playable,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
)

// Pergunta "continuar ou começar do início" antes de abrir o player.
data class ResumePrompt(val queue: List<Playable>, val index: Int, val progress: WatchProgress)

data class Playlist(
    val id: String,
    val name: String,
    val url: String,
    val epgUrl: String?,
    val userAgent: String?,
    val autoUpdate: Boolean,
    val addedAt: Long,
    val updatedAt: Long,
    val channelCount: Int,
)

data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

private val MARKS = Regex("\\p{Mn}+")

// Usado na busca e para casar canais da lista com os do EPG.
fun normalize(text: String): String =
    MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
        .lowercase()
        .filter { it.isLetterOrDigit() }
