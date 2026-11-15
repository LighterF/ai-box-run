package ir.lexilens.reader.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.lexilens.reader.App
import ir.lexilens.reader.data.Card
import ir.lexilens.reader.data.CardComposer
import ir.lexilens.reader.data.CardContextBackfill
import ir.lexilens.reader.databinding.SheetCardDetailBinding
import ir.lexilens.reader.tts.AudioPlayer
import ir.lexilens.reader.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CardDetailSheet private constructor(
    private val context: Context,
    private var card: Card,
    private val onChanged: () -> Unit,
) {
    private val app = context.applicationContext as App
    private val dialog = BottomSheetDialog(context)
    private val binding = SheetCardDetailBinding.inflate(LayoutInflater.from(context))
    private val player = AudioPlayer()
    private var suspended = card.suspended

    init {
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnDismissListener { player.release() }
        bind()
    }

    private fun bind() {
        binding.word.text = card.word
        val meta = listOf(card.ipa, card.pos).filter { it.isNotBlank() }.joinToString("  ·  ")
        binding.meta.text = meta
        binding.meta.visibility = if (meta.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        binding.translationInput.setText(card.translation)
        binding.noteInput.setText(card.note)
        binding.sourceLine.text = contextText()
        binding.dueInfo.text = buildString {
            append("وضعیت: ").append(TextUtils.dueLabel(card))
            append("  ·  مرور: ").append(TextUtils.faNumber(card.reps))
            if (card.lapses > 0) append("  ·  فراموشی: ").append(TextUtils.faNumber(card.lapses))
        }
        binding.suspendButton.text = if (suspended) "رفع تعلیق" else "تعلیق"

        if (showCrop(card)) {
            binding.contextView.setCrop(card.cropPath, card.rectL, card.rectT, card.rectR, card.rectB, card.word)
        } else {
            binding.contextView.setText(contextText(), card.word)
        }

        binding.playWord.setOnClickListener { playWord() }
        binding.playSentence.setOnClickListener {
            app.tts.speak(contextText())
        }
        binding.saveButton.setOnClickListener { save() }
        binding.suspendButton.setOnClickListener { toggleSuspend() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        refreshContext()
    }

    private fun refreshContext() {
        if (!CardContextBackfill.needsRefresh(card)) return
        app.bg.launch {
            val updated = CardContextBackfill.refresh(context, app, card) ?: return@launch
            withContext(Dispatchers.Main) {
                if (!dialog.isShowing) return@withContext
                card = updated
                val text = contextText()
                binding.sourceLine.text = text
                if (showCrop(updated)) {
                    binding.contextView.setCrop(updated.cropPath, updated.rectL, updated.rectT, updated.rectR, updated.rectB, updated.word)
                } else {
                    binding.contextView.setText(text, updated.word)
                }
            }
        }
    }

    private fun contextText(): String =
        card.sentence.trim().ifBlank {
            card.sourceLine.trim().ifBlank { card.word }
        }

    private fun showCrop(card: Card): Boolean =
        card.contextRev >= CardComposer.CONTEXT_REV &&
            card.cropPath.isNotBlank() &&
            File(card.cropPath).exists()

    private fun playWord() {
        val f = if (card.audioPath.isNotBlank()) File(card.audioPath) else null
        if (f == null || !player.play(f)) app.tts.speak(card.word)
    }

    private fun save() {
        val updated = card.copy(
            translation = binding.translationInput.text.toString().trim(),
            note = binding.noteInput.text.toString().trim(),
            suspended = suspended,
        )
        app.db.updateCard(updated)
        Toast.makeText(context, "ذخیره شد", Toast.LENGTH_SHORT).show()
        onChanged()
        dialog.dismiss()
    }

    private fun toggleSuspend() {
        suspended = !suspended
        app.db.updateCard(card.copy(suspended = suspended))
        binding.suspendButton.text = if (suspended) "رفع تعلیق" else "تعلیق"
        Toast.makeText(
            context,
            if (suspended) "کارت معلق شد" else "کارت به مرور برگشت",
            Toast.LENGTH_SHORT
        ).show()
        onChanged()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(context)
            .setTitle("حذف «${card.word}»؟")
            .setMessage("این کارت و تصویر جمله‌اش پاک می‌شوند.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف") { _, _ ->
                if (card.cropPath.isNotBlank()) runCatching { File(card.cropPath).delete() }
                if (card.audioPath.isNotBlank()) runCatching { File(card.audioPath).delete() }
                app.db.deleteCard(card.id)
                onChanged()
                dialog.dismiss()
            }
            .show()
    }

    fun show() = dialog.show()

    companion object {
        fun show(context: Context, card: Card, onChanged: () -> Unit) {
            CardDetailSheet(context, card, onChanged).show()
        }
    }
}
