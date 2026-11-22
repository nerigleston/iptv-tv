package dev.neri.iptv

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URLEncoder

data class XtreamAccount(val base: String, val username: String, val password: String, val liveExtension: String)

// Reconhece URLs de servidor Xtream Codes (get.php ou player_api.php com usuário e senha).
fun xtreamAccount(url: String): XtreamAccount? {
    val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return null
    val path = uri.path.orEmpty()
    if (!path.endsWith("get.php") && !path.endsWith("player_api.php")) return null
    val username = uri.getQueryParameter("username") ?: return null
    val password = uri.getQueryParameter("password") ?: return null
    val extension = if (uri.getQueryParameter("output") == "ts") "ts" else "m3u8"
    return XtreamAccount("${uri.scheme}://${uri.encodedAuthority}", username, password, extension)
}

/**
 * Cliente da API do Xtream Codes (player_api.php). As respostas grandes ficam em cache
 * em disco e são lidas em streaming, porque a lista de filmes passa de 7 MB.
 */
class Xtream(
    private val account: XtreamAccount,
    private val userAgent: String,
    private val dir: File,
) {
    private val user = URLEncoder.encode(account.username, "UTF-8")
    private val pass = URLEncoder.encode(account.password, "UTF-8")
    private val api = "${account.base}/player_api.php?username=$user&password=$pass"

    suspend fun checkAccount() = withContext(Dispatchers.IO) {
        val json = openUrl(api, userAgent).use { it.readBytes().decodeToString() }
        val info = runCatching { JSONObject(json).getJSONObject("user_info") }.getOrNull()
            ?: error("o servidor não respondeu como Xtream")
        if (info.optInt("auth") != 1) error("usuário ou senha recusados")
        info.optString("status").takeIf { it.isNotEmpty() && it != "Active" }?.let { error("conta $it") }
    }

    suspend fun live(force: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        val categories = categories("get_live_categories", force)
        readArray(cached("get_live_streams", force)) { o ->
            val id = o["stream_id"] ?: return@readArray null
            Channel(
                name = o["name"]?.trim().orEmpty(),
                url = "${account.base}/live/$user/$pass/$id.${account.liveExtension}",
                group = categories[o["category_id"]] ?: NO_GROUP,
                logo = o["stream_icon"]?.ifBlank { null },
                tvgId = o["epg_channel_id"]?.ifBlank { null },
                tvgName = null,
                streamId = id,
            )
        }
    }

    suspend fun movies(force: Boolean): Pair<List<Category>, List<VodItem>> = withContext(Dispatchers.IO) {
        val categories = categoryList("get_vod_categories", force)
        categories to readArray(cached("get_vod_streams", force)) { o ->
            val id = o["stream_id"] ?: return@readArray null
            VodItem(
                id = id,
                name = o["name"]?.trim().orEmpty(),
                poster = o["stream_icon"]?.ifBlank { null },
                categoryId = o["category_id"].orEmpty(),
                rating = formatRating(o["rating"]),
                url = "${account.base}/movie/$user/$pass/$id.${o["container_extension"] ?: "mp4"}",
            )
        }
    }

    suspend fun series(force: Boolean): Pair<List<Category>, List<VodItem>> = withContext(Dispatchers.IO) {
        val categories = categoryList("get_series_categories", force)
        categories to readArray(cached("get_series", force)) { o ->
            val id = o["series_id"] ?: return@readArray null
            VodItem(
                id = id,
                name = o["name"]?.trim().orEmpty(),
                poster = o["cover"]?.ifBlank { null },
                categoryId = o["category_id"].orEmpty(),
                rating = formatRating(o["rating"]),
                year = o["releaseDate"]?.take(4)?.ifBlank { null },
                plot = o["plot"]?.ifBlank { null },
                genre = o["genre"]?.ifBlank { null },
            )
        }
    }

    suspend fun seriesInfo(seriesId: String): SeriesInfo = withContext(Dispatchers.IO) {
        val json = openUrl("$api&action=get_series_info&series_id=$seriesId", userAgent).use { it.readBytes().decodeToString() }
        val root = JSONObject(json)
        val episodes = HashMap<Int, MutableList<Episode>>()
        fun add(array: JSONArray) {
            for (i in 0 until array.length()) {
                val e = array.optJSONObject(i) ?: continue
                val id = e.optString("id")
                if (id.isEmpty()) continue
                val info = e.optJSONObject("info")
                val season = e.optString("season").toIntOrNull() ?: 0
                episodes.getOrPut(season) { ArrayList() } += Episode(
                    id = id,
                    season = season,
                    number = e.optString("episode_num").toIntOrNull() ?: (i + 1),
                    title = e.optString("title").ifEmpty { "Episódio ${i + 1}" },
                    url = "${account.base}/series/$user/$pass/$id.${e.optString("container_extension").ifEmpty { "mp4" }}",
                    plot = info?.optString("plot")?.ifBlank { null },
                    duration = info?.optString("duration")?.ifBlank { null },
                )
            }
        }
        // "episodes" costuma ser { "1": [...], "2": [...] }, mas alguns servidores mandam uma lista de listas.
        when (val raw = root.opt("episodes")) {
            is JSONObject -> raw.keys().forEach { key -> raw.optJSONArray(key)?.let(::add) }
            is JSONArray -> for (i in 0 until raw.length()) raw.optJSONArray(i)?.let(::add)
        }
        val sorted = episodes.mapValues { (_, list) -> list.sortedBy { it.number } }
        SeriesInfo(sorted.keys.sorted(), sorted)
    }

    // EPG curto de um canal. Alguns painéis devolvem o JSON inteiro dentro de uma string base64.
    suspend fun shortEpg(streamId: String): List<Programme> = withContext(Dispatchers.IO) {
        val text = openUrl("$api&action=get_short_epg&stream_id=$streamId&limit=30", userAgent)
            .use { it.readBytes().decodeToString() }.trim()
        val root = when (val value = JSONTokener(text).nextValue()) {
            is JSONObject -> value
            is String -> JSONObject(decodeBase64(value))
            else -> return@withContext emptyList()
        }
        val listings = root.optJSONArray("epg_listings") ?: return@withContext emptyList()
        (0 until listings.length()).mapNotNull { i ->
            val l = listings.optJSONObject(i) ?: return@mapNotNull null
            val start = l.optString("start_timestamp").toLongOrNull() ?: return@mapNotNull null
            val stop = l.optString("stop_timestamp").toLongOrNull() ?: return@mapNotNull null
            Programme(
                start = start * 1000,
                stop = stop * 1000,
                title = decodeBase64(l.optString("title")).ifBlank { "Sem título" },
                description = decodeBase64(l.optString("description")).ifBlank { null },
            )
        }.sortedBy { it.start }
    }

    fun clearCache() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun cached(action: String, force: Boolean): File {
        dir.mkdirs()
        val file = File(dir, "$action.json")
        if (force || !file.exists() || file.length() == 0L) {
            val tmp = File(dir, "$action.tmp")
            openUrl("$api&action=$action", userAgent).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
        }
        return file
    }

    private fun categoryList(action: String, force: Boolean): List<Category> =
        readArray(cached(action, force)) { o ->
            val id = o["category_id"] ?: return@readArray null
            Category(id, o["category_name"]?.trim().orEmpty())
        }

    private fun categories(action: String, force: Boolean) = categoryList(action, force).associate { it.id to it.name }
}

// "5.667" -> "5.7"; "8" continua "8".
private fun formatRating(value: String?): String? {
    val number = value?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    return if (number % 1.0 == 0.0) number.toInt().toString() else "%.1f".format(java.util.Locale.US, number)
}

private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

private fun decodeBase64(value: String): String {
    if (value.isEmpty() || value.length % 4 != 0 || !BASE64.matches(value)) return value
    return runCatching { Base64.decode(value, Base64.DEFAULT).decodeToString() }.getOrDefault(value)
}

// Lê um array JSON de objetos "planos" sem carregar tudo na memória.
private fun <T> readArray(file: File, item: (Map<String, String?>) -> T?): List<T> {
    JsonReader(file.bufferedReader()).use { reader ->
        // Sem conteúdo, alguns servidores mandam {} ou [] — só o array interessa.
        if (reader.peek() != JsonToken.BEGIN_ARRAY) return emptyList()
        val out = ArrayList<T>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            item(readFlatObject(reader))?.let(out::add)
        }
        reader.endArray()
        return out
    }
}

private fun readFlatObject(reader: JsonReader): Map<String, String?> {
    val map = HashMap<String, String?>()
    reader.beginObject()
    while (reader.hasNext()) {
        val name = reader.nextName()
        map[name] = when (reader.peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            JsonToken.NULL -> reader.nextNull().let { null }
            else -> reader.skipValue().let { null }
        }
    }
    reader.endObject()
    return map
}
