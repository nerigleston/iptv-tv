package dev.neri.iptv

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream

// Muitos servidores IPTV só respondem para players conhecidos.
const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

// HttpURLConnection não segue redirecionamento de http para https sozinho.
fun openUrl(url: String, userAgent: String): InputStream {
    var current = URL(url)
    repeat(5) {
        val conn = connect(current, userAgent)
        val code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            current = URL(current, location)
            return@repeat
        }
        if (code !in 200..299) {
            conn.disconnect()
            error("o servidor respondeu HTTP $code")
        }
        return conn.inputStream
    }
    error("redirecionamentos demais")
}

// O servidor às vezes derruba a conexão antes de responder ("unexpected end of stream"):
// painel sobrecarregado ou conexão velha do pool. Nada foi lido ainda, então dá para
// tentar de novo com segurança, sempre com uma conexão nova.
private fun connect(url: URL, userAgent: String): HttpURLConnection {
    var attempt = 0
    while (true) {
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", userAgent)
        // Servidores IPTV fecham keep-alive sem avisar; não reaproveita conexões.
        conn.setRequestProperty("Connection", "close")
        try {
            conn.responseCode
            return conn
        } catch (e: UnknownHostException) {
            throw e
        } catch (e: IOException) {
            conn.disconnect()
            if (++attempt >= 3) throw IOException("o servidor fechou a conexão sem responder", e)
            Thread.sleep(1_000L * attempt)
        }
    }
}

// Guias EPG costumam vir em .xml.gz; detecta pelo cabeçalho, não pela extensão.
fun gunzipIfNeeded(input: InputStream): InputStream {
    val buffered = BufferedInputStream(input)
    buffered.mark(2)
    val b1 = buffered.read()
    val b2 = buffered.read()
    buffered.reset()
    return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
}
