package ir.lexilens.reader.reader

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import ir.lexilens.reader.App
import ir.lexilens.reader.R
import ir.lexilens.reader.data.Card
import ir.lexilens.reader.data.CardComposer
import ir.lexilens.reader.data.DictionaryEntry
import ir.lexilens.reader.data.Document
import ir.lexilens.reader.data.Prefs
import ir.lexilens.reader.databinding.ActivityReaderBinding
import ir.lexilens.reader.pdf.PageRenderer
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.PdfTextExtractor
import ir.lexilens.reader.pdf.WordBox
import ir.lexilens.reader.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var app: App
    private lateinit var prefs: Prefs
    private var doc: Document? = null
    private var renderer: PageRenderer? = null
    private var textExtractor: PdfTextExtractor? = null
    private var pageText: PageText? = null
    private var pageTextPage = -1
    private var currentWord: WordBox? = null
    private var currentWordPage = -1
    private var activeSheet: WordLookupSheet? = null
    private val rendering = mutableSetOf<Int>()
    private val pendingWidths = HashMap<Int, Int>()
    private var textJob: Job? = null
    private var barsVisible = true
    private var seekGuard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        prefs = Prefs(this)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val docId = intent.getLongExtra(EXTRA_DOC_ID, 0L)
        if (docId <= 0L) {
            finish()
            return
        }
        val loaded = app.db.document(docId)
        if (loaded == null || !File(loaded.path).exists()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("کتاب پیدا نشد")
                .setMessage("فایل این کتاب در دسترس نیست.")
                .setPositiveButton("باشه") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }
        doc = loaded
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.docTitle.text = loaded.title
        binding.pageView.fitWidth = prefs.fitWidth

        try {
            val file = File(loaded.path)
            renderer = PageRenderer(file)
            textExtractor = PdfTextExtractor(this, loaded.id, file)
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this)
                .setTitle("PDF باز نشد")
                .setMessage(e.message ?: "فایل ممکن است رمزدار یا خراب باشد.")
                .setPositiveButton("باشه") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }

        val pageCount = renderer?.pageCount ?: 0
        binding.pageSeek.max = (pageCount - 1).coerceAtLeast(1)
        binding.pageView.listener = object : PdfPageView.Listener {
            override fun onPageChanged(index: Int) {
                updatePageUi(index)
                prefetchText(index)
            }

            override fun onNeedPage(index: Int, targetWidth: Int) = requestPage(index, targetWidth)
            override fun onLongPressPage(index: Int, nx: Float, ny: Float, viewX: Float, viewY: Float) {
                handleLongPress(nx, ny)
            }
            override fun onTap() = toggleBars()
            override fun onZoomChanged(zoomed: Boolean) {
                if (zoomed && barsVisible) toggleBars()
            }

            override fun onNeedTile(
                index: Int,
                pageLeft: Float,
                pageTop: Float,
                pageRight: Float,
                pageBottom: Float,
                outWidth: Int,
                outHeight: Int,
                token: Int,
            ) {
                val r = renderer ?: return
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching {
                            r.renderRegion(
                                index,
                                pageLeft,
                                pageTop,
                                pageRight,
                                pageBottom,
                                outWidth,
                                outHeight,
                            )
                        }.getOrNull()
                    }
                    if (isFinishing || isDestroyed) {
                        bmp?.recycle()
                        return@launch
                    }
                    if (bmp != null) {
                        binding.pageView.setPageTile(
                            index,
                            token,
                            pageLeft,
                            pageTop,
                            pageRight,
                            pageBottom,
                            bmp,
                        )
                    } else {
                        binding.pageView.tileFailed(index, token)
                    }
                }
            }
        }
        binding.pageView.setDocument(renderer!!, pageCount, loaded.lastPage)
        updatePageUi(binding.pageView.pageIndex)
        prefetchText(binding.pageView.pageIndex)

        binding.backButton.setOnClickListener { finish() }
        binding.fitButton.setOnClickListener {
            prefs.fitWidth = !prefs.fitWidth
            binding.pageView.fitWidth = prefs.fitWidth
            Snackbar.make(
                binding.root,
                if (prefs.fitWidth) "عرض کامل" else "تمام صفحه",
                Snackbar.LENGTH_SHORT
            ).show()
        }
        binding.prevButton.setOnClickListener {
            binding.pageView.goToPage(binding.pageView.pageIndex - 1)
        }
        binding.nextButton.setOnClickListener {
            binding.pageView.goToPage(binding.pageView.pageIndex + 1)
        }
        binding.pageSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !seekGuard) {
                    binding.pageView.goToPage(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.setPadding(binding.topBar.paddingLeft, bars.top + dp(6), binding.topBar.paddingRight, dp(6))
            binding.bottomBar.setPadding(binding.bottomBar.paddingLeft, dp(6), binding.bottomBar.paddingRight, bars.bottom + dp(10))
            insets
        }
    }

    private fun updatePageUi(index: Int) {
        val count = renderer?.pageCount ?: 0
        binding.pageCounter.text = "صفحهٔ ${TextUtils.faNumber(index + 1)} از ${TextUtils.faNumber(count)}"
        seekGuard = true
        binding.pageSeek.progress = index
        seekGuard = false
        binding.prevButton.alpha = if (index > 0) 1f else 0.35f
        binding.nextButton.alpha = if (index < count - 1) 1f else 0.35f
        doc?.let { app.db.updateLastPage(it.id, index) }
    }

    private fun requestPage(index: Int, targetWidth: Int) {
        val r = renderer ?: return
        if (index !in 0 until r.pageCount) return
        val target = targetWidth.coerceIn(200, 5000)
        if (!rendering.contains(index) && target <= binding.pageView.bitmapWidth(index)) return
        if (target > (pendingWidths[index] ?: 0)) pendingWidths[index] = target
        if (rendering.contains(index)) return
        rendering.add(index)
        lifecycleScope.launch {
            while (!isFinishing && !isDestroyed) {
                val want = pendingWidths[index] ?: 0
                if (want <= 0) break
                pendingWidths[index] = 0
                val bmp: Bitmap? = withContext(Dispatchers.IO) {
                    runCatching { r.renderFresh(index, want) }.getOrNull()
                }
                if (bmp != null && !isFinishing && !isDestroyed) {
                    binding.pageView.setPageBitmap(index, bmp)
                } else if (bmp != null) {
                    bmp.recycle()
                }
                val next = pendingWidths[index] ?: 0
                if (next <= binding.pageView.bitmapWidth(index)) break
            }
            rendering.remove(index)
        }
    }

    private fun prefetchText(index: Int) {
        textJob?.cancel()
        pageText = null
        pageTextPage = -1
        textJob = lifecycleScope.launch {
            val own = withContext(Dispatchers.IO) { textExtractor?.extract(index) }
            if (own != null && binding.pageView.pageIndex == index) {
                pageText = own
                pageTextPage = index
            }
            withContext(Dispatchers.IO) {
                listOf(index - 1, index + 1).filter { it >= 0 }.forEach { textExtractor?.extract(it) }
            }
        }
    }

    private fun handleLongPress(nx: Float, ny: Float) {
        val page = binding.pageView.pageIndex
        val text = pageText?.takeIf { pageTextPage == page }
        if (text != null) {
            performHit(text, nx, ny)
            return
        }
        val snack = Snackbar.make(binding.root, "در حال آماده‌سازی متن صفحه…", Snackbar.LENGTH_INDEFINITE)
        snack.show()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { textExtractor?.extract(page) }
            snack.dismiss()
            if (loaded == null || loaded.words.isEmpty()) {
                Snackbar.make(
                    binding.root,
                    "این صفحه متن قابل انتخاب ندارد؛ شاید PDF اسکن‌شده است.",
                    Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }
            pageText = loaded
            pageTextPage = page
            performHit(loaded, nx, ny)
        }
    }

    private fun performHit(text: PageText, nx: Float, ny: Float) {
        val (px, py) = text.mapFromPage(nx, ny)
        val hit = text.hit(px, py)
        if (hit == null) {
            Snackbar.make(binding.root, "واژه‌ای پیدا نشد؛ دقیق‌تر روی کلمه نگه دار.", Snackbar.LENGTH_SHORT).show()
            return
        }
        currentWord = hit.first
        currentWordPage = binding.pageView.pageIndex
        val sheet = WordLookupSheet(
            context = this,
            app = app,
            scope = lifecycleScope,
            pageText = text,
            wordBox = hit.first,
            docId = doc?.id ?: 0L,
            pageIndex = currentWordPage,
            onAdd = { translation, entry, existing ->
                addCard(hit.first, text, translation, entry, existing)
            },
        )
        activeSheet = sheet
        sheet.show()
    }

    private fun addCard(
        word: WordBox,
        text: PageText,
        translation: String,
        entry: DictionaryEntry?,
        existing: Card?,
    ) {
        val sheet = activeSheet
        if (existing != null) {
            val updated = existing.copy(translation = translation.trim())
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { app.db.updateCard(updated) }
                }
                sheet?.dismiss()
                Snackbar.make(
                    binding.root,
                    "معنی «${word.text}» به‌روز شد",
                    Snackbar.LENGTH_LONG,
                ).show()
            }
            return
        }
        val d = doc ?: return
        val r = renderer ?: return
        val page = binding.pageView.pageIndex
        lifecycleScope.launch {
            val built = withContext(Dispatchers.IO) {
                runCatching {
                    val card = CardComposer.build(
                        context = this@ReaderActivity,
                        app = app,
                        doc = d,
                        pageText = text,
                        pageIndex = page,
                        word = word,
                        entry = entry,
                        translationOverride = translation,
                        renderer = r,
                        ttsRate = prefs.ttsRate,
                    )
                    val audio = CardComposer.synthesizeAudio(app, card, prefs.ttsRate)
                    card.copy(audioPath = audio)
                }.getOrNull()
            }
            if (built == null) {
                sheet?.dismiss()
                Snackbar.make(binding.root, "ساخت کارت نشد؛ دوباره تلاش کن.", Snackbar.LENGTH_LONG).show()
                return@launch
            }
            val id = withContext(Dispatchers.IO) { app.db.insertCard(built) }
            sheet?.dismiss()
            if (id == -1L) {
                Snackbar.make(binding.root, "این واژه از قبل برای همین خط اضافه شده.", Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(
                    binding.root,
                    "«${word.text}» به مرور اضافه شد",
                    Snackbar.LENGTH_LONG
                ).setAction("دیدن کارت‌ها") {
                    startActivity(android.content.Intent(this@ReaderActivity, ir.lexilens.reader.ui.MainActivity::class.java))
                }.show()
            }
        }
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        if (barsVisible) {
            binding.topBar.animate().translationY(0f).alpha(1f).setDuration(180).start()
            binding.bottomBar.animate().translationY(0f).alpha(1f).setDuration(180).start()
        } else {
            binding.topBar.animate().translationY(-binding.topBar.height.toFloat()).alpha(0f).setDuration(180).start()
            binding.bottomBar.animate().translationY(binding.bottomBar.height.toFloat()).alpha(0f).setDuration(180).start()
        }
    }

    override fun onPause() {
        super.onPause()
        doc?.let { app.db.updateLastPage(it.id, binding.pageView.pageIndex) }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeSheet?.dismiss()
        textJob?.cancel()
        runCatching { textExtractor?.close() }
        runCatching { renderer?.close() }
        binding.pageView.release()
        textExtractor = null
        renderer = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DOC_ID = "doc_id"
    }
}
