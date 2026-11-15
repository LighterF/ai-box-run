package ir.lexilens.reader.pdf

import kotlin.math.abs
import kotlin.math.roundToInt

/** Normal line step of a section plus whether it had enough samples to trust. */
private data class LineAdvances(val normal: Float, val reliable: Boolean)

/** Maps a range of characters in a reflowed paragraph back to its PDF word. */
data class ReflowWordSpan(
    val wordIndex: Int,
    val start: Int,
    val end: Int,
    val italic: Boolean = false,
)

data class ReflowParagraph(
    val text: String,
    val words: List<ReflowWordSpan>,
    /**
     * Number of blank PDF lines that separated this paragraph from the previous
     * one. The reflow view turns this into extra vertical space so deliberate
     * double line breaks (scene breaks in a story, for example) survive the
     * conversion instead of collapsing into a normal paragraph gap.
     */
    val blankLinesBefore: Int = 0,
) {
    /**
     * Returns the word nearest to [offset]. Spaces and punctuation between two
     * words resolve to the closer one, which is what a long press on a space
     * should do.
     */
    fun wordAt(offset: Int): Int? {
        if (words.isEmpty()) return null
        var best: ReflowWordSpan? = null
        var bestDistance = Int.MAX_VALUE
        for (span in words) {
            if (offset >= span.start && offset < span.end) return span.wordIndex
            val distance = when {
                offset < span.start -> span.start - offset
                else -> offset - span.end + 1
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = span
            }
        }
        return best?.wordIndex
    }
}

data class ReflowContent(val paragraphs: List<ReflowParagraph>) {
    val isEmpty: Boolean get() = paragraphs.isEmpty()
    val characterCount: Int get() = paragraphs.sumOf { it.text.length }

    companion object {
        val EMPTY = ReflowContent(emptyList())
    }
}

/**
 * Turns the fixed, line-by-line text of a PDF page into continuous paragraphs
 * that can be laid out at any width and font size.
 *
 * The input is [PageText.lines] rather than [WordBox.text] on purpose: line
 * text keeps commas, periods and question marks (the word boxes are cleaned
 * for dictionary lookup), and punctuation is what makes the reflowed text
 * readable and the voice natural.
 */
object ReflowText {

    fun build(page: PageText): ReflowContent {
        if (page.lines.isEmpty()) return ReflowContent.EMPTY
        val lines = page.lines.filter { line ->
            line.text.isNotBlank() && !isPageNumber(line, page)
        }
        if (lines.isEmpty()) return ReflowContent.EMPTY

        val rtl = RtlText.rtlShare(lines.joinToString(" ") { it.text }) > 0.5f
        val visualOrder = rtl && linesAreVisual(page)
        val sections = orderReadingUnits(lines, page.width, rtl)
        val medianHeight = median(lines.map { it.h }.filter { it > 0f })
        val paragraphs = mutableListOf<ReflowParagraph>()
        for (section in sections) {
            paragraphs += buildSection(section, page, medianHeight, rtl, visualOrder)
        }
        return ReflowContent(paragraphs)
    }

    /**
     * True when the PDF stores RTL lines in visual (left-to-right) order. The
     * first word of the extracted line then matches the leftmost word box; in a
     * logical-order PDF it matches the rightmost one.
     */
    private fun linesAreVisual(page: PageText): Boolean {
        var visual = 0
        var logical = 0
        for (line in page.lines) {
            if (!RtlText.hasStrongRtl(line.text)) continue
            val first = firstToken(normalize(line.text)) ?: continue
            val left = page.words.getOrNull(line.wordStart) ?: continue
            val right = page.words.getOrNull(line.wordEnd) ?: continue
            if (sameToken(first, left.text)) visual++
            else if (sameToken(first, right.text)) logical++
        }
        return visual > logical
    }

    private fun lineIsVisual(line: LineBox, page: PageText, fallback: Boolean): Boolean {
        if (!RtlText.hasStrongRtl(line.text)) return false
        val first = firstToken(normalize(line.text)) ?: return fallback
        val left = page.words.getOrNull(line.wordStart) ?: return fallback
        val right = page.words.getOrNull(line.wordEnd) ?: return fallback
        if (sameToken(first, left.text)) return true
        if (sameToken(first, right.text)) return false
        return fallback
    }

    private fun firstToken(text: String): String? {
        var start = -1
        for (index in text.indices) {
            if (text[index].isLetterOrDigit()) {
                start = index
                break
            }
        }
        if (start < 0) return null
        var end = start
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '\u200C')) end++
        return text.substring(start, end)
    }

    private fun sameToken(first: String, second: String): Boolean =
        first.equals(second, ignoreCase = true) ||
            first.filter { it.isLetterOrDigit() } == second.filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- columns

    /**
     * Returns the lines in reading order. A single-column page is one unit.
     * For a two-column page every column becomes its own unit, and full-width
     * lines (titles, captions) split the page into horizontal bands so a title
     * stays above the columns it belongs to.
     */
    private fun orderReadingUnits(
        lines: List<LineBox>,
        pageWidth: Float,
        rtl: Boolean,
    ): List<List<LineBox>> {
        val sorted = lines.sortedWith(compareBy({ it.y }, { it.x }))
        if (pageWidth <= 1f || !looksTwoColumn(sorted, pageWidth)) return listOf(sorted)

        val units = mutableListOf<List<LineBox>>()
        var left = mutableListOf<LineBox>()
        var right = mutableListOf<LineBox>()
        fun flushColumns() {
            val ordered = if (rtl) listOf(right, left) else listOf(left, right)
            for (column in ordered) {
                if (column.isNotEmpty()) units.add(column.sortedWith(compareBy({ it.y }, { it.x })))
            }
            left = mutableListOf()
            right = mutableListOf()
        }

        for (line in sorted) {
            when {
                isSpanning(line, pageWidth) -> {
                    flushColumns()
                    units.add(listOf(line))
                }
                line.x + line.w / 2f < pageWidth * 0.5f -> left.add(line)
                else -> right.add(line)
            }
        }
        flushColumns()
        return units.filter { it.isNotEmpty() }
    }

    private fun looksTwoColumn(lines: List<LineBox>, pageWidth: Float): Boolean {
        if (lines.size < 8) return false
        val left = lines.count { it.x + it.w / 2f < pageWidth * 0.5f && it.x < pageWidth * 0.42f }
        val right = lines.count { it.x + it.w / 2f >= pageWidth * 0.5f && it.x > pageWidth * 0.40f }
        if (left < 4 || right < 4) return false
        val leftEdge = lines.filter { it.x + it.w / 2f < pageWidth * 0.5f }.maxOfOrNull { it.x + it.w } ?: 0f
        val rightEdge = lines.filter { it.x + it.w / 2f >= pageWidth * 0.5f }.minOfOrNull { it.x } ?: pageWidth
        // In a real two-column page the left column ends before the right one
        // starts. Overlapping lines mean this is a single justified column.
        return rightEdge - leftEdge > pageWidth * 0.02f
    }

    private fun isSpanning(line: LineBox, pageWidth: Float): Boolean =
        line.w > pageWidth * 0.62f && line.x < pageWidth * 0.35f

    // ------------------------------------------------------------- paragraphs

    private fun buildSection(
        lines: List<LineBox>,
        page: PageText,
        medianHeight: Float,
        rtl: Boolean,
        visualOrder: Boolean,
    ): List<ReflowParagraph> {
        val out = mutableListOf<ReflowParagraph>()
        val text = StringBuilder()
        val words = mutableListOf<ReflowWordSpan>()
        var previous: LineBox? = null
        var previousText: String? = null
        var pendingBlankLines = 0
        val advances = lineAdvances(lines, medianHeight)

        fun flush() {
            val value = text.toString().trim()
            if (value.isNotEmpty()) {
                out.add(ReflowParagraph(value, words.toList(), pendingBlankLines))
            }
            text.setLength(0)
            words.clear()
            pendingBlankLines = 0
        }

        for (line in lines) {
            val raw = normalize(line.text)
            if (raw.isEmpty() || isSymbolLine(raw)) continue
            val visual = rtl && visualOrder && lineIsVisual(line, page, visualOrder)
            val local = if (visual) RtlText.visualToLogical(raw) else raw
            if (local.isBlank()) continue
            val wordIndices = if (rtl && RtlText.hasStrongRtl(raw)) {
                (line.wordStart..line.wordEnd).reversed().toList()
            } else {
                (line.wordStart..line.wordEnd).toList()
            }
            val prev = previous
            val prevText = previousText
            val blankLines = if (prev == null) 0 else blankLinesBefore(prev, line, advances)
            if (prev != null && prevText != null &&
                startsNewParagraph(prev, line, prevText, medianHeight, rtl, advances, blankLines)
            ) {
                flush()
                pendingBlankLines = blankLines
            }
            if (text.isEmpty()) {
                text.append(local)
                appendWordSpans(wordIndices, local, 0, line, page, visual, words)
            } else {
                val joinHyphen = shouldJoinHyphen(prevText.orEmpty(), local)
                if (joinHyphen) {
                    while (text.isNotEmpty() && isHyphen(text.last())) text.setLength(text.length - 1)
                } else if (needsSpace(text.last(), local.first())) {
                    text.append(' ')
                }
                val offset = text.length
                text.append(local)
                appendWordSpans(wordIndices, local, offset, line, page, visual, words)
            }
            previous = line
            previousText = local
        }
        flush()
        return out
    }

    private fun startsNewParagraph(
        previous: LineBox,
        current: LineBox,
        previousText: String,
        medianHeight: Float,
        rtl: Boolean,
        advances: LineAdvances,
        blankLines: Int,
    ): Boolean {
        if (blankLines > 0) return true
        val height = maxOf(previous.h, current.h)
        if (advances.reliable && advances.normal > 0f) {
            val step = lineAdvance(previous, current) / advances.normal
            if (step >= PARAGRAPH_STEP) return true
        } else if (height > 0f) {
            val gap = current.y - previous.bottom
            if (gap > height * 1.7f) return true
        }
        if (looksLikeHeading(current, medianHeight) || looksLikeHeading(previous, medianHeight)) return true
        if (startsBullet(current.text)) return true
        // A first-line indent is the most common paragraph marker in books; in
        // RTL text it sits on the right edge of the line.
        val indent = if (rtl) {
            (previous.x + previous.w) - (current.x + current.w)
        } else {
            current.x - previous.x
        }
        if (indent > maxOf(height, 1f) * 0.9f &&
            !isHyphen(previousText.lastOrNull() ?: ' ')
        ) return true
        return false
    }

    /** Baseline-to-baseline distance that separates two consecutive lines. */
    private fun lineAdvance(above: LineBox, below: LineBox): Float =
        baseline(below) - baseline(above)

    /**
     * The extractor stores a line box as `y = baseline - height` with
     * `h = 1.25 * height`, so this recovers the baseline exactly even when a
     * line contains a larger glyph, superscript or inline object.
     */
    private fun baseline(line: LineBox): Float = line.y + line.h * BASELINE_RATIO

    /**
     * Typical line step in this reading unit. It is computed from the real
     * spacing in the page (the most repeated advance) rather than from the font
     * size, because PDFs use every leading from tight to double-spaced. The
     * mode keeps paragraph gaps, which are few, from inflating the estimate.
     */
    private fun lineAdvances(lines: List<LineBox>, medianHeight: Float): LineAdvances {
        val samples = lines.zipWithNext { above, below -> lineAdvance(above, below) }
            .filter { it > 0f && it.isFinite() }
        if (samples.isEmpty()) {
            return LineAdvances(medianHeight.coerceAtLeast(0f), reliable = false)
        }
        val normal = typicalAdvance(samples)
        return LineAdvances(
            normal = if (normal > 0f) normal else medianHeight.coerceAtLeast(0f),
            reliable = samples.size >= 2,
        )
    }

    /** Most repeated value, tolerant of sub-pixel jitter; ties pick the smaller. */
    private fun typicalAdvance(samples: List<Float>): Float {
        if (samples.isEmpty()) return 0f
        val sorted = samples.sorted()
        var best = sorted.first()
        var bestCount = -1
        for (candidate in sorted) {
            val tolerance = maxOf(0.75f, candidate * 0.06f)
            var count = 0
            for (value in sorted) {
                if (abs(value - candidate) <= tolerance) count++
            }
            if (count > bestCount) {
                bestCount = count
                best = candidate
            }
        }
        return best
    }

    /**
     * Counts the empty PDF lines between two text lines. Paragraph spacing below
     * one full line step is handled by the paragraph gap itself; only a gap of
     * roughly two or more normal steps is treated as a deliberate blank line.
     */
    private fun blankLinesBefore(
        previous: LineBox,
        current: LineBox,
        advances: LineAdvances,
    ): Int {
        if (!advances.reliable || advances.normal <= 0f) return 0
        val steps = lineAdvance(previous, current) / advances.normal
        if (!steps.isFinite() || steps < BLANK_STEP) return 0
        return (steps - 1f).roundToInt().coerceAtLeast(1).coerceAtMost(MAX_BLANK_LINES)
    }

    private fun looksLikeHeading(line: LineBox, medianHeight: Float): Boolean {
        if (medianHeight <= 0f) return false
        val short = normalize(line.text).length <= 48
        return short && line.h > medianHeight * 1.22f
    }

    private fun startsBullet(raw: String): Boolean {
        val text = raw.trimStart()
        if (text.isEmpty()) return false
        if (text.first() in "•◦▪▫●○■□◆◇►▶–—*·") return true
        return BULLET_NUMBER.matches(text.substring(0, minOf(text.length, 5)))
    }

    private fun isSymbolLine(text: String): Boolean {
        var hasLetterOrDigit = false
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                hasLetterOrDigit = true
                break
            }
        }
        return !hasLetterOrDigit && text.length <= 3
    }

    private fun shouldJoinHyphen(previous: String, current: String): Boolean {
        if (previous.isEmpty() || current.isEmpty()) return false
        val last = previous.last()
        if (last != '-' && last != '\u00AD' && last != '\u2010') return false
        val first = current.first()
        return first.isLetterOrDigit()
    }

    private fun isHyphen(ch: Char): Boolean =
        ch == '-' || ch == '\u00AD' || ch == '\u2010'

    private fun needsSpace(last: Char, first: Char): Boolean {
        if (last.isWhitespace() || first.isWhitespace()) return false
        if (first in CLOSING_PUNCTUATION) return false
        if (last in OPENING_PUNCTUATION) return false
        return true
    }

    // ------------------------------------------------------------- word links

    private fun appendWordSpans(
        indices: List<Int>,
        local: String,
        offset: Int,
        line: LineBox,
        page: PageText,
        visual: Boolean,
        out: MutableList<ReflowWordSpan>,
    ) {
        var cursor = 0
        for (index in indices) {
            val word = page.words.getOrNull(index) ?: continue
            val token = word.text
            if (token.isEmpty()) continue
            // In a visual-order RTL line both the line and the word token are
            // mirrored; compare against their logical form.
            val needle = if (visual) RtlText.visualToLogical(token) else token
            var at = local.indexOf(needle, cursor, ignoreCase = true)
            if (at < 0) at = local.indexOf(needle, ignoreCase = true)
            if (at < 0) {
                var ratio = if (line.w > 0f) ((word.x - line.x) / line.w).coerceIn(0f, 1f) else 0f
                if (visual) ratio = 1f - ratio
                at = (ratio * local.length).toInt().coerceIn(0, (local.length - needle.length).coerceAtLeast(0))
            }
            val start = offset + at
            val end = (start + needle.length).coerceAtMost(offset + local.length)
            if (end > start) out.add(ReflowWordSpan(index, start, end, word.italic))
            cursor = (at + needle.length).coerceIn(0, local.length)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun normalize(raw: String): String =
        raw.replace(Regex("\\s+"), " ").trim()

    private fun isPageNumber(line: LineBox, page: PageText): Boolean {
        val text = line.text.trim()
        if (text.length > 6 || !PAGE_NUMBER.matches(text)) return false
        return line.bottom < page.height * 0.12f || line.y > page.height * 0.88f
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private const val BULLET_NUMBER_SOURCE = "\\s*(\\(?\\d{1,3}[.)]|[a-zA-Z][.)])(?=\\s)"
    private val BULLET_NUMBER = Regex(BULLET_NUMBER_SOURCE)
    private val PAGE_NUMBER = Regex(
        "^[\\s\\p{Pd}]*([0-9]{1,4}|[ivxlcdmIVXLCDM]{1,6})[\\s\\p{Pd}]*$"
    )
    private const val CLOSING_PUNCTUATION = ".,;:!?…۔؟،؛»”’)]}"
    private const val OPENING_PUNCTUATION = "«“‘([{"
    private const val MAX_BLANK_LINES = 8
    private const val BASELINE_RATIO = 0.8f
    private const val PARAGRAPH_STEP = 1.32f
    private const val BLANK_STEP = 1.9f
}
