package ir.lexilens.reader.reader

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import ir.lexilens.reader.App
import ir.lexilens.reader.data.DictionaryEntry
import ir.lexilens.reader.data.DictionaryRepository
import ir.lexilens.reader.databinding.SheetWordBinding
import ir.lexilens.reader.pdf.PageText
import ir.lexilens.reader.pdf.WordBox
import ir.lexilens.reader.util.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WordLookupSheet(
    private val context: Context,
    private val app: App,
    private val scope: CoroutineScope,
    private val pageText: PageText,
    private val wordBox: WordBox,
    private val onAdd: (String, DictionaryEntry?) -> Unit,
) {
    private val dialog = BottomSheetDialog(context)
    private val binding = SheetWordBinding.inflate(LayoutInflater.from(context))
    private var entry: DictionaryEntry? = null
    private var lookupJob: Job? = null

    init {
        dialog.setContentView(binding.root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { lookupJob?.cancel() }

        binding.word.text = wordBox.text
        binding.sourceLine.text = highlightedLine()
        binding.translation.isEnabled = false
        binding.addButton.isEnabled = false

        binding.speakButton.setOnClickListener {
            app.tts.speak(TextUtils.normalizeWord(wordBox.text).ifBlank { wordBox.text })
        }
        binding.addButton.setOnClickListener { add() }

        lookup()
    }

    private fun lookup() {
        lookupJob = scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    DictionaryRepository.get(context).ensureReady()
                    DictionaryRepository.get(context).lookup(wordBox.text)
                }.getOrNull()
            }
            entry = found
            binding.loadingRow.visibility = android.view.View.GONE
            binding.translation.isEnabled = true
            binding.addButton.isEnabled = true
            if (found == null) {
                binding.translation.hint = "معنی پیدا نشد؛ می‌توانی خودت بنویسی."
                binding.dictSource.text = "در واژه‌نامه پیدا نشد."
                binding.dictSource.visibility = android.view.View.VISIBLE
                return@launch
            }
            val translation = found.translations.take(6).joinToString("\n")
            binding.translation.setText(translation)
            if (translation.isBlank()) {
                binding.translation.hint = "واژه‌نامه معنی فارسی نداشت؛ خودت بنویس."
            }
            val meta = listOf(found.ipa, found.pos).filter { it.isNotBlank() }.joinToString("  ·  ")
            binding.wordMeta.text = meta
            binding.wordMeta.visibility = if (meta.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
            binding.gloss.text = found.glosses.take(2).joinToString(" · ")
            binding.gloss.visibility = if (found.glosses.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            val src = when {
                found.source.contains("wiktionary") -> "ویکی‌واژه"
                found.source == "vahidn" -> "واژه‌نامهٔ VahidN"
                found.source == "shirin" -> "مجموعهٔ Shirin"
                found.source.contains("koreader") -> "واژه‌نامهٔ KOReader"
                else -> found.source
            }
            binding.dictSource.text = buildString {
                append("منبع: ").append(src)
                found.matchedForm?.let { append("  ·  ").append(it).append(" ← ").append(found.word) }
            }
            binding.dictSource.visibility = android.view.View.VISIBLE
        }
    }

    private fun highlightedLine(): CharSequence {
        val line = pageText.lines.getOrNull(wordBox.line)?.text ?: wordBox.text
        val word = wordBox.text
        val idx = line.indexOf(word, ignoreCase = true)
        if (idx < 0) return line
        val span = SpannableString(line)
        span.setSpan(BackgroundColorSpan(Color.rgb(254, 243, 199)), idx, idx + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(ForegroundColorSpan(Color.rgb(11, 21, 18)), idx, idx + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(StyleSpan(Typeface.BOLD), idx, idx + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    private fun add() {
        binding.addButton.isEnabled = false
        val text = binding.translation.text.toString().trim()
        if (text.isBlank()) {
            Toast.makeText(context, "می‌توانی بعداً از بخش کارت‌ها معنی را اضافه کنی.", Toast.LENGTH_SHORT).show()
        }
        onAdd(text, entry)
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    fun show() = dialog.show()
}
