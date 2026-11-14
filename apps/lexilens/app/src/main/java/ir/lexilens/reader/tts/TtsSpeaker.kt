package ir.lexilens.reader.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import ir.lexilens.reader.util.SpeechChunker
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TtsSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false
    private var selectedVoice: Voice? = null
    private var rate = 0.92f
    private val initLatch = CountDownLatch(1)
    private val synthLock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var chunks: List<SpeechChunker.Chunk> = emptyList()
    private var generation = 0
    private var currentIndex = -1

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = advance(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = advance(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = advance(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) {}
    }

    init {
        val google = "com.google.android.tts"
        create(if (installed(google)) google else null, fallback = installed(google))
    }

    private fun installed(pkg: String): Boolean = runCatching {
        appContext.packageManager
            .queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
            .any { it.serviceInfo?.packageName == pkg }
    }.getOrDefault(false)

    private fun create(enginePackage: String?, fallback: Boolean) {
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                configure()
                initLatch.countDown()
            } else if (fallback && enginePackage != null) {
                runCatching { tts?.shutdown() }
                tts = null
                create(null, fallback = false)
            } else {
                ready = false
                initLatch.countDown()
            }
        }
        val created = runCatching {
            if (enginePackage != null) {
                TextToSpeech(appContext, listener, enginePackage)
            } else {
                TextToSpeech(appContext, listener)
            }
        }.getOrNull()
        if (created == null) {
            ready = false
            if (fallback && enginePackage != null) {
                create(null, fallback = false)
            } else {
                initLatch.countDown()
            }
        } else {
            tts = created
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
        runCatching { engine.setOnUtteranceProgressListener(progressListener) }
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
        handler.post {
            if (tts !== engine) return@post
            generation++
            val gen = generation
            chunks = SpeechChunker.chunks(text)
            currentIndex = -1
            runCatching { engine.setSpeechRate(rate) }
            runCatching { engine.stop() }
            speakNext(gen, 0)
        }
    }

    private fun speakNext(gen: Int, index: Int) {
        if (gen != generation) return
        if (index !in chunks.indices) return
        val engine = tts ?: return
        currentIndex = index
        val chunk = chunks[index]
        val id = "lexilens-$gen-$index"
        val result = runCatching {
            engine.speak(chunk.text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            handler.postDelayed({ speakNext(gen, index + 1) }, 40L)
        }
    }

    private fun advance(utteranceId: String?) {
        val parts = parseId(utteranceId) ?: return
        val gen = parts.first
        val index = parts.second
        handler.post {
            if (gen != generation || index != currentIndex) return@post
            val next = index + 1
            if (next >= chunks.size) return@post
            val pause = chunks[index].pauseAfterMs.coerceAtLeast(0L)
            if (pause > 0L) {
                handler.postDelayed({ speakNext(gen, next) }, pause)
            } else {
                speakNext(gen, next)
            }
        }
    }

    private fun parseId(utteranceId: String?): Pair<Int, Int>? {
        if (utteranceId == null || !utteranceId.startsWith("lexilens-")) return null
        val rest = utteranceId.substring("lexilens-".length)
        val parts = rest.split('-')
        if (parts.size != 2) return null
        val gen = parts[0].toIntOrNull() ?: return null
        val index = parts[1].toIntOrNull() ?: return null
        return gen to index
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
                engine.setOnUtteranceProgressListener(progressListener)
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
        generation++
        chunks = emptyList()
        currentIndex = -1
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
