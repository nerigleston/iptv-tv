package dev.neri.iptv

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val EPG_MAX_AGE = 12 * 60 * 60_000L

class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "playlists").apply { mkdirs() }

    fun playlists(): List<Playlist> {
        val array = JSONArray(prefs.getString("playlists", null) ?: return emptyList())
        return (0 until array.length()).map { array.getJSONObject(it).toPlaylist() }
    }

    fun savePlaylists(list: List<Playlist>) =
        prefs.edit { putString("playlists", JSONArray(list.map { it.toJson() }).toString()) }

    // Favoritos são globais, valem para todas as listas.
    var favorites: Set<String>
        get() = prefs.getStringSet("favorites", null)?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet("favorites", value) }

    var lastPlaylistId: String?
        get() = prefs.getString("last_playlist", null)
        set(value) = prefs.edit { putString("last_playlist", value) }

    fun lastChannel(playlistId: String) = prefs.getString("last_channel_$playlistId", null)

    fun setLastChannel(playlistId: String, url: String) = prefs.edit { putString("last_channel_$playlistId", url) }

    suspend fun loadCached(p: Playlist): M3u? = withContext(Dispatchers.IO) {
        m3uFile(p).takeIf { it.exists() }?.let(::readM3u)
    }

    // Recentes ficam por lista (as URLs dos filmes carregam o usuário/senha daquela conta).
    fun recents(playlistId: String): List<WatchProgress> {
        val array = JSONArray(prefs.getString("recent_$playlistId", null) ?: return emptyList())
        return (0 until array.length()).mapNotNull { runCatching { array.getJSONObject(it).toProgress() }.getOrNull() }
    }

    fun saveRecents(playlistId: String, list: List<WatchProgress>) =
        prefs.edit { putString("recent_$playlistId", JSONArray(list.map { it.toJson() }).toString()) }

    fun xtream(p: Playlist): Xtream? =
        xtreamAccount(p.url)?.let { Xtream(it, p.userAgent ?: USER_AGENT, File(dir, "${p.id}-xtream")) }

    suspend fun download(p: Playlist, onProgress: (Long) -> Unit = {}): M3u = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "${p.id}.m3u.tmp")
        fetch(p.url, p.userAgent, tmp, onProgress)
        val m3u = readM3u(tmp)
        if (m3u.channels.isEmpty()) {
            tmp.delete()
            error("a lista não tem nenhum canal")
        }
        tmp.copyTo(m3uFile(p), overwrite = true)
        tmp.delete()
        m3u
    }

    suspend fun loadEpg(p: Playlist, url: String, channels: List<Channel>, force: Boolean): Epg =
        withContext(Dispatchers.IO) {
            val file = File(dir, "${p.id}.epg")
            val stale = !file.exists() ||
                System.currentTimeMillis() - file.lastModified() > EPG_MAX_AGE ||
                prefs.getString("epg_url_${p.id}", null) != url
            if (force || stale) {
                val tmp = File(context.cacheDir, "${p.id}.epg.tmp")
                fetch(url, p.userAgent, tmp)
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
                prefs.edit { putString("epg_url_${p.id}", url) }
            }
            gunzipIfNeeded(file.inputStream()).use { parseXmltv(it, channels) }
        }

    fun delete(p: Playlist) {
        m3uFile(p).delete()
        File(dir, "${p.id}.epg").delete()
        File(dir, "${p.id}-xtream").deleteRecursively()
        prefs.edit {
            remove("last_channel_${p.id}")
            remove("epg_url_${p.id}")
            remove("recent_${p.id}")
        }
    }

    private fun m3uFile(p: Playlist) = File(dir, "${p.id}.m3u")

    private fun readM3u(file: File) = gunzipIfNeeded(file.inputStream()).bufferedReader().useLines(::parseM3u)

    private fun fetch(url: String, userAgent: String?, target: File, onProgress: (Long) -> Unit = {}) =
        openUrl(url, userAgent ?: USER_AGENT).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var reported = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    total += n
                    // Avisa a cada ~1 MB para a tela mostrar o progresso.
                    if (total - reported >= 1_000_000) {
                        reported = total
                        onProgress(total)
                    }
                }
            }
        }
}

private fun Playlist.toJson() = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("url", url)
    .put("epgUrl", epgUrl)
    .put("userAgent", userAgent)
    .put("autoUpdate", autoUpdate)
    .put("addedAt", addedAt)
    .put("updatedAt", updatedAt)
    .put("channelCount", channelCount)

private fun JSONObject.stringOrNull(key: String) = if (has(key) && !isNull(key)) getString(key) else null

private fun WatchProgress.toJson() = JSONObject()
    .put("title", item.title)
    .put("subtitle", item.subtitle)
    .put("url", item.url)
    .put("poster", item.poster)
    .put("position", position)
    .put("duration", duration)
    .put("updatedAt", updatedAt)

private fun JSONObject.toProgress() = WatchProgress(
    item = Playable(getString("title"), stringOrNull("subtitle"), getString("url"), stringOrNull("poster")),
    position = getLong("position"),
    duration = getLong("duration"),
    updatedAt = optLong("updatedAt"),
)

private fun JSONObject.toPlaylist() = Playlist(
    id = getString("id"),
    name = getString("name"),
    url = getString("url"),
    epgUrl = stringOrNull("epgUrl"),
    userAgent = stringOrNull("userAgent"),
    autoUpdate = optBoolean("autoUpdate"),
    addedAt = optLong("addedAt"),
    updatedAt = optLong("updatedAt"),
    channelCount = optInt("channelCount"),
)
