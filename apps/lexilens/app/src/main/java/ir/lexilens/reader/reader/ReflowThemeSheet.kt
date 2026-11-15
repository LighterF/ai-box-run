package ir.lexilens.reader.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.lexilens.reader.databinding.SheetReflowThemeBinding

/**
 * Bottom sheet for the reflow reader's appearance. It applies colours and the
 * font live, so the page behind the sheet changes while the user tries a
 * preset, a swatch or a face.
 */
class ReflowThemeSheet(
    private val context: Context,
    private var background: Int,
    private var text: Int,
    private var fontId: String,
    private var fontOptions: List<ReflowFontOption>,
    private val onColorsChange: (background: Int, text: Int) -> Unit,
    private val onFontChange: (id: String) -> Unit,
    private val onImportFont: () -> Unit,
    private val onDeleteFont: (id: String) -> Unit,
) {
    private val dialog = BottomSheetDialog(context)
    private val binding = SheetReflowThemeBinding.inflate(LayoutInflater.from(context))
    private val presetCards = mutableListOf<Pair<ReflowTheme, LinearLayout>>()
    private val textSwatches = mutableListOf<Pair<Int, FrameLayout>>()
    private val backgroundSwatches = mutableListOf<Pair<Int, FrameLayout>>()
    private val fontCards = mutableListOf<Pair<ReflowFontOption, LinearLayout>>()
    private val density = context.resources.displayMetrics.density

    init {
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        buildPresets()
        buildSwatches()
        buildFonts()
        binding.resetButton.setOnClickListener {
            apply(ReflowThemes.default.background, ReflowThemes.default.text)
        }
        binding.importFontButton.setOnClickListener { onImportFont() }
        binding.doneButton.setOnClickListener { dialog.dismiss() }
        render()
    }

    fun show() = dialog.show()

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    /** Re-renders the font row after an import or a deletion. */
    fun updateFonts(options: List<ReflowFontOption>, selectedId: String) {
        fontOptions = options
        fontId = selectedId
        buildFonts()
        render()
    }

    private fun buildPresets() {
        val margin = dp(3)
        ReflowThemes.presets.forEach { theme ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(7), dp(5), dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = "پریست ${theme.name}"
                setOnClickListener { apply(theme.background, theme.text) }
            }
            val sample = TextView(context).apply {
                text = "Aa"
                textSize = 19f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                setTextColor(theme.text)
                gravity = Gravity.CENTER
            }
            val name = TextView(context).apply {
                text = theme.name
                textSize = 10f
                setTextColor(theme.text)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            card.addView(sample)
            card.addView(name)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(margin, margin, margin, margin)
            }
            binding.presetGrid.addView(card, lp)
            presetCards.add(theme to card)
        }
    }

    private fun buildSwatches() {
        ReflowThemes.textColors.forEach { color ->
            val swatch = makeSwatch(color)
            swatch.setOnClickListener { apply(background, color) }
            binding.textSwatches.addView(swatch, swatchParams())
            textSwatches.add(color to swatch)
        }
        ReflowThemes.backgroundColors.forEach { color ->
            val swatch = makeSwatch(color)
            swatch.setOnClickListener { apply(color, text) }
            binding.backgroundSwatches.addView(swatch, swatchParams())
            backgroundSwatches.add(color to swatch)
        }
    }

    private fun buildFonts() {
        binding.fontRow.removeAllViews()
        fontCards.clear()
        fontOptions.forEach { option ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(7), dp(9), dp(7))
                isClickable = true
                isFocusable = true
                contentDescription = "فونت ${option.label}"
                setOnClickListener { applyFont(option) }
                if (option.removable) {
                    isLongClickable = true
                    setOnLongClickListener {
                        confirmDelete(option)
                        true
                    }
                }
            }
            val sample = TextView(context).apply {
                text = "Aa اب"
                textSize = 19f
                typeface = option.typeface
                gravity = Gravity.CENTER
                maxLines = 1
            }
            val label = TextView(context).apply {
                text = option.label
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val subtitle = TextView(context).apply {
                text = option.subtitle
                textSize = 8.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(sample)
            card.addView(label)
            card.addView(subtitle)
            val lp = LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            }
            binding.fontRow.addView(card, lp)
            fontCards.add(option to card)
        }
    }

    private fun confirmDelete(option: ReflowFontOption) {
        MaterialAlertDialogBuilder(context)
            .setTitle("حذف فونت «${option.label}»؟")
            .setMessage("فقط از فهرست فونت‌های اضافه‌شده حذف می‌شود؛ فایل اصلی روی گوشی دست‌نخورده می‌ماند.")
            .setNegativeButton("نه") { _, _ -> }
            .setPositiveButton("حذف") { _, _ -> onDeleteFont(option.id) }
            .show()
    }

    private fun makeSwatch(color: Int): FrameLayout {
        val frame = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = colorLabel(color)
        }
        val circle = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        frame.addView(
            circle,
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER),
        )
        val check = TextView(context).apply {
            text = "✓"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(if (ReflowThemes.isLight(color)) Color.BLACK else Color.WHITE)
            visibility = View.GONE
        }
        frame.addView(
            check,
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER),
        )
        frame.tag = check
        return frame
    }

    private fun swatchParams(): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = dp(42)
            height = dp(42)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun apply(newBackground: Int, newText: Int) {
        background = newBackground
        text = newText
        render()
        onColorsChange(background, text)
    }

    private fun applyFont(option: ReflowFontOption) {
        if (fontId == option.id) return
        fontId = option.id
        render()
        onFontChange(fontId)
    }

    private fun currentFont(): ReflowFontOption? =
        fontOptions.firstOrNull { it.id == fontId } ?: fontOptions.firstOrNull()

    private fun render() {
        val font = currentFont()
        val face = font?.typeface ?: Typeface.SERIF
        binding.themePreview.typeface = face
        binding.themePreview.setTextColor(text)
        binding.themePreview.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(background)
            setStroke(dp(1), 0x33000000)
        }
        binding.themePreview.text = previewText(font)
        val contrast = ReflowThemes.contrastRatio(background, text)
        binding.contrastWarning.visibility = if (contrast < 3.0) View.VISIBLE else View.GONE

        val selectedPreset = presetCards.firstOrNull { it.first.background == background && it.first.text == text }
        presetCards.forEach { (theme, card) ->
            val selected = selectedPreset?.second === card
            card.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(theme.background)
                setStroke(
                    dp(if (selected) 2 else 1),
                    if (selected) 0xFF0F766E.toInt() else 0x33000000,
                )
            }
            card.scaleX = if (selected) 1.03f else 1f
            card.scaleY = if (selected) 1.03f else 1f
        }
        textSwatches.forEach { (color, frame) -> paintSwatch(frame, color, color == text) }
        backgroundSwatches.forEach { (color, frame) -> paintSwatch(frame, color, color == background) }
        fontCards.forEach { (option, card) ->
            val selected = option.id == fontId
            card.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(background)
                setStroke(
                    dp(if (selected) 2 else 1),
                    if (selected) 0xFFF59E0B.toInt() else 0x33000000,
                )
            }
            (card.getChildAt(0) as? TextView)?.setTextColor(text)
            (card.getChildAt(1) as? TextView)?.setTextColor(text)
            (card.getChildAt(2) as? TextView)?.setTextColor(withAlpha(text, 0.6f))
            card.scaleX = if (selected) 1.03f else 1f
            card.scaleY = if (selected) 1.03f else 1f
        }
    }

    /**
     * Shows a Latin word in italic next to Persian text, so the italic cut (or
     * synthetic slant) of the selected face is visible before leaving the sheet.
     */
    private fun previewText(font: ReflowFontOption?): CharSequence {
        val plain = "خواندن با این فونت روان‌تر است.\nI want to read this."
        val start = plain.indexOf("I want")
        if (start < 0) return plain
        val styled = SpannableString(plain)
        styled.setSpan(
            ReflowItalicSpan(font?.italicTypeface),
            start,
            start + "I want".length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return styled
    }

    private fun paintSwatch(frame: FrameLayout, color: Int, selected: Boolean) {
        frame.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                dp(if (selected) 3 else 1),
                if (selected) 0xFFF59E0B.toInt() else 0x33000000,
            )
        }
        val check = frame.tag as? TextView
        check?.visibility = if (selected) View.VISIBLE else View.GONE
    }

    private fun withAlpha(color: Int, factor: Float): Int =
        Color.argb((Color.alpha(color) * factor).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun colorLabel(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)

    private fun dp(value: Int): Int = (value * density).toInt()
}
