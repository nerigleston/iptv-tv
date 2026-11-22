package dev.neri.iptv

const val NO_GROUP = "Sem grupo"

data class M3u(val channels: List<Channel>, val epgUrl: String?)

private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

private fun attributes(line: String) =
    ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

fun parseM3u(lines: Sequence<String>): M3u {
    val channels = ArrayList<Channel>()
    var epgUrl: String? = null
    var info: String? = null
    var extGroup: String? = null
    for (raw in lines) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                val attrs = attributes(line)
                // Pode vir mais de uma URL separada por vírgula; usamos a primeira.
                epgUrl = (attrs["url-tvg"] ?: attrs["x-tvg-url"])
                    ?.split(',')?.firstOrNull()?.trim()?.ifEmpty { null }
            }
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                info = line
                extGroup = null
            }
            line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
            line.startsWith("#") -> Unit
            else -> {
                channels += toChannel(info, extGroup, line)
                info = null
                extGroup = null
            }
        }
    }
    return M3u(channels, epgUrl)
}

private fun toChannel(info: String?, extGroup: String?, url: String): Channel {
    if (info == null) return Channel(url, url, extGroup ?: NO_GROUP, null, null, null)
    val matches = ATTR.findAll(info).toList()
    val attrs = matches.associate { it.groupValues[1].lowercase() to it.groupValues[2] }
    // O nome vem depois da primeira vírgula que sucede os atributos.
    val tail = info.substring(matches.lastOrNull()?.range?.last?.plus(1) ?: 0)
    val name = tail.substringAfter(',', "").trim()
        .ifEmpty { attrs["tvg-name"].orEmpty() }
        .ifEmpty { url }
    return Channel(
        name = name,
        url = url,
        group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup ?: NO_GROUP,
        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
        tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
        kind = kindOf(url, name),
    )
}

private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v")
private val EPISODE = Regex("""\bS\d{1,2}\s?E\d{1,3}\b""", RegexOption.IGNORE_CASE)

// Servidores Xtream usam /movie/ e /series/ na URL; nas outras listas, a extensão
// de arquivo indica VOD e o padrão S01E02 no nome indica episódio.
private fun kindOf(url: String, name: String): Kind {
    val path = url.substringBefore('?').lowercase()
    return when {
        "/series/" in path -> Kind.SERIES
        "/movie/" in path -> Kind.MOVIE
        path.substringAfterLast('.', "") in VOD_EXTENSIONS ->
            if (EPISODE.containsMatchIn(name)) Kind.SERIES else Kind.MOVIE
        else -> Kind.LIVE
    }
}
