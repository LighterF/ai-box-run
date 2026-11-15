package ir.lexilens.reader.tts

import android.media.MediaPlayer
import java.io.File

class AudioPlayer {
    private var player: MediaPlayer? = null

    fun play(file: File): Boolean {
        if (!file.exists() || file.length() < 100L) return false
        stop()
        return runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ -> stop(); true }
            mp.prepare()
            mp.start()
            player = mp
            true
        }.getOrDefault(false)
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    fun release() = stop()
}
