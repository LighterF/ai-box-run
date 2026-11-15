package ir.lexilens.reader.reader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import ir.lexilens.reader.databinding.SheetReflowThemeBinding

/**
 * Bottom sheet for the reflow reader's appearance. It applies the chosen
 * colours live, so the page behind the sheet changes while the user tries a
 * preset or a swatch.
 */
class ReflowThemeSheet(
    private val context: Context,
    private var background: Int,
    private var text: Int,
    private val onChange: (background: Int, text: Int) -> Unit,
) {
    private val dialog = BottomSheetDialog(context)
    private val binding = SheetReflowThemeBinding.inflate(LayoutInflater.from(context))
    private val presetCards = mutableListOf<Pair<ReflowTheme, LinearLayout>>()
    private val textSwatches = mutableListOf<Pair<Int, FrameLayout>>()
    private val backgroundSwatches = mutableListOf<Pair<Int, FrameLayout>>()
    private val density = context.resources.displayMetrics.density

    init {
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        buildPresets()
        buildSwatches()
        binding.resetButton.setOnClickListener {
            apply(ReflowThemes.default.background, ReflowThemes.default.text)
        }
        binding.doneButton.setOnClickListener { dialog.dismiss() }
        render()
    }

    fun show() = dialog.show()

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
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
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SERIF,
                    android.graphics.Typeface.BOLD,
                )
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
        onChange(background, text)
    }

    private fun render() {
        binding.themePreview.setTextColor(text)
        binding.themePreview.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(background)
            setStroke(dp(1), 0x33000000)
        }
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

    private fun colorLabel(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)

    private fun dp(value: Int): Int = (value * density).toInt()
}
