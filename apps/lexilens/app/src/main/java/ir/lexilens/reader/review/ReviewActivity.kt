package ir.lexilens.reader.review

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import ir.lexilens.reader.App
import ir.lexilens.reader.R
import ir.lexilens.reader.data.Card
import ir.lexilens.reader.data.Prefs
import ir.lexilens.reader.data.Rating
import ir.lexilens.reader.data.Srs
import ir.lexilens.reader.databinding.ActivityReviewBinding
import ir.lexilens.reader.tts.AudioPlayer
import ir.lexilens.reader.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

class ReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewBinding
    private lateinit var app: App
    private lateinit var prefs: Prefs
    private val player = AudioPlayer()
    private val queue = mutableListOf<Card>()
    private var index = 0
    private var current: Card? = null
    private var revealed = false
    private var ratingLocked = false
    private var docId = 0L
    private var docTitle: String? = null
    private var lastBefore: Card? = null
    private var lastLogId = -1L
    private var lastIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        prefs = Prefs(this)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        docId = intent.getLongExtra(EXTRA_DOC_ID, 0L)
        docTitle = intent.getStringExtra(EXTRA_DOC_TITLE)
        binding.deckLabel.text = docTitle ?: "همهٔ کارت‌ها"

        binding.closeButton.setOnClickListener { finish() }
        binding.emptyCloseButton.setOnClickListener { finish() }
        binding.revealButton.setOnClickListener { reveal() }
        binding.playButton.setOnClickListener { playWord() }
        binding.playSentenceButton.setOnClickListener { speakSentence() }
        binding.btnAgain.setOnClickListener { rate(Rating.AGAIN) }
        binding.btnHard.setOnClickListener { rate(Rating.HARD) }
        binding.btnGood.setOnClickListener { rate(Rating.GOOD) }
        binding.btnEasy.setOnClickListener { rate(Rating.EASY) }

        loadQueue()
    }

    private fun loadQueue() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val stats = app.db.stats()
                val reviewLimit = (prefs.reviewsPerDay - stats.reviewedToday).coerceAtLeast(0)
                val newLimit = (prefs.newPerDay - stats.newToday).coerceAtLeast(0)
                val learning = app.db.dueLearning(now, 200)
                val reviews = app.db.dueReviews(now, min(reviewLimit, 500))
                val fresh = app.db.newCards(min(newLimit, 100))
                (learning + reviews + fresh)
                    .filter { docId <= 0L || it.docId == docId }
                    .distinctBy { it.id }
                    .take(500)
            }
            queue.clear()
            queue.addAll(loaded)
            index = 0
            if (queue.isEmpty()) {
                showEmpty()
            } else {
                binding.emptyState.visibility = View.GONE
                binding.cardScroll.visibility = View.VISIBLE
                showCard()
            }
        }
    }

    private fun showCard() {
        if (index >= queue.size) {
            showEmpty()
            return
        }
        val card = queue[index]
        current = card
        revealed = false
        ratingLocked = false
        binding.cardScroll.scrollTo(0, 0)
        binding.word.text = card.word
        val meta = listOf(card.ipa, card.pos).filter { it.isNotBlank() }.joinToString("  ·  ")
        binding.wordMeta.text = meta
        binding.wordMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE
        binding.frontHint.visibility = View.VISIBLE
        binding.revealButton.visibility = View.VISIBLE
        binding.answerSection.visibility = View.GONE
        binding.ratingBar.visibility = View.GONE
        binding.translation.text = TextUtils.translationLines(card.translation)
            .joinToString("\n")
            .ifBlank { "برای این کارت معنی ثبت نشده؛ از بخش کارت‌ها ویرایشش کن." }
        binding.noteText.text = if (card.note.isBlank()) "" else "یادداشت: ${card.note}"
        binding.noteText.visibility = if (card.note.isBlank()) View.GONE else View.VISIBLE
        binding.pageBadge.text = "صفحهٔ ${TextUtils.faNumber(card.page + 1)}"
        binding.sourceLine.text = card.sourceLine.ifBlank { card.sentence }
        if (card.cropPath.isNotBlank() && File(card.cropPath).exists()) {
            binding.contextView.setCrop(card.cropPath, card.rectL, card.rectT, card.rectR, card.rectB, card.word)
        } else {
            binding.contextView.setText(card.sourceLine.ifBlank { card.sentence }, card.word)
        }
        binding.progressText.text = "کارت ${TextUtils.faNumber(index + 1)} از ${TextUtils.faNumber(queue.size)}"
        binding.sessionProgress.max = queue.size
        binding.sessionProgress.progress = index
        binding.lblAgain.text = Srs.preview(card, Rating.AGAIN)
        binding.lblHard.text = Srs.preview(card, Rating.HARD)
        binding.lblGood.text = Srs.preview(card, Rating.GOOD)
        binding.lblEasy.text = Srs.preview(card, Rating.EASY)
        if (prefs.autoPlay) playWord()
    }

    private fun reveal() {
        if (revealed) return
        revealed = true
        binding.frontHint.visibility = View.GONE
        binding.revealButton.visibility = View.GONE
        binding.answerSection.visibility = View.VISIBLE
        binding.ratingBar.visibility = View.VISIBLE
        binding.answerSection.alpha = 0f
        binding.answerSection.translationY = dp(14f)
        binding.answerSection.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun rate(rating: Rating) {
        if (!revealed || ratingLocked) return
        val card = current ?: return
        ratingLocked = true
        val before = card
        val outcome = Srs.grade(card, rating)
        lastBefore = before
        lastIndex = index
        lifecycleScope.launch {
            lastLogId = withContext(Dispatchers.IO) { app.db.recordReview(card, rating.ordinal, outcome) }
            index++
            if (index >= queue.size) showEmpty() else showCard()
            showUndo()
        }
    }

    private fun showUndo() {
        val logId = lastLogId
        val before = lastBefore ?: return
        binding.root.let { root ->
            Snackbar.make(root, "ثبت شد", Snackbar.LENGTH_LONG)
                .setAction("واگرد") {
                    if (logId <= 0L) return@setAction
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { app.db.undoReview(before, logId) }
                        index = lastIndex.coerceAtLeast(0)
                        lastLogId = -1L
                        binding.emptyState.visibility = View.GONE
                        binding.cardScroll.visibility = View.VISIBLE
                        showCard()
                    }
                }
                .show()
        }
    }

    private fun showEmpty() {
        binding.ratingBar.visibility = View.GONE
        binding.answerSection.visibility = View.GONE
        binding.revealButton.visibility = View.GONE
        binding.frontHint.visibility = View.GONE
        binding.cardScroll.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.sessionProgress.progress = binding.sessionProgress.max
        binding.emptyDetail.text = "کارت جدیدی هم در صف نیست. در تنظیمات می‌توانی سهمیهٔ روزانه را تغییر دهی."
        lifecycleScope.launch {
            val upcoming = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                app.db.allCards(docId = docId)
                    .filter { !it.suspended && it.dueAt > now }
                    .minByOrNull { it.dueAt }
            }
            if (!isFinishing && upcoming != null) {
                binding.emptyDetail.text = "مرور بعدی: ${TextUtils.dueLabel(upcoming)}"
            }
        }
    }

    private fun playWord() {
        val card = current ?: return
        val f = if (card.audioPath.isNotBlank()) File(card.audioPath) else null
        if (f == null || !player.play(f)) {
            if (!app.tts.available()) {
                Toast.makeText(this, "موتور تلفظ آفلاین روی این دستگاه پیدا نشد.", Toast.LENGTH_SHORT).show()
            } else {
                app.tts.speak(card.word, prefs.ttsRate)
            }
        }
    }

    private fun speakSentence() {
        val card = current ?: return
        app.tts.speak(card.sentence.ifBlank { card.sourceLine }, prefs.ttsRate)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        const val EXTRA_DOC_ID = "doc_id"
        const val EXTRA_DOC_TITLE = "doc_title"
    }
}
