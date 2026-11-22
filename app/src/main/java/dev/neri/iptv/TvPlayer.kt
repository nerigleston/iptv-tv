package dev.neri.iptv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

private fun mediaItemFor(url: String, mimeType: String? = null): MediaItem {
    val builder = MediaItem.Builder().setUri(url)
    // Sem a extensão .m3u8 no fim o ExoPlayer não reconhece HLS sozinho.
    builder.setMimeType(mimeType ?: MimeTypes.APPLICATION_M3U8.takeIf { url.contains("m3u8", ignoreCase = true) })
    return builder.build()
}

@OptIn(UnstableApi::class)
class TvPlayer(context: Context, userAgent: String) {
    var buffering by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // Volume do app (0–100), guardado entre sessões; vale para TV, filmes e séries.
    private val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    var volume by mutableStateOf(prefs.getInt("volume", 100))
        private set
    var muted by mutableStateOf(prefs.getBoolean("muted", false))
        private set

    // Pausado pelo usuário. No ao vivo, despausar reabre o canal no ao vivo.
    var paused by mutableStateOf(false)
        private set

    // Servidores que mandam MPEG-TS mesmo quando a URL pede .m3u8.
    private val tsHosts = mutableSetOf<String>()

    // Uma troca de formato por canal, para não ficar alternando entre HLS e TS.
    private var formatRetried = false

    // Vigia de travamento: o servidor às vezes para de mandar dados sem fechar a conexão.
    // Se ficar carregando por muito tempo, reabre o link no mesmo ponto (o que antes
    // só se conseguia saindo e entrando de novo no filme).
    private val handler = Handler(Looper.getMainLooper())
    private var reconnects = 0
    private val stallCheck = Runnable {
        if (exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady) {
            if (reconnects < MAX_RECONNECTS) reconnect()
            else error = "O servidor parou de responder"
        }
    }

    private fun watchStall() {
        handler.removeCallbacks(stallCheck)
        if (exo.playbackState == Player.STATE_BUFFERING && exo.playWhenReady) {
            handler.postDelayed(stallCheck, STALL_TIMEOUT_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) { reconnects = 0; error = null }
            watchStall()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = watchStall()

        override fun onPlayerError(e: PlaybackException) {
            handler.removeCallbacks(stallCheck)
            val item = exo.currentMediaItem
            when {
                // Live que ficou para trás: volta para o ao vivo.
                e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    exo.seekToDefaultPosition()
                    exo.prepare()
                }
                // Não era TS/MP4: tenta de novo como HLS uma vez.
                !formatRetried &&
                    e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
                    item?.localConfiguration != null &&
                    item.localConfiguration?.mimeType != MimeTypes.APPLICATION_M3U8 -> {
                    formatRetried = true
                    exo.setMediaItem(mediaItemFor(item.localConfiguration!!.uri.toString(), MimeTypes.APPLICATION_M3U8))
                    exo.prepare()
                }
                // Pediu .m3u8 mas veio MPEG-TS (sem #EXTM3U): toca como TS e lembra do servidor.
                !formatRetried &&
                    e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED &&
                    item?.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 -> {
                    formatRetried = true
                    val uri = item.localConfiguration!!.uri
                    uri.host?.let { tsHosts += it }
                    exo.setMediaItem(mediaItemFor(uri.toString(), MimeTypes.VIDEO_MP2T))
                    exo.prepare()
                }
                // Conexão caiu no meio: reconecta sozinho algumas vezes antes de mostrar o erro.
                reconnects < MAX_RECONNECTS && e.errorCode in NETWORK_ERRORS -> reconnect()
                else -> error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "O servidor recusou o canal"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Sem conexão com o servidor"
                    else -> "Não foi possível reproduzir (${e.errorCodeName})"
                }
            }
        }
    }

    val exo: ExoPlayer = run {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            // 10 s sem receber nada já indica conexão morta; com 30 s a tela ficava parada.
            .setReadTimeoutMs(10_000)
        // Buffer maior (2 min em vez de 50 s) para engasgos curtos do servidor passarem despercebidos.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 1_000, 2_000)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setLoadControl(loadControl)
            .build()
            .apply {
                addListener(listener)
                volume = if (muted) 0f else this@TvPlayer.volume / 100f
            }
    }

    val isLive get() = exo.isCurrentMediaItemLive || exo.duration == C.TIME_UNSET

    fun pause() {
        paused = true
        exo.pause()
    }

    /** Continua de onde parou; no ao vivo, volta para o ao vivo em vez de ficar atrasado. */
    fun unpause() {
        paused = false
        if (isLive && exo.mediaItemCount > 0) {
            reconnects = 0
            error = null
            exo.currentMediaItem?.let { exo.setMediaItem(it) }
            exo.prepare()
            exo.play()
        } else {
            resume()
        }
    }

    fun togglePause() = if (paused || !exo.isPlaying) unpause() else pause()

    fun updateVolume(value: Int) {
        volume = value.coerceIn(0, 100)
        if (volume > 0) muted = false
        applyVolume()
    }

    fun changeVolume(delta: Int) = updateVolume(volume + delta)

    fun toggleMute() {
        muted = !muted
        applyVolume()
    }

    private fun applyVolume() {
        exo.volume = if (muted) 0f else volume / 100f
        prefs.edit().putInt("volume", volume).putBoolean("muted", muted).apply()
    }

    fun play(url: String, startMs: Long = 0) {
        error = null
        paused = false
        formatRetried = false
        reconnects = 0
        val mimeType = MimeTypes.VIDEO_MP2T.takeIf { Uri.parse(url).host in tsHosts }
        exo.setMediaItem(mediaItemFor(url, mimeType), startMs)
        exo.prepare()
        exo.play()
    }

    fun retry() {
        error = null
        reconnects = 0
        reconnect()
    }

    /** Abre o link de novo com conexão nova: filme no ponto onde parou, ao vivo no ao vivo. */
    fun reconnect() {
        val item = exo.currentMediaItem ?: return
        reconnects++
        error = null
        val live = exo.isCurrentMediaItemLive || exo.duration == C.TIME_UNSET
        if (live) exo.setMediaItem(item) else exo.setMediaItem(item, exo.currentPosition)
        exo.prepare()
        exo.play()
    }

    fun resume() {
        if (exo.mediaItemCount == 0) return
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.play()
    }

    fun stop() {
        handler.removeCallbacks(stallCheck)
        exo.stop()
    }

    fun release() {
        handler.removeCallbacks(stallCheck)
        exo.removeListener(listener)
        exo.release()
    }

    private companion object {
        const val STALL_TIMEOUT_MS = 15_000L
        const val MAX_RECONNECTS = 3
        val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
    }
}
