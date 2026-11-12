package ir.lexilens.reader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TtsSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var selectedVoice: Voice? = null
    private var rate = 0.92f
    private val initLatch = CountDownLatch(1)
    private val synthLock = Any()

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) configure()
            initLatch.countDown()
        }
    }

    private fun configure() {
        val engine = tts ?: return
        runCatching { engine.setLanguage(Locale.US) }
        runCatching {
            val voices = engine.voices.orEmpty()
                .filter { it.locale.language.equals("en", ignoreCase = true) }
            selectedVoice = voices
                .sortedWith(
                    compareByDescending<Voice> { it.locale.country == "US" }
                        .thenByDescending { it.locale.country == "GB" }
                        .thenByDescending { !it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                )
                .firstOrNull()
            selectedVoice?.let { engine.voice = it }
        }
        runCatching { engine.setSpeechRate(rate) }
        runCatching { engine.setPitch(1.0f) }
    }

    private fun ensureReady(timeoutMs: Long = 8_000L): Boolean {
        if (!ready) initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ready && tts != null
    }

    fun speak(text: String, rate: Float = this.rate) {
        if (text.isBlank()) return
        this.rate = rate
        if (!ensureReady(3_000L)) return
        val engine = tts ?: return
        runCatching {
            engine.setSpeechRate(rate)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "lexilens-${System.nanoTime()}")
        }
    }

    /**
     * Synthesizes to a WAV file so the word is playable with zero network later.
     * Returns true only when a non-empty file was produced.
     */
    fun synthesize(text: String, target: File, rate: Float = this.rate): Boolean {
        if (text.isBlank()) return false
        this.rate = rate
        if (!ensureReady(10_000L)) return false
        val engine = tts ?: return false
        synchronized(synthLock) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.delete()
            val latch = CountDownLatch(1)
            var ok = false
            val id = "synth-${System.nanoTime()}"
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    ok = true
                    latch.countDown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    latch.countDown()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    latch.countDown()
                }
            }
            val previous = runCatching { engine.setOnUtteranceProgressListener(listener) }.isSuccess
            runCatching { engine.setSpeechRate(rate) }
            val result = runCatching {
                engine.synthesizeToFile(text, Bundle(), tmp, id)
            }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.SUCCESS) {
                latch.await(25, TimeUnit.SECONDS)
            }
            if (previous) runCatching {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
            }
            if (ok && tmp.exists() && tmp.length() > 100L) {
                if (target.exists()) target.delete()
                return tmp.renameTo(target) || runCatching {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                    true
                }.getOrDefault(false)
            }
            tmp.delete()
            return false
        }
    }

    fun available(): Boolean = ensureReady(4_000L)

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
