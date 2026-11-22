package dev.neri.iptv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class Epg(
    private val byChannel: Map<String, List<Programme>>,
    private val idByName: Map<String, String>,
) {
    val isEmpty get() = byChannel.isEmpty()

    // Casa pelo tvg-id; sem ele, pelo nome normalizado do canal.
    fun programmes(channel: Channel): List<Programme> {
        channel.tvgId?.lowercase()?.let { id -> byChannel[id]?.let { return it } }
        val id = channel.tvgName?.let { idByName[normalize(it)] }
            ?: idByName[normalize(channel.name)]
            ?: return emptyList()
        return byChannel[id].orEmpty()
    }

    fun now(channel: Channel, at: Long) = programmes(channel).firstOrNull { at >= it.start && at < it.stop }

    fun next(channel: Channel, at: Long) = programmes(channel).firstOrNull { it.start >= at }

    companion object {
        val EMPTY = Epg(emptyMap(), emptyMap())
    }
}

private val XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

// Formato XMLTV: "20260419083000 +0000"; sem fuso, assume UTC.
private fun parseXmltvTime(value: String?): Long? = runCatching {
    val text = value?.trim() ?: return null
    val local = LocalDateTime.parse(text.take(14).padEnd(14, '0'), XMLTV_TIME)
    val offset = text.drop(14).trim().takeIf { it.isNotEmpty() }?.let(ZoneOffset::of) ?: ZoneOffset.UTC
    local.toInstant(offset).toEpochMilli()
}.getOrNull()

private inline fun XmlPullParser.forEachChild(block: (String) -> Unit) {
    val parentDepth = depth
    while (true) {
        val event = next()
        if (event == XmlPullParser.END_DOCUMENT) return
        if (event == XmlPullParser.END_TAG && depth == parentDepth) return
        if (event == XmlPullParser.START_TAG && depth == parentDepth + 1) block(name)
    }
}

// Lê o XMLTV em streaming e guarda só os canais da lista, de ontem até daqui a 7 dias,
// para caber na memória da TV mesmo com guias enormes.
fun parseXmltv(input: InputStream, channels: List<Channel>): Epg {
    val wantedIds = channels.mapNotNullTo(HashSet()) { it.tvgId?.lowercase() }
    val wantedNames = channels.flatMapTo(HashSet()) { listOfNotNull(it.tvgName, it.name).map(::normalize) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val from = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val to = today.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()

    val keep = HashSet<String>()
    val idByName = HashMap<String, String>()
    val byChannel = HashMap<String, MutableList<Programme>>()

    val parser = Xml.newPullParser()
    parser.setInput(input, null)
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType != XmlPullParser.START_TAG) continue
        when (parser.name) {
            "channel" -> {
                val id = parser.getAttributeValue(null, "id")?.lowercase() ?: continue
                val names = ArrayList<String>()
                parser.forEachChild { if (it == "display-name") names += normalize(parser.nextText()) }
                val matchedNames = names.filter { it in wantedNames }
                matchedNames.forEach { idByName.putIfAbsent(it, id) }
                if (id in wantedIds || matchedNames.isNotEmpty()) keep += id
            }
            "programme" -> {
                val id = parser.getAttributeValue(null, "channel")?.lowercase()
                if (id == null || id !in keep) continue
                val start = parseXmltvTime(parser.getAttributeValue(null, "start")) ?: continue
                val stop = parseXmltvTime(parser.getAttributeValue(null, "stop")) ?: (start + 30 * 60_000)
                if (stop < from || start > to) continue
                var title = ""
                var description: String? = null
                parser.forEachChild {
                    when (it) {
                        "title" -> if (title.isEmpty()) title = parser.nextText().trim()
                        "desc" -> if (description == null) description = parser.nextText().trim()
                    }
                }
                byChannel.getOrPut(id) { ArrayList() } +=
                    Programme(start, stop, title.ifEmpty { "Sem título" }, description?.ifEmpty { null })
            }
        }
    }
    return Epg(byChannel.mapValues { (_, list) -> list.sortedBy { it.start } }, idByName)
}
